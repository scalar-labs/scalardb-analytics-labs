/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.TimeMicroVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.complex.FixedSizeListVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.util.Text;
import org.jspecify.annotations.Nullable;

/**
 * Converts Arrow result values to values that serialize as JSON: strings, numbers, booleans, null,
 * lists, and maps. Integers and decimals that JSON numbers cannot carry exactly become strings.
 */
public final class ArrowJson {
  private ArrowJson() {}

  /**
   * Converts one value of a vector, descending into lists, arrays, maps, and structs. Arrow
   * represents dates, times of day, and zoned timestamps as plain numbers, so those are rendered as
   * ISO-8601 strings at any depth, like timestamps without a zone.
   */
  public static @Nullable Object value(FieldVector vector, int index) {
    if (vector.isNull(index)) {
      return null;
    }
    return switch (vector) {
      case DateDayVector dates -> LocalDate.ofEpochDay(dates.get(index)).toString();
      case TimeMicroVector times -> timeOfDay(times.get(index));
      case TimeStampVector timestamps
          when vector.getField().getType() instanceof ArrowType.Timestamp type
              && type.getTimezone() != null ->
          instant(timestamps.get(index), type.getUnit()).toString();
      case FixedSizeListVector array -> {
        var elements = new ArrayList<@Nullable Object>();
        var values = array.getDataVector();
        var start = index * array.getListSize();
        for (var element = start; element < start + array.getListSize(); element++) {
          elements.add(value(values, element));
        }
        yield elements;
      }
      // A map vector is a list of key and value structs, so maps render as lists of entries.
      case ListVector list -> {
        var elements = new ArrayList<@Nullable Object>();
        var values = list.getDataVector();
        for (var element = list.getElementStartIndex(index);
            element < list.getElementEndIndex(index);
            element++) {
          elements.add(value(values, element));
        }
        yield elements;
      }
      case StructVector struct -> {
        var fields = new LinkedHashMap<String, @Nullable Object>();
        for (var child : struct.getChildrenFromFields()) {
          fields.put(child.getName(), value(child, index));
        }
        yield fields;
      }
      default -> plain(vector.getObject(index));
    };
  }

  /** Renders a time of day, including the end-of-day value {@code 24:00:00} that SQL allows. */
  private static String timeOfDay(long microseconds) {
    return microseconds == TimeUnit.DAYS.toMicros(1)
        ? "24:00:00"
        : LocalTime.ofNanoOfDay(TimeUnit.MICROSECONDS.toNanos(microseconds)).toString();
  }

  private static Instant instant(long value, org.apache.arrow.vector.types.TimeUnit unit) {
    return switch (unit) {
      case SECOND -> Instant.ofEpochSecond(value);
      case MILLISECOND -> Instant.ofEpochMilli(value);
      case MICROSECOND ->
          Instant.ofEpochSecond(
              Math.floorDiv(value, 1_000_000L), Math.floorMod(value, 1_000_000L) * 1_000L);
      case NANOSECOND ->
          Instant.ofEpochSecond(
              Math.floorDiv(value, 1_000_000_000L), Math.floorMod(value, 1_000_000_000L));
    };
  }

  private static Object plain(Object value) {
    if (value == null || value instanceof Boolean || value instanceof String) {
      return value;
    }
    if (value instanceof Text text) {
      return text.toString();
    }
    if (value instanceof BigInteger integer) {
      return integer.toString();
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.toPlainString();
    }
    if (value instanceof byte[] bytes) {
      return Base64.getEncoder().encodeToString(bytes);
    }
    if (value instanceof TemporalAccessor || value instanceof UUID) {
      return value.toString();
    }
    if (value instanceof List<?> list) {
      return list.stream().map(ArrowJson::plain).toList();
    }
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> result.put(String.valueOf(key), plain(item)));
      return result;
    }
    return value;
  }
}
