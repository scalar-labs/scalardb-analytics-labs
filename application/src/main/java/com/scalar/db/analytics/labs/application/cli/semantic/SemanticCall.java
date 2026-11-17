/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.semantic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.cli.CliOutput;
import com.scalar.db.analytics.labs.application.cli.WorkspaceOption;
import com.scalar.db.analytics.labs.application.semantic.SemanticCatalogService;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * A {@code semantic} subcommand that makes one call through {@link SemanticCatalogService} and
 * prints its result as one versioned JSON object.
 */
abstract class SemanticCall implements Callable<Integer> {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final Map<String, String> environment;

  @Mixin private WorkspaceOption workspaceOption = new WorkspaceOption();

  @Spec private @Nullable CommandSpec commandSpec;

  SemanticCall(Map<String, String> environment) {
    this.environment = environment;
  }

  /** Makes the call and returns the output, a {@link #envelope}. */
  abstract Map<String, Object> call(SemanticCatalogService service);

  @Override
  public final Integer call() throws JsonProcessingException {
    var out = Objects.requireNonNull(commandSpec, "commandSpec").commandLine().getOut();
    try {
      var workspace = Workspace.load(workspaceOption.directory(environment), environment);
      CliOutput.write(out, call(new SemanticCatalogService(workspace)));
      return CliOutput.SUCCESS;
    } catch (ApplicationFailure e) {
      return CliOutput.writeFailure(out, e);
    }
  }

  /** An output object: its schema, schema version {@code 1}, and fields in the order given. */
  static Map<String, Object> envelope(String schema, Object... fields) {
    var output = new LinkedHashMap<String, Object>();
    output.put("schema", "scalardb-analytics/" + schema);
    output.put("schemaVersion", "1");
    for (var index = 0; index < fields.length; index += 2) {
      output.put((String) fields[index], fields[index + 1]);
    }
    return output;
  }

  /** An enum constant named by an option value, in upper or kebab case. */
  static <E extends Enum<E>> E constant(Class<E> type, String value, String option) {
    try {
      return Enum.valueOf(type, value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApplicationFailure("INVALID_REQUEST", "Unknown value for " + option + ": " + value);
    }
  }

  /** Reads a JSON input from a file, or from standard input when the path is {@code -}. */
  static JsonNode readJson(Path path, InputStream standardInput) {
    try {
      var text =
          path.toString().equals("-")
              ? new String(standardInput.readAllBytes(), StandardCharsets.UTF_8)
              : Files.readString(path, StandardCharsets.UTF_8);
      return OBJECT_MAPPER.readTree(text);
    } catch (JsonProcessingException e) {
      throw new ApplicationFailure("INVALID_REQUEST", "The input is not JSON: " + path);
    } catch (IOException e) {
      throw new ApplicationFailure("INVALID_REQUEST", "The input cannot be read: " + path);
    }
  }

  /** A required text property of a JSON input object. */
  static String text(JsonNode node, String property, String where) {
    var value = node.get(property);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new ApplicationFailure(
          "INVALID_REQUEST", where + " needs the text property \"" + property + "\".");
    }
    return value.textValue();
  }

  /** An optional text property of a JSON input object. */
  static @Nullable String optionalText(JsonNode node, String property, String where) {
    var value = node.get(property);
    if (value == null || value.isNull()) {
      return null;
    }
    return text(node, property, where);
  }

  /** Converts a value with a function that may reject it as invalid input. */
  static <T, R> R convert(T value, Function<T, R> conversion) {
    try {
      return conversion.apply(value);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new ApplicationFailure(
          "INVALID_REQUEST", Objects.requireNonNullElse(e.getMessage(), "The input is invalid."));
    }
  }
}
