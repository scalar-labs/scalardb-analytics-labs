/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.storage;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** The Spring Data JDBC aggregates of the records the Semantic Catalog keeps outside the log. */
public final class Rows {
  private Rows() {}

  public static final String SCHEMA = "semantic_catalog";

  /** A domain, one per Physical Catalog catalog. */
  @Table(schema = SCHEMA, name = "domains")
  public record DomainRow(@Id String name, Instant createdAt) {}

  /** A build task. The identifier is {@code <domain>/<task id>}; the record is the task as JSON. */
  @Table(schema = SCHEMA, name = "build_tasks")
  public record BuildTaskRow(@Id String id, String domain, long seq, String record) {}

  /** A review presentation record, identified like a build task. */
  @Table(schema = SCHEMA, name = "review_presentations")
  public record PresentationRow(@Id String id, String domain, long seq, String record) {}

  /** A call identifier and the output of its first call, identified like a build task. */
  @Table(schema = SCHEMA, name = "call_records")
  public record CallRow(
      @Id String id,
      String domain,
      String operation,
      String inputDigest,
      String output,
      Instant recordedAt) {}
}
