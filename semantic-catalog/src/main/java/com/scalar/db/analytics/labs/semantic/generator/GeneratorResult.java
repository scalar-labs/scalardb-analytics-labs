/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.DependencyRef;
import com.scalar.db.analytics.labs.semantic.model.ElementDescriptor;
import com.scalar.db.analytics.labs.semantic.model.Predicates;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.RetirementReason;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What one Generator run returns: candidates in the order they are to be accepted, retirement
 * proposals, and the inputs it found missing.
 */
public record GeneratorResult(
    List<Candidate> candidates, List<RetirementProposal> retirements, List<String> missingInputs) {
  public GeneratorResult {
    candidates = List.copyOf(candidates);
    retirements = List.copyOf(retirements);
    missingInputs = List.copyOf(missingInputs);
  }

  public static GeneratorResult of(List<Candidate> candidates) {
    return new GeneratorResult(candidates, List.of(), List.of());
  }

  /**
   * A candidate to be accepted.
   *
   * <p>A dependency or rationale reference to a Fact may give the Fact URN instead of a Fact
   * Revision URN; the Semantic Catalog resolves it to the Fact's latest revision when it accepts
   * the candidate. A candidate may depend on a candidate earlier in the same result.
   *
   * @param descriptor the element's identity, required for an existence candidate
   * @param basis log record identifiers of answers the candidate was produced from
   */
  public record Candidate(
      String element,
      @Nullable ElementDescriptor descriptor,
      String predicate,
      Scope scope,
      JsonNode value,
      List<DependencyRef> dependencies,
      List<Check> checks,
      List<String> basis) {
    public Candidate {
      dependencies = List.copyOf(dependencies);
      checks = List.copyOf(checks);
      basis = List.copyOf(basis);
    }

    public static Candidate existence(
        ElementDescriptor descriptor, List<DependencyRef> dependencies, List<Check> checks) {
      return new Candidate(
          descriptor.urn(),
          descriptor,
          Predicates.EXISTS,
          Scope.UNRESTRICTED,
          com.scalar.db.analytics.labs.semantic.model.Json.object().put("exists", true),
          dependencies,
          checks,
          List.of());
    }
  }

  public record RetirementProposal(
      String fact, RetirementReason reason, List<DependencyRef> dependencies, List<Check> checks) {
    public RetirementProposal {
      dependencies = List.copyOf(dependencies);
      checks = List.copyOf(checks);
    }
  }
}
