/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Table;
import com.scalar.db.analytics.labs.semantic.generator.Output;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.PhysicalCatalogReference;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.RetirementReason;
import java.util.HashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Table and column ingestion: the existence and Physical Catalog reference of a table's Dataset and
 * of a Field per column, all {@code declared-enforced}, and the retirement of a Dataset whose table
 * no longer exists or a Field whose column disappeared (see {@code
 * docs/semantic-catalog/generators.md}, "Schema interpretation" and "Column changes").
 *
 * <p>A column that disappears is retired and one that appears is a new Field, whether or not the
 * two are a rename, because names cannot tell a rename from a drop and an add. A column the table
 * metadata may be hiding has not disappeared.
 */
final class TableColumnIngestion extends SchemaGenerator {
  static final String NAME = "table-and-column-ingestion";

  TableColumnIngestion() {
    super(NAME);
  }

  @Override
  public List<Input> inputs() {
    return List.of(new Input.PhysicalSchema());
  }

  @Override
  void interpret(
      GeneratorContext context,
      Output output,
      Table table,
      PhysicalCatalogReference.Table reference) {
    var declarations = declarations(table);
    var dataset = context.element(reference);
    if (declarations.isEmpty()) {
      dataset
          .flatMap(element -> context.closedRangeFact(element, Vocabulary.EXISTENCE, EXISTS_KEY))
          .filter(GeneratorContext.Fact::retirable)
          .ifPresent(
              existence ->
                  proposeRetire(
                      context,
                      output,
                      table,
                      existence,
                      RetirementReason.ELEMENT_NO_LONGER_EXISTS,
                      JSON.objectNode()
                          .put("reason", "The source reports the table does not exist")));
      return;
    }
    var declared = declarations.get();
    var read = inputsRead(table, List.of());
    var datasetRef =
        output.add(new LogRecord.Element(ElementIdentity.PhysicalCatalogObject.dataset(reference)));
    var datasetExistence = ingest(context, output, table, datasetRef, reference, null, read);
    var columns = new HashSet<String>();
    for (var column : declared.columns()) {
      var columnReference = new PhysicalCatalogReference.Column(reference, column.name());
      columns.add(columnReference.canonical());
      var field =
          output.add(
              new LogRecord.Element(
                  ElementIdentity.PhysicalCatalogObject.field(datasetRef, columnReference)));
      ingest(context, output, table, field, columnReference, datasetExistence, read);
    }
    if (!declared.columnsComplete() || dataset.isEmpty()) {
      return;
    }
    for (var field : context.fields(dataset.get())) {
      if (columns.contains(field.reference())) {
        continue;
      }
      context
          .closedRangeFact(field.element(), Vocabulary.EXISTENCE, EXISTS_KEY)
          .filter(GeneratorContext.Fact::retirable)
          .ifPresent(
              existence ->
                  proposeRetire(
                      context,
                      output,
                      table,
                      existence,
                      RetirementReason.ELEMENT_NO_LONGER_EXISTS,
                      JSON.objectNode()
                          .put("reason", "The table metadata no longer lists the column")));
    }
  }

  /**
   * Proposes an element's existence and Physical Catalog reference, each supported by the table's
   * declaration; returns the existence's acceptance.
   *
   * @param parentExistence the existence of the Dataset a Field belongs to, its premise
   */
  private Accepted ingest(
      GeneratorContext context,
      Output output,
      Table table,
      Ref element,
      PhysicalCatalogReference reference,
      @Nullable Accepted parentExistence,
      JsonNode read) {
    var existenceDependencies =
        parentExistence == null
            ? List.of(onObservation(table))
            : List.of(onObservation(table), parentExistence.dependency());
    var existence =
        acceptClosedRange(
            context,
            output,
            address(output, element, Vocabulary.EXISTENCE),
            JSON.textNode(EXISTS),
            EXISTS_KEY,
            existenceDependencies,
            List.of(declared(true, JSON.textNode(EXISTS), table, JSON.objectNode())),
            read);
    var canonical = reference.canonical();
    acceptClosedRange(
        context,
        output,
        address(output, element, Vocabulary.PHYSICAL_CATALOG_REFERENCE),
        JSON.textNode(canonical),
        IdentityKey.choice(canonical),
        List.of(onObservation(table), existence.dependency()),
        List.of(declared(true, JSON.textNode(canonical), table, JSON.objectNode())),
        read);
    return existence;
  }
}
