/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Reads the output structures of {@code scalardb_analytics_connector.h} into Java values.
 *
 * <p>Offsets and sizes are those of the C header on the supported 64-bit platforms. Every read
 * copies, so the native result can be freed as soon as it has been read.
 */
final class NativeReader {
  // ScalardbAnalyticsConnectorString and ScalardbAnalyticsConnectorNames: a pointer and a size.
  static final long STRING_SIZE = 16;
  static final long NAMES_SIZE = 16;
  private static final long NAMESPACE_ENTRY_SIZE = 56;
  private static final long TABLE_ENTRY_SIZE = 80;
  private static final long ANALYTICS_TYPE_SIZE = 80;
  private static final long ANALYTICS_FIELD_SIZE = 32;
  private static final long COLUMN_SIZE = 192;
  private static final long CONSTRAINT_SIZE = 112;
  private static final long TABLE_METADATA_SIZE = 136;
  private static final long SOURCE_METADATA_SIZE = 32;
  private static final long TABLE_RECORD_SIZE = 64;
  static final long TABLE_METADATA_RESULT_SIZE = 96;
  static final long LIST_SIZE = 16;
  static final long ERROR_SIZE = 72;

  private static final int LISTED_ITEM = 1;
  private static final int REPORTED_VALUE = 1;
  private static final int REPORTED_MISSING = 2;

  private NativeReader() {}

  static MemorySegment pointer(MemorySegment base, long offset, long size) {
    return base.get(ADDRESS, offset).reinterpret(size);
  }

