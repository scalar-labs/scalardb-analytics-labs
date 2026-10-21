/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.CatalogState.AddressRecord;
import com.scalar.db.analytics.labs.semantic.CatalogState.FactRecord;
import com.scalar.db.analytics.labs.semantic.model.Json;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The rows of the materialized state (section 6.5): current Fact Record states, presentable
 * periods, and per-Fact-Address selections with their commit ranges.
 */
final class MaterializedRows {
  private MaterializedRows() {}

  static List<List<@Nullable Object>> fact(FactRecord fact) {
    return List.of(
        Arrays.<@Nullable Object>asList(
            fact.urn, fact.address, stateName(fact), fact.latestRevision().urn()));
  }

  static List<List<@Nullable Object>> periods(FactRecord fact) {
    var rows = new ArrayList<List<@Nullable Object>>();
    for (var period : fact.periods) {
      rows.add(
          Arrays.<@Nullable Object>asList(fact.urn, period[0], period[1] < 0 ? null : period[1]));
    }
    return rows;
  }

  static List<List<@Nullable Object>> selections(AddressRecord address) {
    var rows = new ArrayList<List<@Nullable Object>>();
    for (var selection : address.history) {
      rows.add(
          Arrays.<@Nullable Object>asList(
              address.urn,
              selection.from(),
              selection.to() < 0 ? null : selection.to(),
              selection.selected(),
              Json.write(selection.eligible())));
    }
    return rows;
  }

  /** Every materialized row derived from a state, encoded for comparison. */
  static TreeSet<String> all(CatalogState state) {
    var rows = new TreeSet<String>();
    for (var fact : state.factRecords().values()) {
      fact(fact).forEach(row -> rows.add(encode("fact", row.toArray())));
      periods(fact).forEach(row -> rows.add(encode("period", row.toArray())));
    }
    for (var address : state.addressRecords().values()) {
      selections(address).forEach(row -> rows.add(encode("selection", row.toArray())));
    }
    return rows;
  }

  static String encode(String table, @Nullable Object... values) {
    var parts = new ArrayList<String>();
    parts.add(table);
    for (var value : values) {
      parts.add(String.valueOf(value));
    }
    return String.join("|", parts);
  }

  private static String stateName(FactRecord fact) {
    return Json.tree(fact.state).asText();
  }
}
