/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.arrow.vector.util.Text;
import org.jspecify.annotations.Nullable;

/** Converts Arrow vector values into values that encode losslessly as JSON. */
public final class ArrowValues {
  private ArrowValues() {}

  public static @Nullable Object toJson(@Nullable Object value) {
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
      return list.stream().map(ArrowValues::toJson).toList();
    }
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, @Nullable Object>();
      map.forEach((key, item) -> result.put(String.valueOf(key), toJson(item)));
      return result;
    }
    return value;
  }
}
