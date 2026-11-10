/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A reference from a log record to another record: its URN, or, for a record of the same commit
 * request, its temporary number.
 *
 * <p>Generator output and other commit requests refer to records not written yet by temporary
 * numbers (see {@code docs/semantic-catalog/storage.md}, "Identifiers"). Writing the commit
 * replaces every temporary number with the URN issued for the record, or with the URN of the
 * existing record it turned out to be the same as. Log records stored in the log hold only {@link
 * Urn} references.
 */
public sealed interface Ref {
  /** A reference to a written record by its URN. */
  record Urn(String value) implements Ref {
    public Urn {
      if (!value.startsWith(Urns.PREFIX)) {
        throw new IllegalArgumentException("Not a Semantic Catalog URN: " + value);
      }
    }

    @JsonValue
    @Override
    public String toString() {
      return value;
    }
  }

  /**
   * A reference to a record of the same commit request.
   *
   * @param number the record's index in the request, as {@code CommitRequest.Builder.add} returns
   *     it; it may refer only to a record placed before the referring one
   */
  record Temp(int number) implements Ref {
    public Temp {
      if (number < 0) {
        throw new IllegalArgumentException("Temporary numbers are not negative: " + number);
      }
    }
  }

  static Ref urn(String value) {
    return new Urn(value);
  }

  /** Reads a stored reference, which is always a URN. */
  @JsonCreator
  static Ref fromJson(String value) {
    return new Urn(value);
  }

  /**
   * The URN of a resolved reference.
   *
   * @throws IllegalStateException when the reference is still a temporary number
   */
  default String urnValue() {
    return switch (this) {
      case Urn urn -> urn.value();
      case Temp temp ->
          throw new IllegalStateException("Unresolved temporary number " + temp.number());
    };
  }
}
