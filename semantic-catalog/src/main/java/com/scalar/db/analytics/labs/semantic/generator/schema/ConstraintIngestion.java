/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.schema;

import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.existence;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.onFact;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.onObservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.generator.CatalogView.ObservationView;
import com.scalar.db.analytics.labs.semantic.generator.Generator;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult.Candidate;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.DependencyRef;
import com.scalar.db.analytics.labs.semantic.model.ElementDescriptor;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.Predicates;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The schema interpretation Generators that map declared constraints and types to candidates
 * (generators.md 3.1). Each rule is its own Generator; they share the reading of table metadata.
 */
public final class ConstraintIngestion implements Generator {
  /** The rules, each run as its own Generator. */
  public enum Rule {
    PRIMARY_KEY("schema.key-from-primary-key"),
    UNIQUENESS("schema.uniqueness-declaration"),
    FOREIGN_KEY_JOIN("schema.join-from-foreign-key"),
    FOREIGN_KEY_PARTICIPATION("schema.participation-from-foreign-key"),
    VALUE_KIND("schema.value-kind-from-source-type");

    private final String generator;

    Rule(String generator) {
      this.generator = generator;
    }
  }

  private final Rule rule;

  public ConstraintIngestion(Rule rule) {
    this.rule = rule;
  }

  public static List<Generator> all() {
    var generators = new ArrayList<Generator>();
    for (var rule : Rule.values()) {
      generators.add(new ConstraintIngestion(rule));
    }
    return generators;
  }

  @Override
  public String name() {
    return rule.generator;
  }

  @Override
  public String version() {
    return "1";
  }

  @Override
  public Set<String> inputs() {
    return rule == Rule.FOREIGN_KEY_PARTICIPATION
        ? Set.of(Generator.confirmed(ElementKind.JOIN, Predicates.EXISTS))
        : Set.of(PHYSICAL_SCHEMA);
  }

  @Override
  public GeneratorResult run(GeneratorContext context) {
    var catalog = context.catalog();
    var domain = catalog.domain();
    var candidates = new ArrayList<Candidate>();
    // Foreign keys reference other tables, so every table's latest observation is read again; the
    // same proposal with the same rationale is not recorded twice.
    var schemas =
        rule == Rule.FOREIGN_KEY_JOIN || rule == Rule.FOREIGN_KEY_PARTICIPATION
            ? GeneratorSupport.schemas(catalog)
            : GeneratorSupport.triggeringSchemas(context);
    for (var schema : schemas) {
      var metadata = schema.content();
      var resource = GeneratorSupport.resourceIdentifier(schema);
      var dataset = GeneratorSupport.datasetUrn(domain, resource);
      if (!GeneratorSupport.confirmed(catalog, dataset)) {
        continue;
      }
      var nullable = new HashMap<String, Boolean>();
      var types = new HashMap<String, String>();
      for (var column : metadata.path("columns")) {
        nullable.put(column.path("name").asText(), column.path("nullable").asBoolean(true));
        types.put(column.path("name").asText(), column.path("sourceType").asText(""));
      }
      Function<String, String> field = column -> Urns.field(dataset, column);
      switch (rule) {
        case PRIMARY_KEY -> primaryKey(domain, schema, dataset, field, candidates);
        case UNIQUENESS -> uniqueness(domain, schema, dataset, nullable, field, candidates);
        case FOREIGN_KEY_JOIN -> joins(context, schema, dataset, types, field, candidates);
        case FOREIGN_KEY_PARTICIPATION ->
            participation(context, schema, dataset, nullable, field, candidates);
        case VALUE_KIND -> valueKinds(domain, schema, types, field, candidates);
      }
    }
    return GeneratorResult.of(candidates);
  }

  private static Check.Method declared(JsonNode constraint) {
    return constraint.path("enforced").asBoolean(false)
        ? Check.Method.DECLARED_ENFORCED
        : Check.Method.DECLARED_UNENFORCED;
  }

  private static List<String> columns(JsonNode constraint) {
    var columns = new ArrayList<String>();
    constraint.path("columns").forEach(column -> columns.add(column.asText()));
    return columns;
  }

