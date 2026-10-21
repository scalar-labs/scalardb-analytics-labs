/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.TreeMap;

/** JSON encoding, canonical forms, and digests shared by the Semantic Catalog. */
public final class Json {
  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private Json() {}

  public static ObjectNode object() {
    return JsonNodeFactory.instance.objectNode();
  }

  public static ArrayNode array() {
    return JsonNodeFactory.instance.arrayNode();
  }

  public static JsonNode tree(Object value) {
    return MAPPER.valueToTree(value);
  }

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("The value cannot be encoded as JSON.", e);
    }
  }

  public static JsonNode read(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("The text is not valid JSON.", e);
    }
  }

  public static <T> T read(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("The text does not match " + type.getSimpleName(), e);
    }
  }

  public static <T> T convert(JsonNode node, Class<T> type) {
    try {
      return MAPPER.treeToValue(node, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("The value does not match " + type.getSimpleName(), e);
    }
  }

  /** Encodes a value with object keys sorted at every level and no insignificant whitespace. */
  public static String canonical(Object value) {
    return write(sorted(tree(value)));
  }

  private static Object sorted(JsonNode node) {
    if (node.isObject()) {
      var map = new TreeMap<String, Object>();
      node.properties().forEach(entry -> map.put(entry.getKey(), sorted(entry.getValue())));
      return map;
    }
    if (node.isArray()) {
      var list = new ArrayList<Object>();
      node.forEach(item -> list.add(sorted(item)));
      return list;
    }
    return node;
  }

  public static String sha256(String text) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /** The digest of a value's canonical form. */
  public static String digest(Object value) {
    return sha256(canonical(value));
  }
}
