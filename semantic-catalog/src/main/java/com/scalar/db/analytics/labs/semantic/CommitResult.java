/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.Ref;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * What writing a {@link CommitRequest} did.
 *
 * @param commit the number of the commit written; empty when every record of the request was the
 *     same as an existing record, so nothing was written
 * @param urns the URN each temporary number of the request now stands for, by temporary number: the
 *     URN issued for a written record, the URN of the existing record a proposal was the same as,
 *     or, for a record that creates nothing, the URN of its log record
 * @param transitions what policy evaluation and cascades moved in the same commit
 * @param buildTasks the build tasks the commit created or added a trigger to, in the same
 *     transaction
 */
public record CommitResult(
    OptionalLong commit,
    Map<Integer, String> urns,
    Transitions transitions,
    List<Long> buildTasks) {
  public CommitResult {
    urns = Map.copyOf(urns);
    buildTasks = List.copyOf(buildTasks);
  }

  /**
   * The URN a temporary number of the request stands for.
   *
   * @throws IllegalArgumentException when the reference is not a temporary number of the request
   */
  public String urn(Ref temporaryNumber) {
    if (!(temporaryNumber instanceof Ref.Temp temp)) {
      throw new IllegalArgumentException("Not a temporary number: " + temporaryNumber);
    }
    var urn = urns.get(temp.number());
    if (urn == null) {
      throw new IllegalArgumentException("Not in the request: " + temp.number());
    }
    return urn;
  }

  /** The URN a temporary number stands for, as a reference for later requests. */
  public Ref ref(Ref temporaryNumber) {
    return Ref.urn(urn(temporaryNumber));
  }
}