  private static List<DependencyRef> fieldDependencies(String domain, List<String> fields) {
    var dependencies = new ArrayList<DependencyRef>();
    for (var urn : fields) {
      dependencies.add(onFact(existence(domain, urn)));
    }
    return dependencies;
  }

  private static void primaryKey(
      String domain,
      ObservationView schema,
      String dataset,
      Function<String, String> field,
      List<Candidate> out) {
    var key = schema.content().path("primaryKey");
    if (!key.isObject()) {
      return;
    }
    var fields = columns(key).stream().map(field).sorted().toList();
    var rationale = onObservation(schema, "primary-key", key);
    var dependencies = new ArrayList<DependencyRef>(fieldDependencies(domain, fields));
    dependencies.add(onFact(existence(domain, dataset)));
    dependencies.add(rationale);
    out.add(
        new Candidate(
            dataset,
            null,
            Predicates.KEY,
            Scope.UNRESTRICTED,
            Json.object().set("fields", Json.tree(fields)),
            dependencies,
            List.of(GeneratorSupport.supported(declared(key), rationale)),
            List.of()));
  }

  private static void uniqueness(
      String domain,
      ObservationView schema,
      String dataset,
      Map<String, Boolean> nullable,
      Function<String, String> field,
      List<Candidate> out) {
    var declarations = new ArrayList<JsonNode>();
    var key = schema.content().path("primaryKey");
    if (key.isObject()) {
      declarations.add(key);
    }
    schema.content().path("uniqueConstraints").forEach(declarations::add);
    for (var declaration : declarations) {
      var columns = columns(declaration);
      // A unique constraint over columns that allow null admits duplicates among rows with null.
      if (columns.isEmpty()
          || columns.stream()
              .anyMatch(column -> nullable.getOrDefault(column, true) && declaration != key)) {
        continue;
      }
      var method =
          "index".equals(declaration.path("kind").asText())
              ? Check.Method.DECLARED_ENFORCED
              : declared(declaration);
      var rationale =
          onObservation(
              schema,
              "unique:" + String.join(",", columns.stream().sorted().toList()),
              declaration);
      var dependencies =
          new ArrayList<DependencyRef>(
              fieldDependencies(domain, columns.stream().map(field).toList()));
      dependencies.add(rationale);
      out.add(
          new Candidate(
              dataset,
              null,
              Predicates.uniqueness(columns),
              Scope.UNRESTRICTED,
              Json.object().put("unique", true),
              dependencies,
              List.of(GeneratorSupport.supported(method, rationale)),
              List.of()));
    }
  }

  private static void joins(
      GeneratorContext context,
      ObservationView schema,
      String dataset,
      Map<String, String> types,
      Function<String, String> field,
      List<Candidate> out) {
    var catalog = context.catalog();
    var domain = catalog.domain();
    for (var foreignKey : schema.content().path("foreignKeys")) {
      var referencedResource = foreignKey.path("references").path("resource").asText();
      var referenced = GeneratorSupport.datasetUrn(domain, referencedResource);
      if (!GeneratorSupport.confirmed(catalog, referenced)) {
        continue;
      }
      var from = columns(foreignKey);
      var to = new ArrayList<String>();
      foreignKey.path("references").path("columns").forEach(column -> to.add(column.asText()));
      if (from.size() != to.size() || from.isEmpty()) {
        continue;
      }
      var pairs = Json.array();
      var dependencies = new ArrayList<DependencyRef>();
      for (var i = 0; i < from.size(); i++) {
        var fromField = field.apply(from.get(i));
        var toField = Urns.field(referenced, to.get(i));
        pairs.add(Json.array().add(fromField).add(toField));
        dependencies.add(onFact(existence(domain, fromField)));
        dependencies.add(onFact(existence(domain, toField)));
      }
      var identifying = Json.object().set("on", pairs);
      var descriptor =
          ElementDescriptor.edge(
              domain,
              ElementKind.JOIN,
              dataset,
              referenced,
              true,
              identifying,
              GeneratorSupport.datasetResource(catalog, dataset) + " -> " + referencedResource);
      var rationale =
          onObservation(
              schema,
              "foreign-key:" + foreignKey.path("name").asText(String.join(",", from)),
              foreignKey);
      dependencies.add(rationale);
      var method = declared(foreignKey);
      var checks = new ArrayList<Check>();
      for (var i = 0; i < from.size(); i++) {
        var item = Predicates.joinPairItem(pairs.get(i));
        // An equality join on strings may drop rows the foreign key admits, unless the collation is
        // known to compare bytes; the collation is not part of table metadata yet.
        var stringColumn = isString(types.getOrDefault(from.get(i), ""));
        checks.add(
            GeneratorSupport.check(
                method,
                List.of(item),
                stringColumn ? Check.Result.UNDECIDABLE : Check.Result.SUPPORTED,
                rationale));
      }
      out.add(Candidate.existence(descriptor, dependencies, checks));
    }
  }

