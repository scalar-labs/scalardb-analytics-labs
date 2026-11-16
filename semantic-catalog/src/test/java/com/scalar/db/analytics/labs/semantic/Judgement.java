/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.DataFinding;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.RetirementReason;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Shorthands for the checks, policies, and answers the judgement tests write. */
final class Judgement {
  private Judgement() {}

  static Check check(ValidationMethod method, String item, CheckResult result) {
    return new Check(
        method,
        JsonNodeFactory.instance.textNode(item),
        result,
        method == ValidationMethod.DATA_CHECK ? DataFinding.COUNTEREXAMPLE_FOUND : null,
        null,
        Scenario.NOTHING);
  }

  static Check supported(ValidationMethod method, String item) {
    return check(method, item, CheckResult.SUPPORTED);
  }

  /** A data check that supports an item because it found no counterexample. */
  static Check noCounterexample(String item) {
    return new Check(
        ValidationMethod.DATA_CHECK,
        JsonNodeFactory.instance.textNode(item),
        CheckResult.SUPPORTED,
        DataFinding.NO_COUNTEREXAMPLE_FOUND,
        null,
        Scenario.NOTHING);
  }

  static void setPolicy(Scenario s, Preset preset, boolean countsDeclaredUnenforced) {
    s.store.setPolicyVersion(
        s.domain, new LogRecord.PolicyVersion(preset, countsDeclaredUnenforced, "owner", "test"));
  }

  static void setPolicy(Scenario s, Preset preset) {
    setPolicy(s, preset, false);
  }

  /**
   * A Fact and its acceptance in one commit request.
   *
   * @param fact the temporary number of the Fact
   * @param accept the temporary number of its acceptance, whose Fact Revision a dependency names
   */
  record Accepted(Ref fact, Ref accept) {
    /** A dependency on the Fact, in the same request. */
    Dependency.OnFact on() {
      return new Dependency.OnFact(fact, accept);
    }
  }

  /** A closed-range Fact accepted with checks. */
  static Accepted fact(
      Scenario.Commit c, Ref address, String value, List<Check> checks, Dependency... on) {
    var fact = c.closedRangeFact(address, value);
    var accept =
        c.add(new LogRecord.Accept(fact, List.of(on), checks, Scenario.NOTHING, Scenario.NOTHING));
    return new Accepted(fact, accept);
  }

  /**
   * A Dataset with its existence and physical-catalog-reference Facts.
   *
   * @param dataset the temporary number of the Dataset
   * @param reference its physical-catalog-reference Fact
   */
  record Dataset(Ref dataset, Accepted existence, Accepted reference) {}

  /** A Dataset whose existence and Physical Catalog reference the source declares and enforces. */
  static Dataset declaredDataset(Scenario.Commit c, String table) {
    var reference = Scenario.table(table);
    var dataset =
        c.add(new LogRecord.Element(ElementIdentity.PhysicalCatalogObject.dataset(reference)));
    var existence =
        fact(
            c,
            c.address(dataset, Vocabulary.EXISTENCE),
            "exists",
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, "exists")));
    var referenceFact =
        fact(
            c,
            c.address(dataset, Vocabulary.PHYSICAL_CATALOG_REFERENCE),
            reference.canonical(),
            List.of(supported(ValidationMethod.DECLARED_ENFORCED, reference.canonical())));
    return new Dataset(dataset, existence, referenceFact);
  }

  static Answers.Answer answer(String fact, AnswerType type) {
    return answer(fact, Transition.CONFIRM, type, null);
  }

  static Answers.Answer answer(
      String fact, Transition transition, AnswerType type, @Nullable Long presentation) {
    return new Answers.Answer(
        fact,
        transition,
        type,
        type == AnswerType.CORRECTION ? "correct, except for corporate members" : "as presented",
        type == AnswerType.CORRECTION ? "every e-commerce customer is a POS member" : null,
        presentation);
  }

  static Answers.Outcomes submit(Scenario s, Answers.Answer... answers) {
    return s.store.submitAnswers(
        s.domain, new Answers.Submission("owner", "authority-check", List.of(answers)));
  }

  static Ref proposeRetire(Scenario.Commit c, Ref fact, Check... checks) {
    return c.add(
        new LogRecord.ProposeRetire(
            fact,
            RetirementReason.ELEMENT_NO_LONGER_EXISTS,
            List.of(checks),
            Scenario.NOTHING,
            Scenario.NOTHING));
  }
}
