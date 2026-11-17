/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Languages;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.InputKind;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow;
import com.scalar.db.analytics.labs.semantic.projection.Projection;
import com.scalar.db.analytics.labs.semantic.storage.StoreTransaction;
import com.scalar.db.analytics.labs.semantic.translation.TranslatableText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Requesting translations and finding the log records whose text they translate (see {@code
 * docs/semantic-catalog/languages.md}, "Translations").
 */
final class Translations {
  private Translations() {}

  /**
   * The log record that holds a record's text: a Fact's value and a Fact Address's descriptions are
   * in the log record that created them, a Fact Revision's text, such as an evaluation's reason or
   * an answer's wording, in the Fact Event that produced it, and an Input Observation's or a log
   * record's in the log record at its position.
   *
   * @throws SemanticCatalogException when the URN names no record of its kind in the domain
   */
  static String logRecordOf(
      StoreTransaction transaction, String domain, Projection projection, String urn) {
    Optional<LogPosition> position;
    try {
      if (!Urns.domain(urn).equals(domain)) {
        throw unknown(urn);
      }
      position =
          switch (Urns.kind(urn)) {
            case Urns.FACT ->
                projection
                    .fact(urn)
                    .flatMap(
                        fact ->
                            transaction.namedRecordPosition(
                                domain, fact.row().createdCommit(), urn));
            case Urns.FACT_ADDRESS ->
                projection
                    .address(urn)
                    .flatMap(
                        address ->
                            transaction.namedRecordPosition(
                                domain, address.row().createdCommit(), urn));
            case Urns.FACT_REVISION -> {
              var fact = Urns.revisionFact(urn);
              var event = Urns.revisionEvent(urn);
              yield transaction
                  .logRecordAt(domain, event)
                  .filter(
                      found ->
                          found.record() instanceof LogRecord.FactEvent factEvent
                              && factEvent.fact().urnValue().equals(fact))
                  .map(found -> event);
            }
            case Urns.INPUT_OBSERVATION -> {
              var at = Urns.position(urn);
              yield transaction
                  .logRecordAt(domain, at)
                  .filter(found -> found.record() instanceof LogRecord.InputObservation)
                  .map(found -> at);
            }
            case Urns.LOG_RECORD -> Optional.of(Urns.position(urn));
            default -> Optional.empty();
          };
    } catch (IllegalArgumentException e) {
      throw unknown(urn);
    }
    return position
        .filter(found -> transaction.logRecordAt(domain, found).isPresent())
        .map(found -> Urns.logRecord(domain, found))
        .orElseThrow(() -> unknown(urn));
  }

  private static SemanticCatalogException unknown(String urn) {
    return new SemanticCatalogException("Unknown record " + urn);
  }

  /** The translatable text of a log record, as it is stored. */
  static Map<String, String> textOf(
      StoreTransaction transaction, String domain, Projection projection, String logRecord) {
    return transaction
        .logRecordAt(domain, Urns.position(logRecord))
        .map(positioned -> TranslatableText.of(positioned.record(), context(projection)))
        .orElse(Map.of());
  }

  private static TranslatableText.Context context(Projection projection) {
    return new TranslatableText.Context() {
      @Override
      public Optional<MaterializedRow.FactAddressRow> address(String factAddress) {
        return projection.address(factAddress).map(Projection.AddressSlice::row);
      }

      @Override
      public Optional<InputKind> inputKind(String inputResource) {
        return projection.inputResource(inputResource).map(MaterializedRow.InputResourceRow::kind);
      }
    };
  }

  /**
   * Requests the translation of some records' text into a language. Records already translated into
   * it, and records with no translatable text, are left out; the rest become triggers of the
   * language's created translation task. Nothing is requested in the recording language, whose text
   * is the original.
   *
   * @throws SemanticCatalogException when the language is not declared or a record is unknown
   */
  static SemanticCatalogStore.TranslationRequest request(
      StoreTransaction transaction,
      String domain,
      Languages languages,
      String language,
      List<String> records) {
    requireDeclared(languages, language);
    if (language.equals(languages.primary())) {
      return new SemanticCatalogStore.TranslationRequest(OptionalLong.empty(), Map.of());
    }
    var projection = new Projection(domain, transaction.projectionSource(domain));
    var byRecord = new LinkedHashMap<String, String>();
    for (var urn : records) {
      byRecord.put(urn, logRecordOf(transaction, domain, projection, urn));
    }
    var translated = transaction.translations(domain, language, byRecord.values()).keySet();
    var pending = new LinkedHashMap<String, String>();
    var triggers = new ArrayList<String>();
    byRecord.forEach(
        (urn, logRecord) -> {
          if (translated.contains(logRecord)
              || textOf(transaction, domain, projection, logRecord).isEmpty()) {
            return;
          }
          pending.put(urn, logRecord);
          if (!triggers.contains(logRecord)) {
            triggers.add(logRecord);
          }
        });
    if (triggers.isEmpty()) {
      return new SemanticCatalogStore.TranslationRequest(OptionalLong.empty(), pending);
    }
    var work = new BuildTask.Work.Translation(language);
    long task = -1;
    for (var trigger : triggers) {
      task = BuildTasks.trigger(transaction, domain, work, trigger);
    }
    return new SemanticCatalogStore.TranslationRequest(OptionalLong.of(task), pending);
  }

  /**
   * Requires a declared language.
   *
   * @throws SemanticCatalogException when the domain does not declare it
   */
  static void requireDeclared(Languages languages, String language) {
    if (!languages.declares(language)) {
      throw new SemanticCatalogException(
          "The domain does not declare the language "
              + language
              + "; its languages are "
              + String.join(", ", languages.declared()));
    }
  }
}
