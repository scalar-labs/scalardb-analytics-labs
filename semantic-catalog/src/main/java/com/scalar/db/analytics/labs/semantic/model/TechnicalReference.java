/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;

/**
 * The physical object a Dataset or a Field refers to: a table, named by its data-source
 * registration, namespace, and table name, or a column of one (see {@code
 * docs/semantic-catalog/record-model.md}, "Identity and URNs").
 *
 * <p>A technical reference never moves to another object, so a renamed table or column is another
 * technical reference. Its {@linkplain #canonical() canonical form} is both the matching value of a
 * technical-reference Fact and the identifier of the element's URN.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "object")
@JsonSubTypes({
  @JsonSubTypes.Type(value = TechnicalReference.Table.class, name = "table"),
  @JsonSubTypes.Type(value = TechnicalReference.Column.class, name = "column")
})
public sealed interface TechnicalReference {
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
  record Table(String registration, String namespace, String table) implements TechnicalReference {
    public Table {
      requireName(registration, "data-source registration");
      requireName(namespace, "namespace");
      requireName(table, "table");
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
  record Column(Table table, String column) implements TechnicalReference {
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
      throw new IllegalArgumentException("A technical reference names its " + what);
    }
  }
}
