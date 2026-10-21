/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Converts between labs connection settings and upstream provider payloads, and removes credentials
 * from payloads that leave the embedded catalog.
 *
 * <p>The upstream catalog stores the full payload, including the password, and returns it in every
 * data source, namespace, and table message. This class is the single place that strips those
 * credentials before a message crosses the labs boundary.
 */
final class ProviderPayloads {
  static final String PAYLOAD_FIELD = "provider_payload_json";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String POSTGRESQL = "postgresql";
  private static final String MYSQL = "mysql";
  private static final Set<String> CREDENTIAL_KEYWORDS =
      Set.of("password", "secret", "token", "credential", "key");

  private ProviderPayloads() {}

  static String toPayload(DataSourceConnection connection) {
    ObjectNode payload = MAPPER.createObjectNode();
    switch (connection) {
      case DataSourceConnection.PostgreSql postgreSql -> {
        payload.put("type", POSTGRESQL);
        payload.put("host", postgreSql.host());
        payload.put("port", postgreSql.port());
        payload.put("username", postgreSql.username());
        payload.put("password", postgreSql.password().reveal());
        payload.put("database", postgreSql.database());
      }
      case DataSourceConnection.MySql mySql -> {
        payload.put("type", MYSQL);
        payload.put("host", mySql.host());
        payload.put("port", mySql.port());
        payload.put("username", mySql.username());
        payload.put("password", mySql.password().reveal());
        mySql.database().ifPresent(database -> payload.put("database", database));
      }
    }
    return payload.toString();
  }

  /** Returns the payload without any credential-bearing field. */
  static String redactPayload(String payload) {
    JsonNode node;
    try {
      node = MAPPER.readTree(payload);
    } catch (JsonProcessingException e) {
      return "{}";
    }
    if (!(node instanceof ObjectNode object)) {
      return "{}";
    }
    removeCredentials(object);
    return object.toString();
  }

  /** Removes credential-bearing fields at every depth, such as keys of nested config maps. */
  private static void removeCredentials(JsonNode node) {
    if (node instanceof ObjectNode object) {
      var credentialFields = new ArrayList<String>();
      object
          .fieldNames()
          .forEachRemaining(
              name -> {
                if (isCredentialField(name)) {
                  credentialFields.add(name);
                }
              });
      object.remove(credentialFields);
      object.elements().forEachRemaining(ProviderPayloads::removeCredentials);
    } else if (node.isArray()) {
      node.elements().forEachRemaining(ProviderPayloads::removeCredentials);
    }
  }

  /** Returns a copy of the message in which every provider payload has been redacted. */
  @SuppressWarnings("unchecked")
  static <M extends Message> M redactMessages(M message) {
    Message redacted = redact(message);
    return redacted == null ? message : (M) redacted;
  }

  static Optional<DataSourceDescriptor> toDescriptor(
      String catalogName, String name, String redactedPayload) {
    JsonNode payload;
    try {
      payload = MAPPER.readTree(redactedPayload);
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
    DataSourceDescriptor.Type type;
    switch (payload.path("type").asText()) {
      case POSTGRESQL -> type = DataSourceDescriptor.Type.POSTGRESQL;
      case MYSQL -> type = DataSourceDescriptor.Type.MYSQL;
      default -> {
        return Optional.empty();
      }
    }
    JsonNode database = payload.path("database");
    return Optional.of(
        new DataSourceDescriptor(
            catalogName,
            name,
            type,
            payload.path("host").asText(),
            payload.path("port").asInt(),
            database.isTextual() ? Optional.of(database.asText()) : Optional.empty(),
            payload.path("username").asText()));
  }

  private static boolean isCredentialField(String name) {
    var normalized = name.toLowerCase(Locale.ROOT);
    return CREDENTIAL_KEYWORDS.stream().anyMatch(normalized::contains);
  }

  /** Returns a redacted copy, or {@code null} when the message contains nothing to redact. */
  private static @Nullable Message redact(Message message) {
    Message.Builder builder = null;
    for (FieldDescriptor field : message.getDescriptorForType().getFields()) {
      if (field.getJavaType() == FieldDescriptor.JavaType.STRING
          && !field.isRepeated()
          && field.getName().equals(PAYLOAD_FIELD)) {
        var payload = (String) message.getField(field);
        if (!payload.isEmpty()) {
          builder = builder == null ? message.toBuilder() : builder;
          builder.setField(field, redactPayload(payload));
        }
      } else if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
        if (field.isRepeated()) {
          int count = message.getRepeatedFieldCount(field);
          for (int index = 0; index < count; index++) {
            var redacted = redact((Message) message.getRepeatedField(field, index));
            if (redacted != null) {
              builder = builder == null ? message.toBuilder() : builder;
              builder.setRepeatedField(field, index, redacted);
            }
          }
        } else if (message.hasField(field)) {
          var redacted = redact((Message) message.getField(field));
          if (redacted != null) {
            builder = builder == null ? message.toBuilder() : builder;
            builder.setField(field, redacted);
          }
        }
      }
    }
    return builder == null ? null : builder.build();
  }
}
