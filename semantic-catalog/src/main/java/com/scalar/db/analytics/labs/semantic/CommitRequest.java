/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import java.util.ArrayList;
import java.util.List;

/**
 * The log records to write to a domain's log as one commit, in log order. The commit is written
 * entirely or not at all.
 *
 * <p>A record refers to an earlier record of the same request by the {@linkplain Ref.Temp temporary
 * number} {@link Builder#add} returned for it. Writing the commit replaces temporary numbers with
 * URNs, and a record proposing something that already exists is not written; {@link CommitResult}
 * reports what each temporary number became.
 *
 * @param operation the kind of operation the commit records, kept on the commit
 * @param author the person or agent behind the call, kept on the commit
 * @param records the records, in the order they are written
 */
public record CommitRequest(String operation, String author, List<LogRecord> records) {
  public CommitRequest {
    records = List.copyOf(records);
  }

  public static Builder builder(String operation, String author) {
    return new Builder(operation, author);
  }

  /** Builds a request record by record, handing out temporary numbers. */
  public static final class Builder {
    private final String operation;
    private final String author;
    private final List<LogRecord> records = new ArrayList<>();

    private Builder(String operation, String author) {
      this.operation = operation;
      this.author = author;
    }

    /** Adds a record and returns the temporary number later records refer to it by. */
    public Ref add(LogRecord record) {
      records.add(record);
      return new Ref.Temp(records.size() - 1);
    }

    public CommitRequest build() {
      return new CommitRequest(operation, author, records);
    }
  }
}
