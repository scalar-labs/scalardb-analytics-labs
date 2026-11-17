/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.KnowledgeRequest;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.projection.DimensionValues.Granularity;
import com.scalar.db.analytics.labs.semantic.projection.DimensionValues.Hierarchy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Calendar Dimensions and a Measure's default time granularity at acceptance: a calendar's rule is
 * its wording, its hierarchy names its levels with optional expressions over the time value, both
 * read in the form of the kind they depend on, and a granularity rests on its Dimension being a
 * calendar (see {@code docs/semantic-catalog/record-model.md}, "Elements and predicates").
 */
class DimensionValuesTest extends StoreScenarioTest {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private static final String CLOSING_ON_THE_20TH =
      "A fiscal month runs from the 21st to the 20th of the next month and is named by the month"
          + " it ends in; a boundary that falls on a holiday moves to the next business day.";

  /** A fiscal calendar with its kind written, and a Measure. */
  private record Sample(Scenario s, String calendar, String kind, String measure) {}

  private Sample sample() {
    var s = scenario();
    var c = s.begin();
    var calendar = c.add(dimension("fiscal-calendar"));
    c.closedRangeCandidate(c.address(calendar, Vocabulary.EXISTENCE), "exists");
    var kind = c.closedRangeCandidate(c.address(calendar, Vocabulary.DIMENSION_KIND), "calendar");
    var measure = c.measure("ec-sales");
    var written = s.write(c);
    return new Sample(s, written.urn(calendar), written.urn(kind), written.urn(measure));
  }

  private static LogRecord.Element dimension(String slug) {
    return new LogRecord.Element(
        new ElementIdentity.Unique(Vocabulary.ElementKind.DIMENSION, slug));
  }

  /** A hierarchy of levels given as name and expression pairs; an empty expression is omitted. */
  private static JsonNode levels(String... namesAndExpressions) {
    var node = JSON.objectNode();
    var list = node.putArray("levels");
    for (var i = 0; i < namesAndExpressions.length; i += 2) {
      var level = list.addObject().put("name", namesAndExpressions[i]);
      if (!namesAndExpressions[i + 1].isEmpty()) {
        level.put("expression", namesAndExpressions[i + 1]);
      }
    }
    return node;
  }

  /** A Fact of an element's predicate accepted resting on the given dependencies. */
  private static Ref fact(
      Scenario.Commit c,
      String element,
      String predicate,
      JsonNode value,
      Dependency... dependencies) {
    var fact = c.add(new LogRecord.Fact(c.address(Ref.urn(element), predicate), value, null));
    c.accept(fact, dependencies);
    return fact;
  }

  private static ObjectNode granularity(String dimension, String level) {
    return JSON.objectNode().put("dimension", dimension).put("level", level);
  }

  @Test
  void aCalendarsRuleAndHierarchyRestOnItsKind() {
    var sample = sample();
    var s = sample.s();
    var c = s.begin();
    var kind = s.on(sample.kind());
    var rule =
        fact(
            c,
            sample.calendar(),
            Vocabulary.CALENDAR_RULE,
            JSON.textNode(CLOSING_ON_THE_20TH),
            kind);
    var hierarchy =
        fact(
            c,
            sample.calendar(),
            Vocabulary.HIERARCHY,
            levels(
                "fiscal year",
                "",
                "fiscal month",
                "strftime(time_value - INTERVAL 20 DAY, '%Y-%m')",
                "business day",
                ""),
            kind);
    var written = s.write(c);

    assertThat(s.revision(written.urn(rule)).value().asText()).isEqualTo(CLOSING_ON_THE_20TH);
    assertThat(Hierarchy.of(s.revision(written.urn(hierarchy)).value()).levels())
        .extracting(level -> level.name())
        .containsExactly("fiscal year", "fiscal month", "business day");
  }

  @Test
  void aCalendarRuleThatIsNoWordingIsNotAccepted() {
    for (var value : List.<JsonNode>of(JSON.textNode(" "), JSON.numberNode(4), JSON.objectNode())) {
      var sample = sample();
      var c = sample.s().begin();
      fact(c, sample.calendar(), Vocabulary.CALENDAR_RULE, value, sample.s().on(sample.kind()));
      assertRejected(sample.s(), c, "wording");
    }
  }

