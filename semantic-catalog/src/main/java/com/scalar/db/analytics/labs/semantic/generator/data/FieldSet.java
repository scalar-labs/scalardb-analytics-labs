/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Column;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Fact;
import com.scalar.db.analytics.labs.semantic.generator.Proposals;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * A set of Fields of one Dataset whose existence is confirmed, with the columns a data check reads
 * them from, in the order of the Fields' URNs.
 *
 * @param fields the Fields' URNs, sorted
 * @param columns the Fields' columns, in the same order
 * @param datasetExistence the Dataset's confirmed existence Fact
 */
record FieldSet(String dataset, List<String> fields, List<Column> columns, Fact datasetExistence) {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final IdentityKey EXISTS = IdentityKey.choice("exists");

  FieldSet {
    fields = List.copyOf(fields);
    columns = List.copyOf(columns);
  }

  /**
   * The Field set of Fields, when each exists with confirmed existence in one Dataset whose
   * existence is confirmed and whose table is known.
   */
  static Optional<FieldSet> of(GeneratorContext context, Collection<String> fields) {
    var sorted = new TreeSet<>(fields);
    if (sorted.isEmpty()) {
      return Optional.empty();
    }
    var columns = new ArrayList<Column>();
    for (var field : sorted) {
      var column = context.column(field);
      if (column.isEmpty() || column.get().existence().state() != FactState.CONFIRMED) {
        return Optional.empty();
      }
      if (!columns.isEmpty()
          && (!columns.getFirst().dataset().equals(column.get().dataset())
              || !columns
                  .getFirst()
                  .table()
                  .inputResource()
                  .equals(column.get().table().inputResource()))) {
        return Optional.empty();
      }
      columns.add(column.get());
    }
    var dataset = columns.getFirst().dataset();
    var existence =
        context
            .closedRangeFact(dataset, Vocabulary.EXISTENCE, EXISTS)
            .filter(fact -> fact.state() == FactState.CONFIRMED);
    return existence.map(found -> new FieldSet(dataset, List.copyOf(sorted), columns, found));
  }

  PhysicalCatalogReference.Table table() {
    return columns.getFirst().reference();
  }

  /** The latest physical schema observation of the table. */
  String physicalSchema() {
    return columns.getFirst().table().observation();
  }

  List<String> columnNames() {
    return columns.stream().map(Column::name).toList();
  }

  /** The uniqueness predicate of the set, which carries it. */
  String uniqueness() {
    return Vocabulary.uniqueness(fields);
  }

  /** The set as a check's target. */
  JsonNode target() {
    var node = JSON.objectNode().put("dataset", dataset);
    var array = node.putArray("fields");
    fields.forEach(array::add);
    return node;
  }

  /** The dependencies on the existence of the Dataset and of each Field. */
  List<Dependency> premises() {
    var premises = new ArrayList<Dependency>();
    premises.add(Proposals.on(datasetExistence));
    columns.forEach(column -> premises.add(Proposals.on(column.existence())));
    return premises;
  }

  /** The Fact Revisions the premises name. */
  List<String> revisions() {
    var revisions = new ArrayList<String>();
    revisions.add(datasetExistence.revision());
    columns.forEach(column -> revisions.add(column.existence().revision()));
    return revisions;
  }
}
