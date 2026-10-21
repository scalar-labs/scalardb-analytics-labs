/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Normal forms of Fact values, which decide whether two Facts are the same or incompatible.
 *
 * <p>Provisional and the same for every predicate: text is trimmed and its whitespace collapsed,
 * enumerated members are lower-case, and sets of Fields are sorted. The canonical JSON of the
 * result is the normal form.
 */
public final class NormalForms {
  private static final Set<String> ENUMERATED =
      Set.of(
          "kind",
          "multiplicity",
          "participation",
          "relationship",
          "function",
          "unique",
          "granularity");
  private static final Set<String> SETS = Set.of("fields", "columns", "entities");

  private NormalForms() {}

  public static JsonNode normalize(JsonNode value) {
    return normalize(null, value);
  }

  private static JsonNode normalize(@Nullable String field, JsonNode value) {
    if (value.isTextual()) {
      var text = value.asText().strip().replaceAll("\\s+", " ");
      if (field != null && ENUMERATED.contains(field)) {
        text = text.toLowerCase(Locale.ROOT);
      }
      return TextNode.valueOf(text);
    }
    if (value.isObject()) {
      var result = Json.object();
      value
          .properties()
          .forEach(
              entry -> result.set(entry.getKey(), normalize(entry.getKey(), entry.getValue())));
      return result;
    }
    if (value.isArray()) {
      var items = new ArrayList<JsonNode>();
      value.forEach(item -> items.add(normalize(null, item)));
      if (field != null && SETS.contains(field)) {
        items.sort(Comparator.comparing(Json::canonical));
      }
      var result = Json.array();
      items.forEach(result::add);
      return result;
    }
    return value;
  }

  /** The normal form of a value as text. */
  public static String of(JsonNode value) {
    return Json.canonical(normalize(value));
  }
}