  @Test
  void aHierarchyOutsideItsFormIsNotAccepted() {
    var unnamed = JSON.objectNode();
    unnamed.putArray("levels").addObject().put("expression", "year(time_value)");
    for (var value :
        List.of(levels(), levels("month", "", "month", ""), unnamed, JSON.textNode("monthly"))) {
      var sample = sample();
      var c = sample.s().begin();
      fact(c, sample.calendar(), Vocabulary.HIERARCHY, value, sample.s().on(sample.kind()));
      assertRejected(sample.s(), c, "hierarchy");
    }
  }

  @Test
  void aLevelsExpressionIsParsedAndReadsOnlyTheTimeValue() {
    for (var expression : List.of("strftime(amount, '%Y')", "strftime(time_value,", "(SELECT 1)")) {
      var sample = sample();
      var c = sample.s().begin();
      fact(
          c,
          sample.calendar(),
          Vocabulary.HIERARCHY,
          levels("month", expression),
          sample.s().on(sample.kind()));
      var before = sample.s().lastCommit();
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> sample.s().write(c))
          .as(expression)
          .isInstanceOf(com.scalar.db.analytics.labs.semantic.model.CommitRejectedException.class);
      assertThat(sample.s().lastCommit()).isEqualTo(before);
    }
  }

  @Test
  void aKindSpecificValueWithoutTheKindItIsReadInIsNotAccepted() {
    var sample = sample();
    var c = sample.s().begin();
    fact(c, sample.calendar(), Vocabulary.CALENDAR_RULE, JSON.textNode(CLOSING_ON_THE_20TH));
    assertRejected(sample.s(), c, "exactly one kind");
  }

  @Test
  void aKindThatIsNotDefinedIsNotAccepted() {
    var s = scenario();
    var c = s.begin();
    var dimension = c.add(dimension("age-band"));
    c.closedRangeCandidate(c.address(dimension, Vocabulary.EXISTENCE), "exists");
    c.closedRangeCandidate(c.address(dimension, Vocabulary.DIMENSION_KIND), "attribute");
    assertRejected(s, c, "No kind of Dimension is attribute");
  }

  // Classifications

  private static final String AGE_BANDS =
      "Members are grouped by their age today into ten-year bands, from under 20 to 60 and over.";

  private static final String AGE_BAND =
      "CASE WHEN date_diff('year', birth_date, current_date) < 20 THEN 'under 20'"
          + " WHEN date_diff('year', birth_date, current_date) >= 60 THEN '60 and over'"
          + " ELSE CAST(date_diff('year', birth_date, current_date) // 10 * 10 AS VARCHAR) || 's'"
          + " END";

  /**
   * A classification with its kind written, over a members Dataset whose birth date and prefecture
   * are Fields, and an orders Dataset with an amount Field.
   */
  private record Classification(
      Scenario s, String dimension, String kind, String birthDate, String amount) {}

  private Classification classification() {
    var s = scenario();
    var c = s.begin();
    var members = c.dataset("crm.public.members");
    var birthDate = c.field(members, "crm.public.members.birth_date");
    c.field(members, "crm.public.members.prefecture");
    var orders = c.dataset("shop.public.orders");
    var amount = c.field(orders, "shop.public.orders.amount");
    var dimension = c.add(dimension("age-band"));
    c.closedRangeCandidate(c.address(dimension, Vocabulary.EXISTENCE), "exists");
    var kind =
        c.closedRangeCandidate(c.address(dimension, Vocabulary.DIMENSION_KIND), "classification");
    var written = s.write(c);
    return new Classification(
        s, written.urn(dimension), written.urn(kind), written.urn(birthDate), written.urn(amount));
  }

  private static String existenceOf(String element) {
    return com.scalar.db.analytics.labs.semantic.model.Urns.fact(
        com.scalar.db.analytics.labs.semantic.model.Urns.factAddress(element, Vocabulary.EXISTENCE),
        1);
  }

  private static List<String> factDependencies(Scenario s, String fact) {
    return s.revision(fact).dependencies().stream()
        .filter(Dependency.OnFact.class::isInstance)
        .map(dependency -> ((Dependency.OnFact) dependency).fact().urnValue())
        .toList();
  }

  /** The classification's classified Field accepted as the birth date, written. */
  private static String classifiedBirthDate(Classification sample) {
    var s = sample.s();
    var c = s.begin();
    var field =
        fact(
            c,
            sample.dimension(),
            Vocabulary.CLASSIFIED_FIELD,
            JSON.textNode(sample.birthDate()),
            s.on(sample.kind()));
    return s.write(c).urn(field);
  }

  @Test
  void aClassificationsValuesRestOnItsKindAndTheFieldsTheyRead() {
    var sample = classification();
    var s = sample.s();
    var classified = classifiedBirthDate(sample);
    var c = s.begin();
    var rule =
        fact(
            c,
            sample.dimension(),
            Vocabulary.CLASSIFICATION_RULE,
            JSON.textNode(AGE_BANDS),
            s.on(sample.kind()));
    var hierarchy =
        fact(
            c,
            sample.dimension(),
            Vocabulary.HIERARCHY,
            levels("age band", AGE_BAND, "age", "date_diff('year', birth_date, current_date)"),
            s.on(sample.kind()),
            s.on(classified));
    var written = s.write(c);

    assertThat(s.revision(written.urn(rule)).value().asText()).isEqualTo(AGE_BANDS);
    // The classified Field and the Fields the level expressions read become premise dependencies,
    // so a column that disappears cascades to the classification.
    assertThat(factDependencies(s, classified))
        .containsExactlyInAnyOrder(sample.kind(), existenceOf(sample.birthDate()));
    assertThat(factDependencies(s, written.urn(hierarchy)))
        .containsExactlyInAnyOrder(sample.kind(), classified, existenceOf(sample.birthDate()));
  }

  @Test
  void aClassificationsLevelsWithoutExpressionsNeedNoClassifiedField() {
    var sample = classification();
    var s = sample.s();
    var c = s.begin();
    var hierarchy =
        fact(
            c,
            sample.dimension(),
            Vocabulary.HIERARCHY,
            levels("sales area", ""),
            s.on(sample.kind()));
    var written = s.write(c);

    assertThat(factDependencies(s, written.urn(hierarchy))).containsExactly(sample.kind());
  }

  @Test
  void aClassificationsLevelExpressionOutsideItsTableOrFormIsNotAccepted() {
    var cases =
        java.util.Map.of(
            "amount * 2", "has no Field for",
            "date_diff('year', birth_date,", "does not parse",
            "(SELECT 1)", "subquery",
            "prefecture", "only references a column");
    cases.forEach(
        (expression, reason) -> {
          var sample = classification();
          var s = sample.s();
          var classified = classifiedBirthDate(sample);
          var c = s.begin();
          fact(
              c,
              sample.dimension(),
              Vocabulary.HIERARCHY,
              levels("band", expression),
              s.on(sample.kind()),
              s.on(classified));
          assertRejected(s, c, reason);
        });
  }

  @Test
  void aClassificationsLevelExpressionWithoutItsClassifiedFieldIsNotAccepted() {
    var sample = classification();
    var c = sample.s().begin();
    fact(
        c,
        sample.dimension(),
        Vocabulary.HIERARCHY,
        levels("age band", AGE_BAND),
        sample.s().on(sample.kind()));
    assertRejected(sample.s(), c, "exactly one classified Field");
  }

  @Test
  void aClassificationsValuesOutsideTheirFormsAreNotAccepted() {
    var sample = classification();
    var c = sample.s().begin();
    fact(
        c,
        sample.dimension(),
        Vocabulary.CLASSIFICATION_RULE,
        JSON.textNode(" "),
        sample.s().on(sample.kind()));
    assertRejected(sample.s(), c, "wording");

    sample = classification();
    c = sample.s().begin();
    fact(c, sample.dimension(), Vocabulary.CLASSIFICATION_RULE, JSON.textNode(AGE_BANDS));
    assertRejected(sample.s(), c, "exactly one kind");

    // A classified Field names a Field, not another element.
    sample = classification();
    c = sample.s().begin();
    fact(
        c,
        sample.dimension(),
        Vocabulary.CLASSIFIED_FIELD,
        JSON.textNode(sample.dimension()),
        sample.s().on(sample.kind()));
    assertRejected(sample.s(), c, "is no Field");

    // A classification has no calendar rule.
    sample = classification();
    c = sample.s().begin();
    fact(
        c,
        sample.dimension(),
        Vocabulary.CALENDAR_RULE,
        JSON.textNode(CLOSING_ON_THE_20TH),
        sample.s().on(sample.kind()));
    assertRejected(sample.s(), c, "has no predicate calendar-rule");
  }

  @Test
  void aClassificationsMissingKnowledgeIsItsRuleBesidesThatOfEveryDimension() {
    var sample = classification();
    var s = sample.s();
    var confirm = s.begin();
    confirm.confirmByMachine(Ref.urn(sample.kind()));
    s.write(confirm);

    // The classified Field is written only when an input states it, so it is not reported.
    assertThat(missing(s, sample.dimension()))
        .containsExactly(Vocabulary.MEANING, Vocabulary.HIERARCHY, Vocabulary.CLASSIFICATION_RULE);
  }

  @Test
  void aGranularityRestsOnACalendarAndNamesItsLevelInWords() {
    var sample = sample();
    var s = sample.s();
    var c = s.begin();
    var fact =
        fact(
            c,
            sample.measure(),
            Vocabulary.DEFAULT_TIME_GRANULARITY,
            granularity(sample.calendar(), "fiscal month"),
            s.on(sample.kind()));
    var written = s.write(c);

    assertThat(Granularity.of(s.revision(written.urn(fact)).value()))
        .isEqualTo(new Granularity(sample.calendar(), "fiscal month"));
  }

  @Test
  void aGranularityThatDoesNotRestOnACalendarIsNotAccepted() {
    var sample = sample();
    var s = sample.s();
    var c = s.begin();
    fact(
        c,
        sample.measure(),
        Vocabulary.DEFAULT_TIME_GRANULARITY,
        granularity(sample.calendar(), "fiscal month"));
    assertRejected(s, c, "being a calendar");
  }

  @Test
  void aGranularityProposedWithoutAValueTakesItFromItsKeyWithADimensionOfTheSameCommit() {
    var s = scenario();
    var c = s.begin();
    var calendar = c.add(dimension("fiscal-calendar"));
    var existence = c.closedRangeDependency(c.address(calendar, Vocabulary.EXISTENCE), "exists");
    var kind =
        c.closedRangeDependency(
            c.address(calendar, Vocabulary.DIMENSION_KIND), "calendar", existence);
    var measure = c.measure("ec-sales");
    var granularity =
        c.add(
            new LogRecord.Fact(
                c.address(measure, Vocabulary.DEFAULT_TIME_GRANULARITY),
                JSON.nullNode(),
                Granularity.identityKey(calendar, "fiscal month")));
    c.accept(granularity, kind);
    var written = s.write(c);

    assertThat(Granularity.of(s.revision(written.urn(granularity)).value()))
        .isEqualTo(new Granularity(written.urn(calendar), "fiscal month"));
  }

  @Test
  void aCalendarsMissingKnowledgeIsThatOfItsKindOnly() {
    var sample = sample();
    var s = sample.s();
    // Until a kind is selected, a Dimension's closed core is that of every Dimension.
    assertThat(missing(s, sample.calendar()))
        .containsExactly(Vocabulary.MEANING, Vocabulary.HIERARCHY);

    var confirm = s.begin();
    confirm.confirmByMachine(Ref.urn(sample.kind()));
    s.write(confirm);

    assertThat(missing(s, sample.calendar()))
        .containsExactly(Vocabulary.MEANING, Vocabulary.HIERARCHY, Vocabulary.CALENDAR_RULE);
  }

  /** The closed-core predicates of an element reported with missing knowledge, in any view. */
  private static List<String> missing(Scenario s, String element) {
    return s.latest(
        snapshot ->
            snapshot
                .request(List.of(KnowledgeRequest.all(element)), Knowledge.View.WITH_UNCONFIRMED)
                .getFirst()
                .missing()
                .stream()
                .map(missing -> missing.predicate())
                .toList());
  }
}
