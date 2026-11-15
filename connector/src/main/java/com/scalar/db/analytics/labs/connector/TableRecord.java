/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

import java.time.Instant;
import java.util.List;

/** The result of a table metadata request for one requested table. */
public sealed interface TableRecord {
  /** The requested table's name path. */
  List<String> table();

  /**
   * The table was read.
   *
   * @param complete whether no item is missing
   * @param readAt when the Connector read the source's catalog
   */
  record Read(
      List<String> table,
      boolean complete,
      TableMetadata metadata,
      SourceMetadata sourceMetadata,
      Instant readAt)
      implements TableRecord {
    public Read {
      table = List.copyOf(table);
    }
  }

  /** The Connector confirmed that the table does not exist. */
  record DoesNotExist(List<String> table) implements TableRecord {
    public DoesNotExist {
      table = List.copyOf(table);
    }
  }

  /** The table exists, but the credential lacks the privilege to read it. */
  record PermissionDenied(List<String> table, String reason) implements TableRecord {
    public PermissionDenied {
      table = List.copyOf(table);
    }
  }

  /**
   * The table is not found, and whether it exists is unknown, because the source's catalog silently
   * omits tables the credential may not read.
   */
  record NotFound(List<String> table, String reason) implements TableRecord {
    public NotFound {
      table = List.copyOf(table);
    }
  }
}
