/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.LogRecord.PolicyVersion;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.DataFinding;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Policy evaluation: under a user policy version, whether a candidate's confirmation or a confirmed
 * Fact's retirement proceeds by machine judgement or needs the deciding party's consent (see {@code
 * docs/semantic-catalog/construction.md}, "Choosing the confirmation path").
 *
 * <p>This class makes inline evaluation, the deterministic path. Under {@code auto} it adopts only
 * what deterministic signals cover and sends a refuted candidate or a second Fact to a person;
 * everything else is {@linkplain Decision.Undecided undecided} and left for the policy evaluation
 * model. It reads nothing but its arguments.
 */
public final class PolicyEvaluator {
  private PolicyEvaluator() {}

  /** What inline evaluation decided. */
  public sealed interface Decision {
    String reason();

    /** Machine judgement adopts the transition. */
    record Adopt(String reason) implements Decision {}

    /** The transition needs the deciding party's consent. */
    record RequestConsent(String reason) implements Decision {}

    /** Deterministic signals do not settle it; the policy evaluation model decides. */
    record Undecided(String reason) implements Decision {}
  }

  /**
   * Evaluates the confirmation of a candidate.
   *
   * @param checks the candidate's validation result
   * @param anotherFactSelected whether another Fact is selected at the candidate's Fact Address in
   *     the Catalog Snapshot just before the evaluating commit
   */
  public static Decision evaluateConfirmation(
      PolicyVersion policy, List<Check> checks, boolean anotherFactSelected) {
    switch (policy.preset()) {
      case FULL_AUTO -> {
        return new Decision.Adopt("The full-auto preset adopts every candidate");
      }
      case MANUAL -> {
        return new Decision.RequestConsent("The manual preset asks for consent to every candidate");
      }
      case AUTO -> {}
    }
    if (checks.stream().anyMatch(check -> check.result() == CheckResult.REFUTED)) {
      return new Decision.RequestConsent("A check refutes the candidate");
    }
    if (anotherFactSelected) {
      return new Decision.RequestConsent("Another Fact is selected at the same Fact Address");
    }
    var unsupported = new ArrayList<String>();
    var restOnAbsence = new ArrayList<String>();
    var items = items(checks);
    for (var item : items) {
      var deterministic =
          checks.stream()
              .filter(check -> supports(check, item) && deterministic(policy, check.method()))
              .toList();
      if (deterministic.isEmpty()) {
        unsupported.add(item.toString());
      } else if (deterministic.stream().allMatch(PolicyEvaluator::restsOnAbsence)
          && checks.stream().noneMatch(check -> supports(check, item) && declared(check))) {
        restOnAbsence.add(item.toString());
      }
    }
    if (items.isEmpty()) {
      return new Decision.Undecided("No check supports the candidate deterministically");
    }
    if (!unsupported.isEmpty()) {
      return new Decision.Undecided(
          "No deterministic check supports " + String.join(", ", unsupported));
    }
    if (!restOnAbsence.isEmpty()) {
      return new Decision.Undecided(
          "Only data checks that found no counterexample support "
              + String.join(", ", restOnAbsence)
              + ", and no declaration does");
    }
    return new Decision.Adopt("Deterministic checks support every validation item");
  }

  /**
   * Evaluates the retirement of a confirmed Fact on its open retirement proposal.
   *
   * @param checks the validation result of the proposal
   */
  public static Decision evaluateRetirement(PolicyVersion policy, List<Check> checks) {
    switch (policy.preset()) {
      case FULL_AUTO -> {
        return new Decision.Adopt("The full-auto preset adopts every retirement proposal");
      }
      case MANUAL -> {
        return new Decision.RequestConsent(
            "The manual preset asks for consent to every retirement proposal");
      }
      case AUTO -> {}
    }
    if (checks.stream().anyMatch(check -> check.result() == CheckResult.REFUTED)) {
      return new Decision.RequestConsent("A check refutes the reason for retirement");
    }
    if (checks.stream()
        .anyMatch(
            check ->
                check.result() == CheckResult.SUPPORTED && deterministic(policy, check.method()))) {
      return new Decision.Adopt("A deterministic check supports the reason for retirement");
    }
    return new Decision.Undecided("No deterministic check supports the reason for retirement");
  }

  /** Whether a validation method counts as deterministic under a policy version. */
  public static boolean deterministic(PolicyVersion policy, ValidationMethod method) {
    return switch (method) {
      case DECLARED_ENFORCED, DATA_CHECK -> true;
      case DECLARED_UNENFORCED -> policy.countsDeclaredUnenforced();
      case DOCUMENT, INFERENCE -> false;
    };
  }

  /** The validation items the checks name: each element of an array of items, or the one item. */
  private static Set<JsonNode> items(List<Check> checks) {
    var items = new LinkedHashSet<JsonNode>();
    for (var check : checks) {
      items.addAll(itemsOf(check));
    }
    return items;
  }

  private static List<JsonNode> itemsOf(Check check) {
    var items = check.items();
    if (items.isArray()) {
      var list = new ArrayList<JsonNode>();
      items.forEach(list::add);
      return list;
    }
    return items.isMissingNode() || items.isNull() ? List.of() : List.of(items);
  }

  private static boolean supports(Check check, JsonNode item) {
    return check.result() == CheckResult.SUPPORTED && itemsOf(check).contains(item);
  }

  private static boolean restsOnAbsence(Check check) {
    return check.finding() == DataFinding.NO_COUNTEREXAMPLE_FOUND;
  }

  /** A declaration supplies the intent data cannot, whether or not the source enforces it. */
  private static boolean declared(Check check) {
    return check.method() == ValidationMethod.DECLARED_ENFORCED
        || check.method() == ValidationMethod.DECLARED_UNENFORCED;
  }
}
