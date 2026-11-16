/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.scalar.db.analytics.labs.semantic.CommitRequest;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What one Generator run produces: the records of its commit, in log order, and the inputs its
 * targets lacked.
 *
 * <p>New records refer to one another by the temporary numbers {@link #add} returns, and to
 * existing records by URN. A record that is the same as an existing one is not written, so a
 * Generator proposes the whole of what its input states every time.
 */
public final class Output {
  private final List<LogRecord> records = new ArrayList<>();
  private final Set<String> missing = new LinkedHashSet<>();

  /** Adds a record and returns the temporary number later records refer to it by. */
  public Ref add(LogRecord record) {
    records.add(record);
    return new Ref.Temp(records.size() - 1);
  }

  /**
   * Records that a target lacked an input, so the Generator produced nothing for it.
   *
   * @param what the missing input, such as the Physical Catalog reference of a table
   */
  public void missing(String what) {
    missing.add(what);
  }

  /** The records produced, as one commit request. */
  public CommitRequest request(String operation, String author) {
    var builder = CommitRequest.builder(operation, author);
    records.forEach(builder::add);
    return builder.build();
  }

  public boolean hasRecords() {
    return !records.isEmpty();
  }

  public List<String> missingInputs() {
    return List.copyOf(missing);
  }
}
