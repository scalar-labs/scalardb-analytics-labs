/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.List;
import java.util.Optional;

/**
 * The physical object a Dataset or a Field refers to: a Physical Catalog table, named by the
 * Physical Catalog's registration unit (data-source registration, namespace, and table name) rather
 * than by its resource identifier, or a column of one (see {@code
 * docs/semantic-catalog/record-model.md}, "Identity and URNs").
 *
 * <p>A Physical Catalog reference never moves to another object, so a renamed table or column is
 * another Physical Catalog reference. Its {@linkplain #canonical() canonical form} is both the
 * matching value of a physical-catalog-reference Fact and the identifier of the element's URN.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "object")
@JsonSubTypes({
  @JsonSubTypes.Type(value = PhysicalCatalogReference.Table.class, name = "table"),
  @JsonSubTypes.Type(value = PhysicalCatalogReference.Column.class, name = "column")
})
public sealed interface PhysicalCatalogReference {
  /** The kind of element that refers to this object. */
  @JsonIgnore
  ElementKind elementKind();

  /**
   * The canonical form: each name {@linkplain Urns#encode(String) encoded} and joined by dots, such
   * as {@code shop.public.orders.amount}.
   */
  @JsonIgnore
  String canonical();

  /** A table, which a Dataset refers to. */
  record Table(String registration, String namespace, String table)
      implements PhysicalCatalogReference {
    public Table {
      requireName(registration, "data-source registration");
      requireName(namespace, "namespace");
      requireName(table, "table");
    }

    /**
     * The table a name path names in a data source: a namespace and a table name, as the Query
     * Engine attaches them. A name path of any other length names no table this way, and neither
     * does one with an empty name.
     *
     * @param registration the data-source registration
     * @param namePath the table's name path within the source, as table metadata states it
     */
    public static Optional<Table> of(String registration, List<String> namePath) {
      if (registration.isEmpty()
          || namePath.size() != 2
          || namePath.stream().anyMatch(String::isEmpty)) {
        return Optional.empty();
      }
      return Optional.of(new Table(registration, namePath.get(0), namePath.get(1)));
    }

    @Override
    public ElementKind elementKind() {
      return ElementKind.DATASET;
    }

    @Override
    public String canonical() {
      return Urns.encode(registration) + "." + Urns.encode(namespace) + "." + Urns.encode(table);
    }
  }

  /** A column of a table, which a Field refers to. */
  record Column(Table table, String column) implements PhysicalCatalogReference {
    public Column {
      requireName(column, "column");
    }

    @Override
    public ElementKind elementKind() {
      return ElementKind.FIELD;
    }

    @Override
    public String canonical() {
      return table.canonical() + "." + Urns.encode(column);
    }
  }

  private static void requireName(String name, String what) {
    if (name.isEmpty()) {
      throw new IllegalArgumentException("A Physical Catalog reference names its " + what);
    }
  }
}
