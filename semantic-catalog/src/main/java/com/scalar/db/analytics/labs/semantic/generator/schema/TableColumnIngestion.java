/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.schema;

import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.existence;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.onFact;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.onObservation;
import static com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport.supported;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.generator.Generator;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult.Candidate;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult.RetirementProposal;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorSupport;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.ElementDescriptor;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.Predicates;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.RetirementReason;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Table and column ingestion: the existence and technical references of Datasets and Fields, and
 * retirement proposals for columns marked as gone (generators.md 3.1).
 */
public final class TableColumnIngestion implements Generator {
  @Override
  public String name() {
    return "schema.table-column-ingestion";
  }

  @Override
  public String version() {
    return "1";
  }

  @Override
  public Set<String> inputs() {
    return Set.of(PHYSICAL_SCHEMA);
  }

  @Override
  public GeneratorResult run(GeneratorContext context) {
    var catalog = context.catalog();
    var domain = catalog.domain();
    var candidates = new ArrayList<Candidate>();
    var retirements = new ArrayList<RetirementProposal>();
    for (var schema : GeneratorSupport.triggeringSchemas(context)) {
      var metadata = schema.content();
      var resource = GeneratorSupport.resourceIdentifier(schema);
      var dataset = GeneratorSupport.datasetUrn(domain, resource);
      var datasetExists = existence(domain, dataset);
      if (metadata.path("exists").asBoolean(true)) {
        var tableRef = onObservation(schema, "table", tablePart(metadata));
        candidates.add(
            Candidate.existence(
                ElementDescriptor.node(dataset, ElementKind.DATASET, null, resource),
                List.of(tableRef),
                List.of(supported(Check.Method.DECLARED_ENFORCED, tableRef))));
        candidates.add(
            new Candidate(
                dataset,
                null,
                Predicates.TECHNICAL_REFERENCE,
                Scope.UNRESTRICTED,
                Json.object()
                    .put("resource", Urns.tableResource(domain, resource))
                    .put("sql", resource),
                List.of(onFact(datasetExists), tableRef),
                List.of(supported(Check.Method.DECLARED_ENFORCED, tableRef)),
                List.of()));
      }
      var current = new HashSet<String>();
      for (var column : metadata.path("columns")) {
        var name = column.path("name").asText();
        current.add(name);
        var field = Urns.field(dataset, name);
        var columnRef = onObservation(schema, "column:" + name, column);
        candidates.add(
            Candidate.existence(
                ElementDescriptor.node(field, ElementKind.FIELD, dataset, name),
                List.of(onFact(datasetExists), columnRef),
                List.of(supported(Check.Method.DECLARED_ENFORCED, columnRef))));
        candidates.add(
            new Candidate(
                field,
                null,
                Predicates.TECHNICAL_REFERENCE,
                Scope.UNRESTRICTED,
                Json.object()
                    .put("resource", Urns.tableResource(domain, resource))
                    .put("selector", "column:" + name)
                    .put("sql", name),
                List.of(onFact(existence(domain, field)), columnRef),
                List.of(supported(Check.Method.DECLARED_ENFORCED, columnRef)),
                List.of()));
      }
      // A column that was in an earlier observation and is marked gone now is retired. A column
      // that metadata reports as incomplete is not regarded as gone (02-catalog-contract.md 1).
      if (!metadata.path("complete").path("columns").asBoolean(true)) {
        continue;
      }
      for (var field : catalog.children(dataset)) {
        if (field.kind() != ElementKind.FIELD || current.contains(field.descriptor().label())) {
          continue;
        }
        var fact = catalog.fact(existence(domain, field.urn()));
        if (fact.isPresent()
            && (fact.get().state() == FactState.CONFIRMED
                || fact.get().state() == FactState.CANDIDATE)) {
          var gone = onObservation(schema, "column:" + field.descriptor().label(), Json.object());
          retirements.add(
              new RetirementProposal(
                  fact.get().urn(),
                  RetirementReason.ELEMENT_GONE,
                  List.of(gone),
                  List.of(supported(Check.Method.DECLARED_ENFORCED, gone))));
        }
      }
    }
    return new GeneratorResult(candidates, retirements, List.of());
  }

  private static JsonNode tablePart(JsonNode metadata) {
    return Json.object()
        .put("resource", metadata.path("resource").asText())
        .put("kind", metadata.path("kind").asText("table"));
  }
}
