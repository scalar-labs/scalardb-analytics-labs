/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

/**
 * The point a read is made at: a commit (see {@code docs/semantic-catalog/public-api.md}, "Common
 * contract"). Wall-clock time is never a point, because it is neither unique nor monotonic.
 */
public sealed interface Point {
  /** The last commit visible when the read is received. */
  Point LATEST = new Latest();

  /** The last commit visible to the read's transaction. */
  record Latest() implements Point {}

  /**
   * A given commit. The same commit always reads the same Catalog Snapshot.
   *
   * @param commit the commit number; 0 is the empty catalog before the first commit
   */
  record At(long commit) implements Point {
    public At {
      if (commit < 0) {
        throw new IllegalArgumentException("Commit numbers are not negative: " + commit);
      }
    }
  }

  static Point at(long commit) {
    return new At(commit);
  }
}
