/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.ConstructionSql;
import com.scalar.db.analytics.labs.semantic.ModelInputSettings;
import com.scalar.db.analytics.labs.semantic.generator.DataGenerator;
import com.scalar.db.analytics.labs.semantic.generator.DataObservations;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Fact;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Trigger;
import com.scalar.db.analytics.labs.semantic.generator.ModelGenerator;
import com.scalar.db.analytics.labs.semantic.generator.Output;
import com.scalar.db.analytics.labs.semantic.generator.Proposals;
import com.scalar.db.analytics.labs.semantic.llm.LanguageModelException;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.Document;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.CheckResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A meaning-inference Generator: it asks a language model for predicate values of confirmed
 * Datasets or Fields that declarations and data cannot decide (see {@code
 * docs/semantic-catalog/generators.md}, "Meaning inference").
 *
 * <p>The model reads each target table's metadata, the connector's source metadata as the connector
 * wrote it, the target elements' confirmed Facts and rejected values, the relevant sections of the
 * domain's documents, and the data reads the domain's settings allow. It names elements by their
 * Physical Catalog references, and only the targets the run asked about are read back from its
 * answer. A value whose quotation is found in the document it cites is {@code document}; any other
 * is {@code inference}, with the basis the model stated. Neither is adopted inline.
 *
 * <p>A run produces only for a Fact Address with no Fact selected and no candidate pending, and
 * after two rejections there it waits for a newer observation of what they depended on: one
 * candidate per Fact Address per run, and at most one further candidate after a rejection.
 */
abstract class MeaningInference implements ModelGenerator {
  static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  /** The check name of the data observations that hold count statistics. */
  static final String COUNT_STATISTICS = "count-statistics";

  /** The check name of the data observations that hold value samples. */
  static final String VALUE_SAMPLES = "value-samples";

  /** How many distinct values of a column a value sample holds at most. */
  static final int SAMPLED_VALUES = 5;

  /** How many characters of a sampled value are passed at most. */
  static final int SAMPLED_VALUE_LENGTH = 100;

  /**
   * How many targets one model call asks about at most, an initial bound: one call per table, split
   * further for a wide table, keeps each call within what a model answers in time.
   */
  static final int TARGETS_PER_CALL = 25;

  private final String name;
  private final ElementKind kind;