  private static void participation(
      GeneratorContext context,
      ObservationView schema,
      String dataset,
      Map<String, Boolean> nullable,
      Function<String, String> field,
      List<Candidate> out) {
    var catalog = context.catalog();
    var domain = catalog.domain();
    for (var foreignKey : schema.content().path("foreignKeys")) {
      var from = columns(foreignKey);
      if (!foreignKey.path("enforced").asBoolean(false)
          || from.stream().anyMatch(column -> nullable.getOrDefault(column, true))) {
        continue;
      }
      var referenced =
          GeneratorSupport.datasetUrn(
              domain, foreignKey.path("references").path("resource").asText());
      var to = new ArrayList<String>();
      foreignKey.path("references").path("columns").forEach(column -> to.add(column.asText()));
      var pairs = Json.array();
      for (var i = 0; i < from.size() && i < to.size(); i++) {
        pairs.add(
            Json.array().add(field.apply(from.get(i))).add(Urns.field(referenced, to.get(i))));
      }
      var join =
          Urns.edge(
              domain, ElementKind.JOIN, dataset, referenced, true, Json.object().set("on", pairs));
      if (!GeneratorSupport.confirmed(catalog, join)) {
        continue;
      }
      var rationale =
          onObservation(
              schema,
              "foreign-key:" + foreignKey.path("name").asText(String.join(",", from)),
              foreignKey);
      out.add(
          new Candidate(
              join,
              null,
              Predicates.PARTICIPATION_FROM,
              Scope.UNRESTRICTED,
              Json.object().put("participation", "all"),
              List.of(onFact(existence(domain, join)), rationale),
              List.of(GeneratorSupport.supported(Check.Method.DECLARED_ENFORCED, rationale)),
              List.of()));
    }
  }

  private static void valueKinds(
      String domain,
      ObservationView schema,
      Map<String, String> types,
      Function<String, String> field,
      List<Candidate> out) {
    for (var column : schema.content().path("columns")) {
      var name = column.path("name").asText();
      var kind = valueKind(types.getOrDefault(name, ""));
      if (kind == null) {
        continue;
      }
      var urn = field.apply(name);
      var rationale = onObservation(schema, "column:" + name, column);
      out.add(
          new Candidate(
              urn,
              null,
              Predicates.VALUE_KIND,
              Scope.UNRESTRICTED,
              Json.object().put("kind", kind),
              List.of(onFact(existence(domain, urn)), rationale),
              List.of(GeneratorSupport.supported(Check.Method.DECLARED_ENFORCED, rationale)),
              List.of()));
    }
  }

  private static @org.jspecify.annotations.Nullable String valueKind(String sourceType) {
    var type = sourceType.toLowerCase(Locale.ROOT);
    if (type.startsWith("date") || type.startsWith("time") || type.contains("timestamp")) {
      return "time";
    }
    if (type.startsWith("bool") || type.equals("tinyint(1)")) {
      return "boolean";
    }
    return null;
  }

  private static boolean isString(String sourceType) {
    var type = sourceType.toLowerCase(Locale.ROOT);
    return type.contains("char") || type.contains("text") || type.equals("string");
  }
}
