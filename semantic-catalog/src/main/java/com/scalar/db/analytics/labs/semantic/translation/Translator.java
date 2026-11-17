/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.llm.LanguageModel;
import com.scalar.db.analytics.labs.semantic.llm.LanguageModelException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translation of a record's text from the recording language into one other declared language by a
 * language model (see {@code docs/semantic-catalog/languages.md}, "Translations"). The languages
 * and the texts are given in each call's input, so the instructions, and with them the translator's
 * version, are the same in every domain and language.
 */
public final class Translator {
  /** The fixed instructions of every translation call. */
  public static final String INSTRUCTIONS =
      """
      You translate text recorded in a semantic catalog for a reader of another language.

      "sourceLanguage" and "targetLanguage" in the input are BCP 47 language tags. "texts" maps \
      keys to texts written in the source language. Say each text in the target language with \
      the same meaning, neither adding nor leaving out anything. Keep identifiers, SQL, numbers, \
      and quoted document wording exactly as written.

      Answer with exactly one JSON object and nothing else: {"texts": {<each key of the input>: \
      <its translation>}}, with every key of the input and no other.
      """;

  private static final JsonNode PROMPT_VERSION =
      JsonNodeFactory.instance
          .objectNode()
          .put("name", "translation")
          .put("sha256", sha256(INSTRUCTIONS));

  private Translator() {}

  /** The version a translation records: the model's identity and the instructions, hashed. */
  public static JsonNode version(LanguageModel model) {
    var version = JsonNodeFactory.instance.objectNode();
    version.set("model", model.identity());
    version.set("prompt", PROMPT_VERSION);
    return version;
  }

  /** The input of one call: the languages and the texts of one record. */
  public static String input(String source, String target, Map<String, String> texts) {
    var json = JsonNodeFactory.instance.objectNode();
    json.put("sourceLanguage", source);
    json.put("targetLanguage", target);
    var given = json.putObject("texts");
    texts.forEach(given::put);
    return json.toString();
  }

  /**
   * Reads the translations from a model's answer, read as one JSON object.
   *
   * @throws LanguageModelException when the answer does not translate exactly the texts given
   */
  public static Map<String, String> read(JsonNode answer, Map<String, String> texts) {
    var translated = answer.path("texts");
    if (!translated.isObject()) {
      throw new LanguageModelException("The model's translation has no texts");
    }
    var result = new LinkedHashMap<String, String>();
    for (var key : texts.keySet()) {
      var text = translated.path(key);
      if (!text.isTextual() || text.textValue().isBlank()) {
        throw new LanguageModelException("The model's translation leaves out " + key);
      }
      result.put(key, text.textValue());
    }
    if (translated.size() != texts.size()) {
      throw new LanguageModelException("The model's translation has texts it was not given");
    }
    return result;
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
