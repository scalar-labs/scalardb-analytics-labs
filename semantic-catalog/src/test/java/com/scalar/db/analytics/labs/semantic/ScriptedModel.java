/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.generator.Generators;
import com.scalar.db.analytics.labs.semantic.generator.ModelGenerator;
import com.scalar.db.analytics.labs.semantic.llm.LanguageModel;
import com.scalar.db.analytics.labs.semantic.policy.PolicyEvaluationModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A language model that answers each model-backed Generator by a script, told apart by the
 * Generator's instructions rather than by the wording of its input, and keeps what it was asked. A
 * Generator with no script gets an answer with no targets.
 */
final class ScriptedModel implements LanguageModel {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** No data read reaches the model, so a run reads no data. */
  static final ModelInputSettings NO_DATA = new ModelInputSettings(false, false);

  /** One call: the Generator asked, by name, and the input it gave, as JSON. */
  record Call(String generator, JsonNode input) {}

  private final Map<String, String> generatorsByInstructions = new HashMap<>();
  private final Map<String, Function<JsonNode, String>> scripts = new HashMap<>();
  private final String model;
  final List<Call> calls = new ArrayList<>();

  ScriptedModel() {
    this("scripted-1");
  }

  ScriptedModel(String model) {
    this.model = model;
    for (var generator : Generators.all()) {
      if (generator instanceof ModelGenerator asking) {
        generatorsByInstructions.put(asking.instructions(), asking.name());
      }
    }
  }

  /** Answers a Generator's calls by a script over its input. */
  ScriptedModel answering(String generator, Function<JsonNode, String> script) {
    scripts.put(generator, script);
    return this;
  }

  /** An environment whose model-backed Generators get answers with no targets and read no data. */
  static BuildRun.Environment silent(PolicyEvaluationModel policy, ConstructionSql sql) {
    return new BuildRun.Environment(policy, sql, new ScriptedModel(), NO_DATA);
  }

  @Override
  public JsonNode identity() {
    return JsonNodeFactory.instance.objectNode().put("adapter", "scripted").put("model", model);
  }

  @Override
  public synchronized String complete(String instructions, String input) {
    var generator = generatorsByInstructions.get(instructions);
    if (generator == null) {
      throw new AssertionError("A caller other than a model-backed Generator asked the model");
    }
    JsonNode json;
    try {
      json = MAPPER.readTree(input);
    } catch (Exception e) {
      throw new AssertionError("A Generator's input is not JSON", e);
    }
    calls.add(new Call(generator, json));
    return scripts.getOrDefault(generator, ignored -> "{\"targets\": []}").apply(json);
  }
}
