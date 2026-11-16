/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.ConstructionSql;
import com.scalar.db.analytics.labs.semantic.generator.Acceptances;
import com.scalar.db.analytics.labs.semantic.generator.DataGenerator;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Fact;
import com.scalar.db.analytics.labs.semantic.generator.Output;
import com.scalar.db.analytics.labs.semantic.generator.Proposals;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.DataFinding;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.RetirementReason;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A data check: a Generator that reads data values through aggregate queries only, counts and set
 * comparisons, and never reads data rows (see {@code docs/semantic-catalog/generators.md}, "Data
 * checks"). Nothing it reads is passed to a model.
 *
 * <p>What it reads for one target is recorded as one data observation of the target's Input
 * Resource, in the commit of the results that read it, and is not recorded again when it equals the
 * previous observation apart from when it was read. Data refutes a rule but does not establish one:
 * a counterexample found is adopted inline, while finding none is left to the policy evaluation
 * model unless a declaration supports the same item.
 */
abstract class DataCheck implements DataGenerator {
  static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private final String name;

  DataCheck(String name) {
    this.name = name;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String version() {
    return "1";
  }

  // SQL

  /** A table as construction-run SQL addresses it: registration, namespace, and table. */
  static String table(PhysicalCatalogReference.Table table) {
    return identifier(table.registration())
        + "."
        + identifier(table.namespace())
        + "."
        + identifier(table.table());
  }

  static String identifier(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  /** A column of an aliased table. */
  static String column(String alias, String column) {
    return alias + "." + identifier(column);
  }

  /** Whether any of the columns is null. */
  static String anyNull(Collection<String> columns) {
    return "(" + String.join(" OR ", columns.stream().map(c -> c + " IS NULL").toList()) + ")";
  }

  /** A count over the read rows, cast so every engine returns it as a number. */
  static String count(String expression) {
    return "CAST(coalesce(" + expression + ", 0) AS BIGINT)";
  }

  static ConstructionSql.Request request(String sql, List<ConstructionSql.Input> inputs) {
    return new ConstructionSql.Request(sql, inputs);
  }

  // Reads

  /** The first row's value of a column, as a count. */
  static long number(ConstructionSql.Outcome.Read read, String column) {
    var index = read.columns().indexOf(column);
    if (index < 0 || read.rows().isEmpty()) {
      throw new IllegalStateException("A data check read no " + column);
    }
    var value = read.rows().getFirst().get(index);
    return value.isNumber() ? value.asLong() : Long.parseLong(value.asText());
  }

  /** The completed reads of a query, or empty when a statement exceeded a limit. */
  static Optional<List<ConstructionSql.Outcome.Read>> completed(Read read) {
    var reads = new ArrayList<ConstructionSql.Outcome.Read>();
    for (var outcome : read.outcomes()) {
      switch (outcome) {
        case ConstructionSql.Outcome.Read completed -> reads.add(completed);
        case ConstructionSql.Outcome.LimitExceeded ignored -> {
          return Optional.empty();
        }
      }
    }
    return Optional.of(reads);
  }

  // Data observations

  /** A data observation as the run's records refer to it. */
  record Observed(Ref resource, Ref observation) {
    Dependency dependency() {
      return new Dependency.OnInput(resource, observation);
    }
  }

  /**
   * Records what a query read as a data observation, unless it equals the latest observation of the
   * same Input Resource apart from when it was read, which is referred to instead.
   */
  Observed observe(GeneratorContext context, Output output, Read read) {
    var content = stored(content(read));
    var dataCheck = read.query().dataCheck();
    var existing = context.dataObservationResource(dataCheck);
    if (existing.isPresent()) {
      var latest = context.latestObservation(existing.get());
      if (latest.isPresent()
          && context
              .observationContent(latest.get())
              .map(previous -> comparable(previous).equals(SAME_NUMBER, comparable(content)))
              .orElse(false)) {
        return new Observed(Ref.urn(existing.get()), Ref.urn(latest.get()));
      }
    }
    var resource =
        existing.isPresent()
            ? Ref.urn(existing.get())
            : output.add(LogRecord.InputResource.dataObservation(dataCheck));
    var observation = output.add(new LogRecord.InputObservation(resource, null, content));
    output.add(new LogRecord.ObservationEvent(observation, context.provenance()));
    return new Observed(resource, observation);
  }

  /** What an observation records: the check, its target, and each statement with its outcome. */
  private static JsonNode content(Read read) {
    var query = read.query();
    var content = JSON.objectNode();
    content.put("check", query.check());
    content.set("target", query.target());
    var schemas = content.putArray("physicalSchemas");
    query.physicalSchemas().forEach(schemas::add);
    var statements = content.putArray("statements");
    for (var index = 0; index < query.statements().size(); index++) {
      var request = query.statements().get(index);
      var statement = statements.addObject();
      statement.put("sql", request.sql());
      var inputs = statement.putArray("inputs");
      for (var input : request.inputs()) {
        inputs
            .addObject()
            .put("table", input.table().canonical())
            .put("reading", input.reading().name().toLowerCase(java.util.Locale.ROOT));
      }
      statement.set("outcome", outcome(read.outcomes().get(index)));
    }
    return content;
  }

  private static JsonNode outcome(ConstructionSql.Outcome outcome) {
    var node = JSON.objectNode();
    switch (outcome) {
      case ConstructionSql.Outcome.Read read -> {
        node.put("state", "read");
        var columns = node.putArray("columns");
        read.columns().forEach(columns::add);
        var rows = node.putArray("rows");
        for (var row : read.rows()) {
          var values = rows.addArray();
          row.forEach(values::add);
        }
        var ranges = node.putArray("ranges");
        for (var range : read.ranges()) {
          ranges
              .addObject()
              .put("table", range.table().canonical())
              .put(
                  "extent",
                  switch (range.extent()) {
                    case SAMPLE -> "sample";
                    case WHOLE_TABLE -> "whole-table";
                  })
              .put("rowsRead", range.rowsRead())
              .put("readAt", range.readAt().toString());
        }
        var sources = node.putArray("sources");
        for (var source : read.sources()) {
          sources
              .addObject()
              .put("registration", source.registration())
              .put("extension", source.extension())
              .put("extensionVersion", source.extensionVersion());
        }
      }
      case ConstructionSql.Outcome.LimitExceeded exceeded ->
          node.put("state", "limit-exceeded").put("reason", exceeded.reason());
    }
    return node;
  }

  /**
   * Compares numbers by value: a count written as a long is read back from the store as whichever
   * numeric node its size calls for.
   */
  private static final java.util.Comparator<JsonNode> SAME_NUMBER =
      (left, right) -> {
        if (left.isNumber() && right.isNumber()) {
          return left.decimalValue().compareTo(right.decimalValue());
        }
        return left.equals(right) ? 0 : 1;
      };

  /** An observation's content without when each table was read, which every read changes. */
  private static JsonNode comparable(JsonNode content) {
    var copy = content.deepCopy();
    for (var statement : copy.path("statements")) {
      for (var range : statement.path("outcome").path("ranges")) {
        if (range instanceof ObjectNode object) {
          object.remove("readAt");
        }
      }
    }
    return copy;
  }

  // Checks

  /** A {@code data-check} check of this Generator on an observation. */
  Check check(
      JsonNode items,
      CheckResult result,
      @Nullable DataFinding finding,
      Observed observed,
      ObjectNode details) {
    var withCheck = details.deepCopy().put("check", name);
    return new Check(
        ValidationMethod.DATA_CHECK,
        stored(items),
        result,
        result == CheckResult.UNDECIDABLE ? DataFinding.NO_COUNTEREXAMPLE_FOUND : finding,
        observed.observation(),
        stored(withCheck));
  }

  /**
   * JSON as the store gives it back, with each number in the node type its value calls for, so a
   * check or observation produced again from the same input equals the one written before.
   */
  static JsonNode stored(JsonNode node) {
    return LogRecordJson.readNode(LogRecordJson.writeNode(node));
  }

  /** An array of validation items. */
  static JsonNode items(Collection<JsonNode> items) {
    ArrayNode array = JSON.arrayNode();
    items.forEach(array::add);
    return array.size() == 1 ? array.get(0) : array;
  }

  /** What this Generator states in an acceptance it makes. */
  Acceptances.Ownership ownership(GeneratorContext context) {
    return Acceptances.dataCheck(context, name);
  }

  // Conclusions

  /**
   * A value a read shows, with the checks that support it: {@code data-check}, or {@code inference}
   * for what data cannot support.
   */
  record Proposal(JsonNode value, IdentityKey key, List<Check> checks) {}

  /**
   * What one target's read concludes about the Facts at one Fact Address of an element.
   *
   * @param proposal the value the read shows, if it shows one
   * @param contradicted the items of an existing Fact's value that a counterexample found refutes
   * @param undecided a check for the existing candidates when the read decides nothing, such as a
   *     query over a limit
   * @param premises the dependencies every acceptance states besides the observation
   * @param read the Fact Revisions and observations read besides the observation
   */
  record Conclusion(
      String element,
      String predicate,
      Optional<Proposal> proposal,
      Function<Fact, List<JsonNode>> contradicted,
      Optional<Function<Fact, Check>> undecided,
      List<Dependency> premises,
      List<String> read) {}

  /**
   * Records a conclusion: proposes or accepts again the value shown, refutes the candidates it
   * contradicts, proposes retiring the confirmed Facts it contradicts with the reason that the
   * confirmation was wrong, and marks the candidates undecidable when the read decides nothing.
   */
  void conclude(GeneratorContext context, Output output, Observed observed, Conclusion conclusion) {
    var element = conclusion.element();
    var predicate = conclusion.predicate();
    var sameUrn =
        conclusion
            .proposal()
            .flatMap(
                proposal -> Proposals.atAddress(context, element, predicate, proposal.key()).same())
            .map(Fact::urn);
    for (var fact : context.factsAt(element, predicate)) {
      if (sameUrn.isPresent() && sameUrn.get().equals(fact.urn())) {
        continue;
      }
      var contradicted = conclusion.contradicted().apply(fact);
      if (!contradicted.isEmpty() && fact.state() == FactState.CANDIDATE) {
        var refuting =
            check(
                items(contradicted),
                CheckResult.REFUTED,
                DataFinding.COUNTEREXAMPLE_FOUND,
                observed,
                JSON.objectNode());
        acceptAgain(context, output, observed, conclusion, fact, List.of(refuting));
      } else if (!contradicted.isEmpty() && fact.retirable()) {
        output.add(
            new LogRecord.ProposeRetire(
                Ref.urn(fact.urn()),
                RetirementReason.CONFIRMATION_WAS_WRONG,
                List.of(
                    check(
                        items(contradicted),
                        CheckResult.SUPPORTED,
                        DataFinding.COUNTEREXAMPLE_FOUND,
                        observed,
                        JSON.objectNode()
                            .put(
                                "reason", "The data has a counterexample to the confirmed value"))),
                inputsRead(observed, conclusion.read(), List.of(fact.revision())),
                context.provenance()));
      } else if (conclusion.proposal().isEmpty()
          && conclusion.undecided().isPresent()
          && fact.state() == FactState.CANDIDATE) {
        acceptAgain(
            context,
            output,
            observed,
            conclusion,
            fact,
            List.of(conclusion.undecided().get().apply(fact)));
      }
    }
    if (conclusion.proposal().isPresent()) {
      propose(context, output, observed, conclusion, conclusion.proposal().get());
    }
  }

  private void acceptAgain(
      GeneratorContext context,
      Output output,
      Observed observed,
      Conclusion conclusion,
      Fact fact,
      List<Check> checks) {
    var dependencies = new ArrayList<Dependency>();
    dependencies.add(observed.dependency());
    dependencies.addAll(conclusion.premises());
    var restated = Acceptances.restate(Optional.of(fact), ownership(context), dependencies, checks);
    output.add(
        new LogRecord.Accept(
            Ref.urn(fact.urn()),
            restated.dependencies(),
            restated.checks(),
            inputsRead(observed, conclusion.read(), List.of(fact.revision())),
            context.provenance()));
  }

  private void propose(
      GeneratorContext context,
      Output output,
      Observed observed,
      Conclusion conclusion,
      Proposal proposal) {
    var atAddress =
        Proposals.atAddress(context, conclusion.element(), conclusion.predicate(), proposal.key());
    var dependencies = new ArrayList<Dependency>();
    dependencies.add(observed.dependency());
    dependencies.addAll(conclusion.premises());
    dependencies.addAll(atAddress.dependencies());
    var restated =
        Acceptances.restate(atAddress.same(), ownership(context), dependencies, proposal.checks());
    var address =
        output.add(
            new LogRecord.FactAddress(
                Ref.urn(conclusion.element()), conclusion.predicate(), Scope.EMPTY, null));
    var fact = output.add(new LogRecord.Fact(address, proposal.value(), proposal.key()));
    output.add(
        new LogRecord.Accept(
            fact,
            restated.dependencies(),
            restated.checks(),
            inputsRead(observed, conclusion.read(), atAddress.revisions()),
            context.provenance()));
  }

  /**
   * The inputs read: the observation when it was recorded earlier, then the Fact Revisions and
   * observations read. A new observation is not written yet and has no URN to list, and is named by
   * the dependency on it instead.
   */
  static JsonNode inputsRead(Observed observed, List<String> read, List<String> more) {
    ArrayNode inputs = JSON.arrayNode();
    if (observed.observation() instanceof Ref.Urn urn) {
      inputs.add(urn.value());
    }
    read.forEach(inputs::add);
    more.forEach(inputs::add);
    return inputs;
  }
}
