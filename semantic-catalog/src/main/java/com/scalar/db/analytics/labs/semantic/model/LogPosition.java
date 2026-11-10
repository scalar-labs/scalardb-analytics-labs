/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import java.util.regex.Pattern;

/**
 * The log position of a log record: its commit number and its order within the commit, both from 1,
 * written {@code c<commit>.<ordinal>} (see {@code docs/semantic-catalog/storage.md},
 * "Identifiers").
 *
 * <p>Log positions are unique and never reused, so URNs issued from them are reproduced exactly
 * when the log is replayed from empty. A point in time is a commit, never a position inside one.
 */
public record LogPosition(long commit, int ordinal) implements Comparable<LogPosition> {
  private static final Pattern FORM = Pattern.compile("c([1-9][0-9]*)\\.([1-9][0-9]*)");

  public LogPosition {
    if (commit < 1 || ordinal < 1) {
      throw new IllegalArgumentException("Log positions start at c1.1: c" + commit + "." + ordinal);
    }
  }

  static LogPosition parse(String text) {
    var matcher = FORM.matcher(text);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Not a log position: " + text);
    }
    return new LogPosition(Long.parseLong(matcher.group(1)), Integer.parseInt(matcher.group(2)));
  }

  @Override
  public int compareTo(LogPosition other) {
    var byCommit = Long.compare(commit, other.commit);
    return byCommit != 0 ? byCommit : Integer.compare(ordinal, other.ordinal);
  }

  @Override
  public String toString() {
    return "c" + commit + "." + ordinal;
  }
}