  MeaningInference(String name, ElementKind kind) {
    this.name = name;
    this.kind = kind;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String version() {
    return "1";
  }

  @Override
  public List<Input> inputs() {
    return List.of(new Input.Confirmed(kind, Vocabulary.EXISTENCE));
  }

  /** The predicates this Generator proposes, in the order the model is asked for them. */
  abstract List<String> predicates();

  /**
   * A value the model gave for a predicate, in the form the predicate takes, or why it is not one.
   */
  abstract Valued value(String predicate, JsonNode given);

  /**
   * Whether a Fact Address is one that schema interpretation fills from a current declaration of
   * the target's column, which meaning inference leaves to it.
   */
  boolean declared(Located target, String predicate) {
    return false;
  }

  /** A predicate value read from the answer, or why the answer gives none. */
  sealed interface Valued {
    /**
     * @param key the identity key of a closed-range value; absent for a free description
     */
    record Value(JsonNode value, @Nullable IdentityKey key) implements Valued {}

    record Invalid(String why) implements Valued {}
  }

  static Valued text(JsonNode given) {
    return given.isTextual() && !given.asText().isBlank()
        ? new Valued.Value(JSON.textNode(given.asText().strip()), null)
        : new Valued.Invalid("it is not a non-empty text");
  }

  static Valued choice(JsonNode given, List<String> choices) {
    return given.isTextual() && choices.contains(given.asText())
        ? new Valued.Value(JSON.textNode(given.asText()), IdentityKey.choice(given.asText()))
        : new Valued.Invalid("it is not one of " + String.join(", ", choices));
  }

  // Targets

  /**
   * A target element with the table its existence rests on.
   *
   * @param column the column a Field refers to; absent for a Dataset
   */
  record Located(
      String element,
      String reference,
      GeneratorContext.Table table,
      PhysicalCatalogReference.Table tableReference,
      @Nullable String column,
      Fact existence) {}

  private Optional<Located> locate(GeneratorContext context, String element) {
    return switch (kind) {
      case DATASET ->
          context
              .datasetTable(element)
              .map(
                  found ->
                      new Located(
                          element,
                          found.reference().canonical(),
                          found.table(),
                          found.reference(),
                          null,
                          found.existence()));
      case FIELD ->
          context
              .column(element)
              .map(
                  found ->
                      new Located(
                          element,
                          new PhysicalCatalogReference.Column(found.reference(), found.name())
                              .canonical(),
                          found.table(),
                          found.reference(),
                          found.name(),
                          found.existence()));
      default -> Optional.empty();
    };
  }

  /** The predicates a confirmed target still calls for a candidate at. */
  private List<String> open(GeneratorContext context, Located target) {
    if (target.existence().state() != FactState.CONFIRMED) {
      return List.of();
    }
    return predicates().stream()
        .filter(predicate -> !declared(target, predicate))
        .filter(predicate -> open(context, context.factsAt(target.element(), predicate)))
        .toList();
  }

  /**
   * Whether a Fact Address takes a new candidate: no Fact is selected there, none is pending, and
   * fewer than two rejected candidates rest on inputs that are still current.
   */
  private static boolean open(GeneratorContext context, List<Fact> facts) {
    var rejectedOnCurrentInput = 0;
    for (var fact : facts) {
      switch (fact.state()) {
        case CONFIRMED, CANDIDATE -> {
          return false;
        }
        case REJECTED -> {
          if (current(context, fact)) {
            rejectedOnCurrentInput++;
          }
        }
        case RETIRED -> {}
      }
    }
    return rejectedOnCurrentInput < 2;
  }

  /** Whether every Input Resource a Fact depends on has no newer observation than it names. */
  private static boolean current(GeneratorContext context, Fact fact) {
    for (var dependency : fact.dependencies()) {
      if (dependency instanceof Dependency.OnInput input) {
        var latest = context.latestObservation(input.inputResource().urnValue());
        if (latest.isPresent() && !latest.get().equals(input.observation().urnValue())) {
          return false;
        }
      }
    }
    return true;
  }

  // Planning

  @Override
  public Plan plan(GeneratorContext context, ModelInputSettings settings) {
    var missing = new LinkedHashSet<String>();
    var elements = new LinkedHashSet<String>();
    for (var trigger : context.triggers()) {
      if (trigger instanceof Trigger.Confirmed confirmed
          && confirmed.kind() == kind
          && confirmed.predicate().equals(Vocabulary.EXISTENCE)) {
        elements.add(confirmed.element());
      }
    }
    var byTable = new LinkedHashMap<String, List<Located>>();
    var predicatesByElement = new LinkedHashMap<String, List<String>>();
    for (var element : elements) {
      var located = locate(context, element);
      if (located.isEmpty()) {
        missing.add("the read table of " + element);
        continue;
      }
      var predicates = open(context, located.get());
      if (predicates.isEmpty()) {
        continue;
      }
      predicatesByElement.put(element, predicates);
      byTable
          .computeIfAbsent(located.get().table().inputResource(), ignored -> new ArrayList<>())
          .add(located.get());
    }
    var asks = new ArrayList<Ask>();
    var queries = new ArrayList<DataGenerator.Query>();
    var sections =
        byTable.isEmpty()
            ? List.<DocumentSections.Section>of()
            : DocumentSections.sections(context.documents());
    for (var located : byTable.values()) {
      var table = located.getFirst();
      var observed = (GeneratorContext.Observed.Read) table.table().observed();
      var columns = columnNames(observed.tableMetadata());
      var reference = table.tableReference().canonical();
      if (settings.countStatistics()) {
        queries.add(countStatistics(table, columns));
      } else {
        missing.add(
            "the count statistics of " + reference + ", which the domain's settings withhold");
      }
      if (settings.valueSamples()) {
        if (!columns.isEmpty()) {
          queries.add(valueSamples(table, columns));
        }
      } else {
        missing.add("the value samples of " + reference + ", which the domain's settings withhold");
      }
      var tableNode = JSON.objectNode();
      tableNode.put("ref", reference);
      tableNode.set("tableMetadata", observed.tableMetadata());
      tableNode.set(
          "sourceMetadata",
          context
              .observationContent(table.table().observation())
              .map(content -> content.path("sourceMetadata"))
              .orElse(JSON.nullNode()));
      var names = new ArrayList<String>();
      names.add(table.tableReference().table());
      names.addAll(columns);
      var chosen = DocumentSections.choose(sections, names, DocumentSections.CHARACTERS);
      missing.addAll(chosen.missing());
      for (var from = 0; from < located.size(); from += TARGETS_PER_CALL) {
        var share = located.subList(from, Math.min(from + TARGETS_PER_CALL, located.size()));
        asks.add(ask(context, tableNode, chosen.sections(), share, predicatesByElement));
      }
    }
    return new Plan(asks, queries, List.copyOf(missing));
  }

  /**
   * One model call over targets of one table: the table once, the document sections chosen for it,
   * then each target with the predicates asked for, its confirmed Facts, and the values already
   * rejected at those predicates. Every target's candidates rest on the documents the call reads.
   */
  private static Ask ask(
      GeneratorContext context,
      ObjectNode tableNode,
      List<DocumentSections.Section> sections,
      List<Located> share,
      Map<String, List<String>> predicatesByElement) {
    var read = new LinkedHashSet<String>();
    var input = JSON.objectNode();
    var targetNodes = input.putArray("targets");
    input.putArray("tables").add(tableNode.deepCopy());
    var documentNodes = input.putArray("documents");
    var documentPremises = new LinkedHashSet<Dependency>();
    for (var section : sections) {
      var document = section.document();
      documentNodes
          .addObject()
          .put("document", document.inputResource())
          .put("name", document.document().name())
          .put("heading", section.heading())
          .put("text", section.text());
      read.add(document.observation());
      documentPremises.add(
          new Dependency.OnInput(
              Ref.urn(document.inputResource()), Ref.urn(document.observation())));
    }
    var targets = new ArrayList<Target>();
    for (var target : share) {
      var predicates = predicatesByElement.getOrDefault(target.element(), List.of());
      var premises = new ArrayList<Dependency>();
      premises.add(Proposals.on(target.existence()));
      premises.add(
          new Dependency.OnInput(
              Ref.urn(target.table().inputResource()), Ref.urn(target.table().observation())));
      premises.addAll(documentPremises);
      read.add(target.table().observation());
      read.add(target.existence().revision());
      var targetNode = targetNodes.addObject();
      targetNode.put("ref", target.reference());
      targetNode.put("table", target.tableReference().canonical());
      var asked = targetNode.putArray("predicates");
      predicates.forEach(asked::add);
      var confirmed = targetNode.putArray("confirmed");
      for (var fact : passedFacts(context, target.element())) {
        premises.add(Proposals.on(fact));
        read.add(fact.revision());
        confirmed.addObject().put("predicate", fact.predicate()).set("value", fact.value());
      }
      var rejected = targetNode.putArray("rejected");
      for (var predicate : predicates) {
        for (var fact : context.factsAt(target.element(), predicate)) {
          if (fact.state() == FactState.REJECTED) {
            read.add(fact.revision());
            rejected.addObject().put("predicate", predicate).set("value", fact.value());
          }
        }
      }
      targets.add(new Target(target.element(), target.reference(), predicates, premises));
    }
    return new Ask(input, targets, List.copyOf(read));
  }

  /**
   * The confirmed Facts of a target the model reads: every closed-core predicate besides existence
   * and the Physical Catalog reference, which the reference it is named by already states. Each is
   * an input read and a dependency of the target's candidates.
   */
  private static List<Fact> passedFacts(GeneratorContext context, String element) {
    var kind = context.elementKind(element);
    if (kind.isEmpty()) {
      return List.of();
    }
    var predicates = new ArrayList<>(Vocabulary.closedCore(kind.get()));
    predicates.remove(Vocabulary.EXISTENCE);
    predicates.remove(Vocabulary.PHYSICAL_CATALOG_REFERENCE);
    if (kind.get() == ElementKind.DATASET) {
      for (var predicate : context.predicates(element)) {
        if (predicate.startsWith(Vocabulary.UNIQUENESS_PREFIX)) {
          predicates.add(predicate);
        }
      }
    }
    var facts = new ArrayList<Fact>();
    for (var predicate : predicates) {
      context.factsAt(element, predicate).stream()
          .filter(fact -> fact.state() == FactState.CONFIRMED)
          .forEach(facts::add);
    }
    return facts;
  }

  private static List<String> columnNames(JsonNode tableMetadata) {
    var names = new ArrayList<String>();
    for (var column : tableMetadata.path("columns")) {
      var name = column.path("name");
      if (name.isTextual()) {
        names.add(name.asText());
      }
    }
    return names;
  }

  // Data reads

  private static String identifier(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  private static String sql(PhysicalCatalogReference.Table table) {
    return identifier(table.registration())
        + "."
        + identifier(table.namespace())
        + "."
        + identifier(table.table());
  }

  private static ObjectNode target(PhysicalCatalogReference.Table table, List<String> columns) {
    var target = JSON.objectNode().put("table", table.canonical());
    var names = target.putArray("columns");
    columns.forEach(names::add);
    return target;
  }

  /** Row count, and the non-null and distinct counts of each column, over a bounded sample. */
  private static DataGenerator.Query countStatistics(Located table, List<String> columns) {
    var select = new ArrayList<String>();
    select.add("CAST(count(*) AS BIGINT) AS \"rows\"");
    for (var column : columns) {
      select.add(
          "CAST(count("
              + identifier(column)
              + ") AS BIGINT) AS "
              + identifier("non-null:" + column));
      select.add(
          "CAST(count(DISTINCT "
              + identifier(column)
              + ") AS BIGINT) AS "
              + identifier("distinct:" + column));
    }
    var reference = table.tableReference();
    return new DataGenerator.Query(
        COUNT_STATISTICS,
        target(reference, columns),
        reference.canonical(),
        List.of(table.table().observation()),
        List.of(
            new ConstructionSql.Request(
                "SELECT " + String.join(", ", select) + " FROM " + sql(reference),
                List.of(new ConstructionSql.Input(reference, ConstructionSql.Reading.SAMPLED)))));
  }

  /**
   * Some distinct values of each column, as text cut to a bounded length, over a bounded sample.
   */
  private static DataGenerator.Query valueSamples(Located table, List<String> columns) {
    var reference = table.tableReference();
    var statements = new ArrayList<ConstructionSql.Request>();
    for (var column : columns) {
      statements.add(
          new ConstructionSql.Request(
              "SELECT DISTINCT left(CAST("
                  + identifier(column)
                  + " AS VARCHAR), "
                  + SAMPLED_VALUE_LENGTH
                  + ") AS \"value\" FROM "
                  + sql(reference)
                  + " WHERE "
                  + identifier(column)
                  + " IS NOT NULL ORDER BY 1 LIMIT "
                  + SAMPLED_VALUES,
              List.of(new ConstructionSql.Input(reference, ConstructionSql.Reading.SAMPLED))));
    }
    return new DataGenerator.Query(
        VALUE_SAMPLES,
        target(reference, columns),
        reference.canonical(),
        List.of(table.table().observation()),
        statements);
  }

  @Override
  public String input(Ask ask, List<DataGenerator.Read> reads) {
    var input = ask.input().deepCopy();
    var tables = new LinkedHashMap<String, ObjectNode>();
    for (var table : input.path("tables")) {
      tables.put(table.path("ref").asText(), (ObjectNode) table);
    }
    for (var read : reads) {
      var table = tables.get(read.query().target().path("table").asText());
      if (table == null) {
        continue;
      }
      switch (read.query().check()) {
        case COUNT_STATISTICS ->
            statistics(read).ifPresent(node -> table.set("countStatistics", node));
        case VALUE_SAMPLES -> samples(read).ifPresent(node -> table.set("valueSamples", node));
        default -> {}
      }
    }
    return input.toPrettyString();
  }

  /** The count statistics a read gives, as the model reads them. */
  private static Optional<JsonNode> statistics(DataGenerator.Read read) {
    if (!(read.outcomes().getFirst() instanceof ConstructionSql.Outcome.Read outcome)
        || outcome.rows().isEmpty()) {
      return Optional.empty();
    }
    var node = JSON.objectNode();
    range(node, outcome);
    var rows = number(outcome, "rows");
    node.put("rows", rows);
    var columns = node.putObject("columns");
    for (var column : read.query().target().path("columns")) {
      var name = column.asText();
      var nonNull = number(outcome, "non-null:" + name);
      columns
          .putObject(name)
          .put("nullRatio", rows == 0 ? 0.0 : (double) (rows - nonNull) / rows)
          .put("distinct", number(outcome, "distinct:" + name));
    }
    return Optional.of(node);
  }

  /** The value samples a read gives, as the model reads them. */
  private static Optional<JsonNode> samples(DataGenerator.Read read) {
    var node = JSON.objectNode();
    var columns = read.query().target().path("columns");
    var values = node.putObject("columns");
    for (var index = 0; index < read.outcomes().size(); index++) {
      if (!(read.outcomes().get(index) instanceof ConstructionSql.Outcome.Read outcome)) {
        continue;
      }
      if (index == 0) {
        range(node, outcome);
      }
      var array = values.putArray(columns.path(index).asText());
      for (var row : outcome.rows()) {
        array.add(row.getFirst());
      }
    }
    return values.isEmpty() ? Optional.empty() : Optional.of(node);
  }

  private static void range(ObjectNode node, ConstructionSql.Outcome.Read outcome) {
    if (!outcome.ranges().isEmpty()) {
      var range = outcome.ranges().getFirst();
      node.put(
          "extent",
          switch (range.extent()) {
            case SAMPLE -> "sample";
            case WHOLE_TABLE -> "whole-table";
          });
      node.put("rowsRead", range.rowsRead());
    }
  }

  private static long number(ConstructionSql.Outcome.Read read, String column) {
    var index = read.columns().indexOf(column);
    if (index < 0) {
      throw new IllegalStateException("Count statistics read no " + column);
    }
    var value = read.rows().getFirst().get(index);
    return value.isNumber() ? value.asLong() : Long.parseLong(value.asText());
  }

  // Recording

  @Override
  public void record(
      GeneratorContext context,
      Plan plan,
      List<DataGenerator.Read> reads,
      List<Answered> answers,
      Output output) {
    var observed = new LinkedHashMap<String, List<DataObservations.Observed>>();
    var statistics = new LinkedHashMap<String, DataObservations.Observed>();
    for (var read : reads) {
      var table = read.query().target().path("table").asText();
      var observation = DataObservations.observe(context, output, read);
      observed.computeIfAbsent(table, ignored -> new ArrayList<>()).add(observation);
      if (read.query().check().equals(COUNT_STATISTICS)) {
        statistics.put(table, observation);
      }
      for (var outcome : read.outcomes()) {
        if (outcome instanceof ConstructionSql.Outcome.LimitExceeded exceeded) {
          output.missing(
              "the "
                  + read.query().check()
                  + " of "
                  + table
                  + ", over a limit: "
                  + exceeded.reason());
          break;
        }
      }
    }
    var cited = new CitedDocuments(context);
    for (var answered : answers) {
      var given = answers(answered.answer());
      var table = answered.ask().input().path("tables").path(0).path("ref").asText();
      for (var target : answered.ask().targets()) {
        record(
            context,
            output,
            answered.ask(),
            target,
            given.get(target.reference()),
            cited,
            observed.getOrDefault(table, List.of()),
            statistics.get(table));
      }
    }
  }

  /**
   * Proposes what the model gave for one target, at each Fact Address that still calls for a
   * candidate: the target is still a confirmed element whose table was read, and no Fact was
   * selected or proposed there while the model answered.
   */
  private void record(
      GeneratorContext context,
      Output output,
      Ask ask,
      Target target,
      @Nullable Map<String, Given> values,
      CitedDocuments cited,
      List<DataObservations.Observed> observations,
      DataObservations.@Nullable Observed statistics) {
    var located = locate(context, target.element());
    if (located.isEmpty()) {
      return;
    }
    var stillOpen = Set.copyOf(open(context, located.get()));
    for (var predicate : target.predicates()) {
      if (!stillOpen.contains(predicate)) {
        continue;
      }
      var given = values == null ? null : values.get(predicate);
      if (given == null) {
        output.missing(predicate + " of " + target.reference() + ", which the model did not infer");
        continue;
      }
      switch (value(predicate, given.value())) {
        case Valued.Invalid invalid ->
            output.missing(
                predicate
                    + " of "
                    + target.reference()
                    + ", whose inferred value was dropped because "
                    + invalid.why());
        case Valued.Value value ->
            propose(
                context,
                output,
                ask,
                target,
                predicate,
                value,
                given,
                cited.verified(ask, given.citation()),
                observations,
                statistics);
      }
    }
  }

  /**
   * Proposes one candidate. It rests on what the model read as planned, so a physical schema
   * observed while the model answered leaves it depending on the older observation, which shows the
   * deciding party what it was inferred from. A value whose citation is found in the cited document
   * gets a {@code document} check quoting it; any other value gets an {@code inference} check with
   * the basis the model stated.
   */
  private static void propose(
      GeneratorContext context,
      Output output,
      Ask ask,
      Target target,
      String predicate,
      Valued.Value value,
      Given given,
      @Nullable String quoted,
      List<DataObservations.Observed> observations,
      DataObservations.@Nullable Observed statistics) {
    var dependencies = new ArrayList<>(target.premises());
    for (var observation : observations) {
      var dependency = observation.dependency();
      if (!dependencies.contains(dependency)) {
        dependencies.add(dependency);
      }
    }
    var check =
        quoted == null
            ? new Check(
                ValidationMethod.INFERENCE,
                JSON.textNode(Vocabulary.VALUE_ITEM),
                CheckResult.SUPPORTED,
                null,
                statistics == null ? null : statistics.observation(),
                DataObservations.stored(JSON.objectNode().put("basis", given.basis())))
            : new Check(
                ValidationMethod.DOCUMENT,
                JSON.textNode(Vocabulary.VALUE_ITEM),
                CheckResult.SUPPORTED,
                null,
                Ref.urn(quoted),
                DataObservations.stored(
                    JSON.objectNode()
                        .put("quotation", Objects.requireNonNull(given.citation()).quotation())
                        .put("basis", given.basis())));
    var inputsRead = JSON.arrayNode();
    ask.read().forEach(inputsRead::add);
    for (var observation : observations) {
      if (observation.observation() instanceof Ref.Urn urn && !ask.read().contains(urn.value())) {
        inputsRead.add(urn.value());
      }
    }
    var address =
        output.add(
            new LogRecord.FactAddress(Ref.urn(target.element()), predicate, Scope.EMPTY, null));
    var fact =
        output.add(
            new LogRecord.Fact(address, DataObservations.stored(value.value()), value.key()));
    output.add(
        new LogRecord.Accept(fact, dependencies, List.of(check), inputsRead, context.provenance()));
  }

  /**
   * The documents a run's answers cite, each read once in the recording transaction and kept in the
   * form a quotation is checked against.
   */
  private static final class CitedDocuments {
    private final GeneratorContext context;
    private final Map<String, String> texts = new HashMap<>();

    CitedDocuments(GeneratorContext context) {
      this.context = context;
    }

    /**
     * The Input Observation a citation quotes, when the call read the cited document and the
     * quotation is in that observation's text; null otherwise, so the value is only an inference.
     */
    @Nullable String verified(Ask ask, @Nullable Citation citation) {
      if (citation == null) {
        return null;
      }
      var observation = passedObservation(ask, citation.document());
      if (observation == null) {
        return null;
      }
      var text =
          texts.computeIfAbsent(
              observation,
              urn ->
                  Quotations.collapsed(
                      context
                          .observationContent(urn)
                          .map(content -> LogRecordJson.fromStored(content, Document.class).text())
                          .orElse("")));
      return Quotations.found(citation.quotation(), text) ? observation : null;
    }

    /**
     * The observation of a document the call read: a document among the call's input whose
     * observation the targets' premises name.
     */
    private static @Nullable String passedObservation(Ask ask, String document) {
      var passed = false;
      for (var section : ask.input().path("documents")) {
        if (section.path("document").asText().equals(document)) {
          passed = true;
          break;
        }
      }
      if (!passed) {
        return null;
      }
      for (var target : ask.targets()) {
        for (var premise : target.premises()) {
          if (premise instanceof Dependency.OnInput input
              && input.inputResource().urnValue().equals(document)) {
            return input.observation().urnValue();
          }
        }
      }
      return null;
    }
  }

  /** A predicate value the model gave, with the basis it stated and the document it cited. */
  private record Given(JsonNode value, String basis, @Nullable Citation citation) {}

  /** A document a model cites for a value, by its Input Resource, with the text it quotes. */
  private record Citation(String document, String quotation) {}

  /**
   * Reads the answer: {@code {"targets": [{"ref": ..., "values": {predicate: {"value": ...,
   * "basis": ..., "citation": {"document": ..., "quotation": ...}}}}]}}, the citation optional. An
   * answer not in this form fails the run, so a model that misreads the instructions is noticed.
   */
  private Map<String, Map<String, Given>> answers(JsonNode answer) {
    var targets = answer.path("targets");
    if (!targets.isArray()) {
      throw unreadable("it has no targets list");
    }
    var answers = new LinkedHashMap<String, Map<String, Given>>();
    for (var target : targets) {
      var reference = target.path("ref");
      var values = target.path("values");
      if (!reference.isTextual() || !values.isObject()) {
        throw unreadable("a target has no ref or no values object");
      }
      var given = new LinkedHashMap<String, Given>();
      for (var entry : values.properties()) {
        var item = entry.getValue();
        if (item.isNull()) {
          continue;
        }
        var basis = item.path("basis");
        if (!item.isObject()
            || !item.has("value")
            || !basis.isTextual()
            || basis.asText().isBlank()) {
          throw unreadable("the value of " + entry.getKey() + " has no value or no basis");
        }
        if (!item.get("value").isNull()) {
          given.put(
              entry.getKey(),
              new Given(item.get("value"), basis.asText(), citation(entry.getKey(), item)));
        }
      }
      answers.merge(
          reference.asText(),
          given,
          (left, right) -> {
            var merged = new LinkedHashMap<>(left);
            merged.putAll(right);
            return merged;
          });
    }
    return answers;
  }

  /** The citation of a value, if the model gave one; an object without both parts is unreadable. */
  private @Nullable Citation citation(String predicate, JsonNode item) {
    var citation = item.path("citation");
    if (citation.isMissingNode() || citation.isNull()) {
      return null;
    }
    var document = citation.path("document");
    var quotation = citation.path("quotation");
    if (!document.isTextual() || !quotation.isTextual()) {
      throw unreadable("the citation of " + predicate + " has no document or no quotation");
    }
    return new Citation(document.asText(), quotation.asText());
  }

  private LanguageModelException unreadable(String why) {
    return new LanguageModelException(
        "The answer to " + name + " is not in the form asked for: " + why);
  }
}
