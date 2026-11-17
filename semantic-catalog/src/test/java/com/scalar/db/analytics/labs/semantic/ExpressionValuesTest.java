/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.model.CommitRejectedException;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Expression values at acceptance: a Measure's aggregation rule and filter conditions are parsed
 * with the Query Engine's parser over the base Dataset's table, and the Fields they reference
 * become premise dependencies, so a column that disappears cascades to the definitions that use it
 * (see {@code docs/semantic-catalog/record-model.md}, "Elements and predicates").
 */
class ExpressionValuesTest extends StoreScenarioTest {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  /** An orders Dataset with three Fields and a Measure based on it, written. */
  private record Sample(
      Scenario s,
      String measure,
      Dependency.OnFact baseDataset,
      String amountExistence,
      String statusExistence) {}

  private Sample sample() {
    var s = scenario();
    var c = s.begin();
    var orders = c.dataset("shop.public.orders");
    var amount = c.field(orders, "shop.public.orders.amount");
    var status = c.field(orders, "shop.public.orders.status");
    c.field(orders, "shop.public.orders.ordered_at");
    var measure = c.measure("ec-sales");
    var written = s.write(c);

    c = s.begin();
    var base =
        c.closedRangeCandidate(
            c.address(written.ref(measure), Vocabulary.BASE_DATASET), written.urn(orders));
    var baseWritten = s.write(c);
    var baseFact = baseWritten.urn(base);
    return new Sample(
        s,
        written.urn(measure),
        s.on(baseFact),
        existenceOf(written.urn(amount)),
        existenceOf(written.urn(status)));
  }

  private static String existenceOf(String element) {
    return Urns.fact(Urns.factAddress(element, Vocabulary.EXISTENCE), 1);
  }

  private static Ref rule(Scenario.Commit c, Sample sample, String function, String expression) {
    return c.aggregationRule(
        c.address(Ref.urn(sample.measure()), Vocabulary.AGGREGATION_RULE),
        function,
        expression,
        sample.baseDataset());
  }

  private static List<String> factDependencies(Scenario s, String fact) {
    return s.revision(fact).dependencies().stream()
        .filter(Dependency.OnFact.class::isInstance)
        .map(dependency -> ((Dependency.OnFact) dependency).fact().urnValue())
        .toList();
  }

  @Test
  void aSessionValueIsAColumnOnlyWhenTheTableHasOneOfThatName() {
    // The orders have no column named current_date, so the Query Engine reads the session's date.
    var sample = sample();
    var s = sample.s();
    var c = s.begin();
    var filter =
        c.add(
            new LogRecord.Fact(
                c.address(Ref.urn(sample.measure()), Vocabulary.FILTER),
                JSON.textNode("ordered_at <= current_date AND amount > 0"),
                null));
    c.accept(filter, sample.baseDataset());
    var written = s.write(c);
    assertThat(factDependencies(s, written.urn(filter)))
        .containsExactlyInAnyOrder(
            sample.baseDataset().fact().urnValue(),
            existenceOf(
                Urns.element(
                    s.domain, Vocabulary.ElementKind.FIELD, "shop.public.orders.ordered_at")),
            sample.amountExistence());

    // A table with a column of that name binds it as the column, which becomes a premise.
    var other = scenario();
    var d = other.begin();
    var ledger = d.dataset("shop.public.ledger");
    var day = d.field(ledger, "shop.public.ledger.current_date");
    var measure = d.measure("ledger-days");
    var setUp = other.write(d);
    d = other.begin();
    var base =
        d.closedRangeCandidate(
            d.address(setUp.ref(measure), Vocabulary.BASE_DATASET), setUp.urn(ledger));
    var baseFact = other.write(d).urn(base);
    d = other.begin();
    var rule =
        d.aggregationRule(
            d.address(Ref.urn(setUp.urn(measure)), Vocabulary.AGGREGATION_RULE),
            "count-distinct",
            "current_date",
            other.on(baseFact));
    var ruleWritten = other.write(d);
    assertThat(factDependencies(other, ruleWritten.urn(rule)))
        .containsExactlyInAnyOrder(baseFact, existenceOf(setUp.urn(day)));
  }

