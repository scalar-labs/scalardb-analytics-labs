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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
   * The log record that holds each of some records' text, with that text, by the URN each record is
   * named by: a Fact's value and a Fact Address's descriptions are in the log record that created
   * them, a Fact Revision's text, such as an evaluation's reason or an answer's wording, in the
   * Fact Event that produced it, and an Input Observation's or a log record's in the log record at
   * its position. However many records are named, they are found in a fixed number of queries.
   *
   * @throws SemanticCatalogException when a URN names no record of its kind in the domain
   */
  static Map<String, Original> originalsOf(
      StoreTransaction transaction, String domain, Collection<String> urns) {
    var facts = new ArrayList<String>();
    var addresses = new ArrayList<String>();
    var positions = new HashMap<String, LogPosition>();
    for (var urn : urns) {
      try {
        if (!Urns.domain(urn).equals(domain)) {
          throw unknown(urn);
        }
        switch (Urns.kind(urn)) {
          case Urns.FACT -> facts.add(urn);
          case Urns.FACT_ADDRESS -> addresses.add(urn);
          case Urns.FACT_REVISION -> positions.put(urn, Urns.revisionEvent(urn));
          case Urns.INPUT_OBSERVATION, Urns.LOG_RECORD -> positions.put(urn, Urns.position(urn));
          default -> throw unknown(urn);
        }
      } catch (IllegalArgumentException e) {
        throw unknown(urn);
      }
    }
    positions.putAll(transaction.creatingPositions(domain, facts, addresses));
    var records = new HashMap<LogPosition, LogRecord>();
    transaction
        .logRecordsAt(domain, new HashSet<>(positions.values()))
        .forEach(positioned -> records.put(positioned.position(), positioned.record()));
    var context = context(transaction, domain, records.values());
    var texts = new HashMap<LogPosition, Map<String, String>>();
    var originals = new LinkedHashMap<String, Original>();
    for (var urn : urns) {
      var position = positions.get(urn);
      var record = position == null ? null : records.get(position);
      if (position == null || record == null || !names(urn, record)) {
        throw unknown(urn);
      }
      originals.put(
          urn,
          new Original(
              Urns.logRecord(domain, position),
              texts.computeIfAbsent(position, ignored -> TranslatableText.of(record, context))));
    }
    return originals;
  }

  /** Whether a record found at the position a URN gives is the record the URN names. */
  private static boolean names(String urn, LogRecord record) {
    return switch (Urns.kind(urn)) {
      case Urns.FACT_REVISION ->
          record instanceof LogRecord.FactEvent event
              && event.fact().urnValue().equals(Urns.revisionFact(urn));
      case Urns.INPUT_OBSERVATION -> record instanceof LogRecord.InputObservation;
      default -> true;
    };
  }

  /**
   * The original text of a log record.
   *
   * @param logRecord the URN of the log record that holds the text
   * @param texts each text by its key in the record; empty when the record has none
   */
  record Original(String logRecord, Map<String, String> texts) {}

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
   * The context of some records' text, with the Fact Addresses of their Facts read in one query.
   * Input Resources, which only usage feedback observations look up, are read as they are needed.
   */
  private static TranslatableText.Context context(
      StoreTransaction transaction, String domain, Collection<LogRecord> records) {
    var factAddresses = new HashSet<String>();
    records.forEach(
        record -> {
          if (record instanceof LogRecord.Fact fact) {
            factAddresses.add(fact.address().urnValue());
          }
        });
    var rows = new HashMap<String, MaterializedRow.FactAddressRow>();
    transaction
        .addressesAt(domain, factAddresses, Long.MAX_VALUE)
        .forEach(row -> rows.put(row.factAddress(), row));
    var projection = new Projection(domain, transaction.projectionSource(domain));
    return new TranslatableText.Context() {
      @Override
      public Optional<MaterializedRow.FactAddressRow> address(String factAddress) {
        return Optional.ofNullable(rows.get(factAddress));
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
    var originals = originalsOf(transaction, domain, records);
    var translated =
        transaction
            .translations(
                domain, language, originals.values().stream().map(Original::logRecord).toList())
            .keySet();
    var pending = new LinkedHashMap<String, String>();
    var triggers = new ArrayList<String>();
    originals.forEach(
        (urn, original) -> {
          var logRecord = original.logRecord();
          if (translated.contains(logRecord) || original.texts().isEmpty()) {
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
   * The text of some records a read returns, each with its translation into a language or a pending
   * mark, by the URN the read names it by. Records without translatable text are left out, and
   * nothing is requested.
   */
  static Map<String, LanguageRead.Text> texts(
      StoreTransaction transaction, String domain, String language, Collection<String> urns) {
    var withText = new LinkedHashMap<String, Original>();
    originalsOf(transaction, domain, urns)
        .forEach(
            (urn, original) -> {
              if (!original.texts().isEmpty()) {
                withText.put(urn, original);
              }
            });
    var translated =
        transaction.translations(
            domain, language, withText.values().stream().map(Original::logRecord).toList());
    var texts = new LinkedHashMap<String, LanguageRead.Text>();
    withText.forEach(
        (urn, original) -> {
          var translation = translated.get(original.logRecord());
          texts.put(
              urn,
              translation == null
                  ? new LanguageRead.Text.Pending(original.logRecord(), original.texts())
                  : new LanguageRead.Text.Translated(
                      original.logRecord(), original.texts(), translation));
        });
    return texts;
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