  static @Nullable String optionalString(MemorySegment base, long offset) {
    var data = base.get(ADDRESS, offset);
    if (data.equals(MemorySegment.NULL)) {
      return null;
    }
    var length = base.get(JAVA_LONG, offset + 8);
    return new String(data.reinterpret(length).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
  }

  static String string(MemorySegment base, long offset) {
    var value = optionalString(base, offset);
    return value == null ? "" : value;
  }

  static boolean bool(MemorySegment base, long offset) {
    return base.get(JAVA_BYTE, offset) != 0;
  }

  static int u32(MemorySegment base, long offset) {
    return base.get(JAVA_INT, offset);
  }

  static List<String> names(MemorySegment base, long offset) {
    var count = base.get(JAVA_LONG, offset + 8);
    var names = new ArrayList<String>();
    if (count == 0) {
      return names;
    }
    var array = pointer(base, offset, count * STRING_SIZE);
    for (long i = 0; i < count; i++) {
      names.add(string(array, i * STRING_SIZE));
    }
    return names;
  }

  static ConnectorException error(MemorySegment error) {
    var segment = error.reinterpret(ERROR_SIZE);
    return new ConnectorException(
        ConnectorException.Kind.of(u32(segment, 0)), bool(segment, 4), string(segment, 8));
  }

  static List<ListedNamespace> namespaceList(MemorySegment list) {
    var segment = list.reinterpret(LIST_SIZE);
    var count = segment.get(JAVA_LONG, 8);
    var entries = new ArrayList<ListedNamespace>();
    if (count == 0) {
      return entries;
    }
    var array = pointer(segment, 0, count * NAMESPACE_ENTRY_SIZE);
    for (long i = 0; i < count; i++) {
      var entry = array.asSlice(i * NAMESPACE_ENTRY_SIZE, NAMESPACE_ENTRY_SIZE);
      entries.add(
          u32(entry, 0) == LISTED_ITEM
              ? new ListedNamespace.Namespace(names(entry, 8))
              : new ListedNamespace.Unreadable(names(entry, 24), string(entry, 40)));
    }
    return entries;
  }

  static List<ListedTable> tableList(MemorySegment list) {
    var segment = list.reinterpret(LIST_SIZE);
    var count = segment.get(JAVA_LONG, 8);
    var entries = new ArrayList<ListedTable>();
    if (count == 0) {
      return entries;
    }
    var array = pointer(segment, 0, count * TABLE_ENTRY_SIZE);
    for (long i = 0; i < count; i++) {
      var entry = array.asSlice(i * TABLE_ENTRY_SIZE, TABLE_ENTRY_SIZE);
      entries.add(
          u32(entry, 0) == LISTED_ITEM
              ? new ListedTable.Table(names(entry, 8), tableKind(u32(entry, 24), entry, 32))
              : new ListedTable.Unreadable(names(entry, 48), string(entry, 64)));
    }
    return entries;
  }

  static TableMetadataResult tableMetadataResult(MemorySegment result) {
    var segment = result.reinterpret(TABLE_METADATA_RESULT_SIZE);
    var count = segment.get(JAVA_LONG, 8);
    var records = new ArrayList<TableRecord>();
    if (count > 0) {
      var array = pointer(segment, 0, count * TABLE_RECORD_SIZE);
      for (long i = 0; i < count; i++) {
        records.add(tableRecord(array.asSlice(i * TABLE_RECORD_SIZE, TABLE_RECORD_SIZE)));
      }
    }
    var retrieval = segment.asSlice(16, 80);
    var adbcDriverName = optionalString(retrieval, 32);
    return new TableMetadataResult(
        records,
        new Retrieval(
            new Retrieval.Component(string(retrieval, 0), string(retrieval, 16)),
            adbcDriverName == null
                ? Optional.empty()
                : Optional.of(new Retrieval.Component(adbcDriverName, string(retrieval, 48))),
            string(retrieval, 64)));
  }

  private static TableRecord tableRecord(MemorySegment record) {
    var table = names(record, 8);
    return switch (u32(record, 0)) {
      case 1, 2 -> {
        var source = pointer(record, 32, SOURCE_METADATA_SIZE);
        var micros = record.get(JAVA_LONG, 40);
        yield new TableRecord.Read(
            table,
            u32(record, 0) == 1,
            tableMetadata(pointer(record, 24, TABLE_METADATA_SIZE)),
            new SourceMetadata(string(source, 0), string(source, 16)),
            Instant.EPOCH.plus(micros, ChronoUnit.MICROS));
      }
      case 3 -> new TableRecord.DoesNotExist(table);
      case 4 -> new TableRecord.PermissionDenied(table, string(record, 48));
      default -> new TableRecord.NotFound(table, string(record, 48));
    };
  }

  private static TableMetadata tableMetadata(MemorySegment metadata) {
    var columnCount = metadata.get(JAVA_LONG, 88);
    var columns = new ArrayList<Column>();
    if (columnCount > 0) {
      var array = pointer(metadata, 80, columnCount * COLUMN_SIZE);
      for (long i = 0; i < columnCount; i++) {
        columns.add(column(array.asSlice(i * COLUMN_SIZE, COLUMN_SIZE)));
      }
    }
    Reported<List<Constraint>> constraints =
        switch (u32(metadata, 100)) {
          case REPORTED_VALUE -> {
            var count = metadata.get(JAVA_LONG, 128);
            var values = new ArrayList<Constraint>();
            if (count > 0) {
              var array = pointer(metadata, 120, count * CONSTRAINT_SIZE);
              for (long i = 0; i < count; i++) {
                values.add(constraint(array.asSlice(i * CONSTRAINT_SIZE, CONSTRAINT_SIZE)));
              }
            }
            yield new Reported.Value<>(List.copyOf(values));
          }
          case REPORTED_MISSING -> new Reported.Missing<>(string(metadata, 104));
          default -> new Reported.NotOffered<>();
        };
    return new TableMetadata(
        names(metadata, 0),
        tableKind(u32(metadata, 16), metadata, 24),
        reportedText(metadata, 40),
        columns,
        bool(metadata, 96),
        constraints);
  }

  private static TableKind tableKind(int kind, MemorySegment base, long otherKindOffset) {
    return switch (kind) {
      case 1 -> new TableKind.Table();
      case 2 -> new TableKind.View();
      case 3 -> new TableKind.External();
      default -> new TableKind.Other(string(base, otherKindOffset));
    };
  }

  private static Reported<String> reportedText(MemorySegment base, long offset) {
    return switch (u32(base, offset)) {
      case REPORTED_VALUE -> new Reported.Value<>(string(base, offset + 8));
      case REPORTED_MISSING -> new Reported.Missing<>(string(base, offset + 24));
      default -> new Reported.NotOffered<>();
    };
  }

  private static Column column(MemorySegment column) {
    Column.Type type =
        switch (u32(column, 64)) {
          case 1 ->
              new Column.Type.Mapped(
                  string(column, 72), analyticsType(column.asSlice(88, ANALYTICS_TYPE_SIZE)));
          case 2 ->
              new Column.Type.Unsupported(
                  string(column, 72), Column.UnsupportedReason.values()[u32(column, 168) - 1]);
          default -> new Column.Type.Unread(string(column, 176));
        };
    return new Column(
        string(column, 0), u32(column, 16), bool(column, 20), reportedText(column, 24), type);
  }

  private static AnalyticsType analyticsType(MemorySegment type) {
    var kind = u32(type, 0);
    return switch (kind) {
      case 8 ->
          new AnalyticsType.Decimal(
              Byte.toUnsignedInt(type.get(JAVA_BYTE, 4)), type.get(JAVA_BYTE, 5));
      case 17 ->
          new AnalyticsType.Array(
              analyticsType(pointer(type, 8, ANALYTICS_TYPE_SIZE)), bool(type, 16));
      case 18 ->
          new AnalyticsType.Map(
              analyticsType(pointer(type, 24, ANALYTICS_TYPE_SIZE)),
              analyticsType(pointer(type, 32, ANALYTICS_TYPE_SIZE)),
              bool(type, 40));
      case 19 -> {
        var count = type.get(JAVA_LONG, 56);
        var fields = new ArrayList<AnalyticsType.Field>();
        if (count > 0) {
          var array = pointer(type, 48, count * ANALYTICS_FIELD_SIZE);
          for (long i = 0; i < count; i++) {
            var field = array.asSlice(i * ANALYTICS_FIELD_SIZE, ANALYTICS_FIELD_SIZE);
            fields.add(
                new AnalyticsType.Field(
                    string(field, 0),
                    analyticsType(pointer(field, 16, ANALYTICS_TYPE_SIZE)),
                    bool(field, 24)));
          }
        }
        yield new AnalyticsType.Struct(fields);
      }
      case 22 -> {
        var count = type.get(JAVA_LONG, 72);
        var members = new ArrayList<String>();
        if (count > 0) {
          var array = pointer(type, 64, count * STRING_SIZE);
          for (long i = 0; i < count; i++) {
            members.add(string(array, i * STRING_SIZE));
          }
        }
        yield new AnalyticsType.Enum(members);
      }
      default -> new AnalyticsType.Scalar(scalarKind(kind));
    };
  }

  private static AnalyticsType.ScalarKind scalarKind(int kind) {
    return switch (kind) {
      case 1 -> AnalyticsType.ScalarKind.BOOLEAN;
      case 2 -> AnalyticsType.ScalarKind.BYTE;
      case 3 -> AnalyticsType.ScalarKind.SMALL_INT;
      case 4 -> AnalyticsType.ScalarKind.INT;
      case 5 -> AnalyticsType.ScalarKind.BIG_INT;
      case 6 -> AnalyticsType.ScalarKind.FLOAT;
      case 7 -> AnalyticsType.ScalarKind.DOUBLE;
      case 9 -> AnalyticsType.ScalarKind.TEXT;
      case 10 -> AnalyticsType.ScalarKind.BLOB;
      case 11 -> AnalyticsType.ScalarKind.DATE;
      case 12 -> AnalyticsType.ScalarKind.TIME;
      case 13 -> AnalyticsType.ScalarKind.TIMESTAMP;
      case 14 -> AnalyticsType.ScalarKind.TIMESTAMP_TZ;
      case 15 -> AnalyticsType.ScalarKind.DURATION;
      case 16 -> AnalyticsType.ScalarKind.INTERVAL;
      case 20 -> AnalyticsType.ScalarKind.UUID;
      case 21 -> AnalyticsType.ScalarKind.JSON;
      case 23 -> AnalyticsType.ScalarKind.YEAR;
      case 24 -> AnalyticsType.ScalarKind.TIME_WITH_TIME_ZONE;
      case 25 -> AnalyticsType.ScalarKind.UNSIGNED_BYTE;
      case 26 -> AnalyticsType.ScalarKind.UNSIGNED_SMALL_INT;
      case 27 -> AnalyticsType.ScalarKind.UNSIGNED_INT;
      case 28 -> AnalyticsType.ScalarKind.UNSIGNED_BIG_INT;
      default -> throw new IllegalStateException("Unknown Analytics type kind " + kind);
    };
  }

  private static Constraint constraint(MemorySegment constraint) {
    var name = string(constraint, 0);
    var enforced = bool(constraint, 16);
    return switch (u32(constraint, 20)) {
      case 1 -> new Constraint.PrimaryKey(name, enforced, names(constraint, 24));
      case 2 ->
          new Constraint.Unique(
              name,
              enforced,
              names(constraint, 24),
              u32(constraint, 40) == 2
                  ? Constraint.UniqueKind.INDEX
                  : Constraint.UniqueKind.CONSTRAINT,
              Optional.ofNullable(optionalString(constraint, 48)));
      case 3 ->
          new Constraint.ForeignKey(
              name, enforced, names(constraint, 24), names(constraint, 64), names(constraint, 80));
      default -> new Constraint.Check(name, enforced, string(constraint, 96));
    };
  }
}
