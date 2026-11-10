/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;

/**
 * The JSON form in which log records, and the lists held in {@code jsonb} columns, are stored.
 *
 * <p>The record kind is stored with each record, and record references are stored as URN strings.
 * Only records whose temporary numbers are all resolved can be written.
 */
public final class LogRecordJson {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private LogRecordJson() {}

  public static String write(LogRecord record) {
    try {
      return MAPPER.writeValueAsString(record);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot write a log record as JSON", e);
    }
  }

  public static LogRecord read(String json) {
    try {
      return MAPPER.readValue(json, LogRecord.class);
    } catch (JsonProcessingException e) {
      throw new SemanticCatalogException("Cannot read a stored log record", e);
    }
  }

  public static String writeStrings(List<String> values) {
    try {
      return MAPPER.writeValueAsString(values);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot write a list as JSON", e);
    }
  }

  public static List<String> readStrings(String json) {
    try {
      return List.of(MAPPER.readValue(json, String[].class));
    } catch (JsonProcessingException e) {
      throw new SemanticCatalogException("Cannot read a stored list", e);
    }
  }

  public static String writeNode(JsonNode node) {
    return node.toString();
  }

  public static JsonNode readNode(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new SemanticCatalogException("Cannot read stored JSON", e);
    }
  }
}
