/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.ModelInputSettings;
import com.scalar.db.analytics.labs.semantic.llm.LanguageModel;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * A non-deterministic Generator that asks a language model (see {@code
 * docs/semantic-catalog/generators.md}, "Meaning inference" and "Generation strategy").
 *
 * <p>A model answer takes far longer than a store read, so a run holds no transaction while it
 * waits, the way a {@link DataGenerator} holds none while its queries run. It plans its targets and
 * the data reads the domain's settings allow from the latest commit, the reads and the model calls
 * run outside any transaction, and it records what the answers propose inside the transaction that
 * commits its output, for the Fact Addresses that still call for a candidate there. A collision
 * repeats only the last step, with the same answers.
 */
public interface ModelGenerator extends Generator {
  /**
   * The fixed instructions the model reads, the whole fixed part of what it is given, so that they
   * and the model identify what produced a candidate.
   */
  String instructions();

  /**
   * The targets the run's triggers call for and the data reads for them.
   *
   * @param context the store at the latest commit; nothing planned here is recorded
   * @param settings which data reads the domain lets reach the model
   */
  Plan plan(GeneratorContext context, ModelInputSettings settings);

  /**
   * What a run plans.
   *
   * @param asks the model calls, each over a bounded share of the targets; none means the model is
   *     not called
   * @param queries the data reads, recorded as data observations
   * @param missing input the run lacks, such as reads the domain's settings withhold
   */
  record Plan(List<Ask> asks, List<DataGenerator.Query> queries, List<String> missing) {
    public Plan {
      asks = List.copyOf(asks);
      queries = List.copyOf(queries);
      missing = List.copyOf(missing);
    }
  }

  /**
   * One model call: a bounded share of a run's targets, so a large catalog becomes several calls of
   * a size the model can answer instead of one that fails every time it is resumed.
   *
   * @param input what the model reads, before the data reads are added to it
   * @param targets the elements this call produces for
   * @param read the Input Observations and Fact Revisions the input holds, which each candidate
   *     records as inputs read, so provenance shows what the model saw
   */
  record Ask(JsonNode input, List<Target> targets, List<String> read) {
    public Ask {
      targets = List.copyOf(targets);
      read = List.copyOf(read);
    }
  }

  /**
   * One element a run produces for.
   *
   * @param element the element's URN
   * @param reference how the model reads and names the element
   * @param predicates the predicates asked for
   * @param premises the dependencies on what the model reads about the element, as planned: its
   *     candidates rest on what the model saw, even when a newer observation arrives while it
   *     answers
   */
  record Target(
      String element, String reference, List<String> predicates, List<Dependency> premises) {
    public Target {
      predicates = List.copyOf(predicates);
      premises = List.copyOf(premises);
    }
  }

  /** A model call with the answer it got, one JSON object. */
  record Answered(Ask ask, JsonNode answer) {}

  /**
   * What the model reads in one call: the planned input with what the data reads gave.
   *
   * @param reads the outcome of each planned query, in planned order; a query that failed is absent
   */
  String input(Ask ask, List<DataGenerator.Read> reads);

  /**
   * Produces candidates from the model's answers for the targets that still call for one.
   *
   * @param context what the run reads, in the recording transaction
   * @param reads the outcome of each planned query that ran
   * @param answers each call with its answer
   * @param output where the run puts what it produces
   * @throws com.scalar.db.analytics.labs.semantic.llm.LanguageModelException when an answer is not
   *     in the form the instructions ask for
   */
  void record(
      GeneratorContext context,
      Plan plan,
      List<DataGenerator.Read> reads,
      List<Answered> answers,
      Output output);

  /**
   * The version provenance records: the Generator's own version, the model's identity, and the
   * instructions, named and hashed, so a change to any of them makes a new version.
   */
  default String version(LanguageModel model) {
    var version = JsonNodeFactory.instance.objectNode();
    version.put("generator", version());
    version.set("model", model.identity());
    version.putObject("prompt").put("name", name()).put("sha256", sha256(instructions()));
    return version.toString();
  }

  private static String sha256(String text) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