  @Test
  void theFieldsAnExpressionReferencesBecomePremiseDependencies() {
    var sample = sample();
    var s = sample.s();
    var c = s.begin();
    var rule = rule(c, sample, "sum", "amount * 1.1");
    var filter =
        c.add(
            new LogRecord.Fact(
                c.address(Ref.urn(sample.measure()), Vocabulary.FILTER),
                JSON.textNode("status NOT IN ('cancelled', 'returned') AND amount > 0"),
                null));
    c.accept(filter, sample.baseDataset());
    var written = s.write(c);

    assertThat(factDependencies(s, written.urn(rule)))
        .containsExactlyInAnyOrder(
            sample.baseDataset().fact().urnValue(), sample.amountExistence());
    assertThat(factDependencies(s, written.urn(filter)))
        .containsExactlyInAnyOrder(
            sample.baseDataset().fact().urnValue(),
            sample.statusExistence(),
            sample.amountExistence());

    // The same acceptance again, without the dependencies acceptance added, records nothing.
    var again = s.begin();
    again.accept(Ref.urn(written.urn(rule)), sample.baseDataset());
    var before = s.lastCommit();
    s.write(again);
    assertThat(s.lastCommit()).isEqualTo(before);
  }

  @Test
  void anExpressionValueOverAFieldThatIsRetiredIsRejectedByCascade() {
    var sample = sample();
    var s = sample.s();
    var c = s.begin();
    var rule = rule(c, sample, "sum", "amount");
    var written = s.judge(c);

    var retire = s.begin();
    retire.confirmByMachine(Ref.urn(sample.amountExistence()));
    s.write(retire);
    var gone = s.begin();
    gone.retireByMachine(Ref.urn(sample.amountExistence()));
    s.judge(gone);

    assertThat(s.state(written.urn(rule))).contains(FactState.REJECTED);
  }

  @Test
  void anExpressionThatDoesNotParseIsNotAccepted() {
    var sample = sample();
    var c = sample.s().begin();
    rule(c, sample, "sum", "amount +");
    assertRejected(sample.s(), c, "does not parse");
  }

  @Test
  void anExpressionNamingAColumnWithoutAFieldIsNotAccepted() {
    var sample = sample();
    var c = sample.s().begin();
    rule(c, sample, "sum", "gross_amount");
    assertRejected(sample.s(), c, "gross_amount");
  }

  @Test
  void textThatReadsAnotherTableOrAddsAClauseIsNotOneExpression() {
    var sample = sample();
    for (var expression :
        List.of(
            "amount FROM shop.public.customers",
            "(SELECT max(amount) FROM shop.public.orders)",
            "amount) FROM expression_values_table LIMIT 1 --",
            "orders.amount",
            "*")) {
      var c = sample.s().begin();
      rule(c, sample, "sum", expression);
      var before = sample.s().lastCommit();
      assertThatThrownBy(() -> sample.s().write(c))
          .as(expression)
          .isInstanceOf(CommitRejectedException.class);
      assertThat(sample.s().lastCommit()).isEqualTo(before);
    }
  }

  @Test
  void anExpressionValueRestsOnExactlyOneBaseDatasetOfItsMeasure() {
    var sample = sample();
    var c = sample.s().begin();
    var fact =
        c.add(
            new LogRecord.Fact(
                c.address(Ref.urn(sample.measure()), Vocabulary.AGGREGATION_RULE),
                JSON.objectNode().put("function", "sum").put("expression", "amount"),
                null));
    c.accept(fact);
    assertRejected(sample.s(), c, "exactly one base Dataset");
  }

  @Test
  void countOverStarCountsRowsAndReferencesNoField() {
    var sample = sample();
    var s = sample.s();
    var c = s.begin();
    var rule = rule(c, sample, "count", "*");
    var written = s.write(c);

    assertThat(factDependencies(s, written.urn(rule)))
        .containsExactly(sample.baseDataset().fact().urnValue());
  }

  @Test
  void anAggregationRuleChoosesItsFunctionFromTheClosedList() {
    var sample = sample();
    var c = sample.s().begin();
    rule(c, sample, "median", "amount");
    assertRejected(sample.s(), c, "median");
  }
}
