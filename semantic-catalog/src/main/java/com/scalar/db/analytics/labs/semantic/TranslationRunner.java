/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.llm.LanguageModel;
import com.scalar.db.analytics.labs.semantic.llm.LanguageModelException;
import com.scalar.db.analytics.labs.semantic.model.Languages;
import com.scalar.db.analytics.labs.semantic.model.QueuedTranslation;
import com.scalar.db.analytics.labs.semantic.model.Translation;
import com.scalar.db.analytics.labs.semantic.projection.Projection;
import com.scalar.db.analytics.labs.semantic.storage.JdbcStore;
import com.scalar.db.analytics.labs.semantic.storage.StoreTransaction;
import com.scalar.db.analytics.labs.semantic.translation.Translator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Translates one domain's translation queue under its translator lock (see {@code
 * docs/semantic-catalog/languages.md}, "Translations"). It runs apart from the domain's build tasks
 * and their builder semaphore, because a translation changes no Fact's state and writes nothing to
 * the log.
 *
 * <p>Each queued translation is read in one transaction, translated by one model call outside any
 * transaction, and kept and removed from the queue in one more. A failure is recorded on the queued
 * translation, which stays queued for the next run. A stopped run leaves nothing to reset: what it
 * did not finish is still queued.
 */
final class TranslationRunner {
  private final JdbcStore store;
  private final String domain;
  private final Languages languages;
  private final LanguageModel model;

  TranslationRunner(JdbcStore store, String domain, Languages languages, LanguageModel model) {
    this.store = store;
    this.domain = domain;
    this.languages = languages;
    this.model = model;
  }

  /**
   * Translates the queue until each queued translation, including those queued while it runs, has
   * been tried once. Waits first while another process translates the domain's queue.
   */
  @SuppressWarnings("try") // The lock is held for the whole run and released on close.
  TranslationRun run() {
    try (var lock = store.lockTranslator(domain)) {
      var tried = new HashSet<Key>();
      var translated = new ArrayList<TranslationRun.Translated>();
      var failed = new ArrayList<TranslationRun.Failed>();
      while (true) {
        lock.requireHeld();
        var next = store.read(transaction -> untried(transaction, tried));
        if (next.isEmpty()) {
          return new TranslationRun(translated, failed);
        }
        for (var queued : next) {
          tried.add(queued.key());
          if (queued.text().isEmpty()) {
            store.write(
                transaction ->
                    transaction.dequeueTranslation(domain, queued.logRecord(), queued.language()));
            continue;
          }
          translate(queued)
              .ifPresentOrElse(
                  reason ->
                      failed.add(
                          new TranslationRun.Failed(queued.logRecord(), queued.language(), reason)),
                  () ->
                      translated.add(
                          new TranslationRun.Translated(queued.logRecord(), queued.language())));
        }
      }
    }
  }

  /** A queued translation by its log record and language. */
  private record Key(String logRecord, String language) {}

  /**
   * A queued translation with the text to translate; the text is empty when there is nothing to
   * translate any more: the record is already translated, the language is no longer declared or is
   * the recording language, or the record holds no translatable text.
   */
  private record Queued(String logRecord, String language, Map<String, String> text) {
    Key key() {
      return new Key(logRecord, language);
    }
  }

  private List<Queued> untried(StoreTransaction transaction, Set<Key> tried) {
    var queue =
        transaction.queuedTranslations(domain).stream()
            .filter(queued -> !tried.contains(new Key(queued.logRecord(), queued.language())))
            .toList();
    if (queue.isEmpty()) {
      return List.of();
    }
    var projection = new Projection(domain, transaction.projectionSource(domain));
    var byLanguage =
        queue.stream()
            .collect(
                Collectors.groupingBy(
                    QueuedTranslation::language,
                    Collectors.mapping(QueuedTranslation::logRecord, Collectors.toList())));
    var translated = new HashSet<Key>();
    byLanguage.forEach(
        (language, records) ->
            transaction
                .translations(domain, language, records)
                .keySet()
                .forEach(logRecord -> translated.add(new Key(logRecord, language))));
    var untried = new ArrayList<Queued>();
    for (var queued : queue) {
      var language = queued.language();
      var obsolete =
          !languages.declares(language)
              || language.equals(languages.primary())
              || translated.contains(new Key(queued.logRecord(), language));
      untried.add(
          new Queued(
              queued.logRecord(),
              language,
              obsolete
                  ? Map.of()
                  : Translations.textOf(transaction, domain, projection, queued.logRecord())));
    }
    return untried;
  }

  /**
   * Translates one queued text and keeps the translation, removing it from the queue; when the
   * model is unavailable or its answer does not translate exactly the texts given, records the
   * failure on the queued translation instead.
   *
   * @return the failure reason, or empty when the translation was made
   */
  private Optional<String> translate(Queued queued) {
    var version = Translator.version(model);
    Translation translation;
    try {
      translation =
          new Translation(
              queued.logRecord(),
              queued.language(),
              Translator.read(
                  BuildRunner.answerObject(
                      model.complete(
                          Translator.INSTRUCTIONS,
                          Translator.input(languages.primary(), queued.language(), queued.text()))),
                  queued.text()),
              version);
    } catch (LanguageModelException e) {
      var reason = String.valueOf(e.getMessage());
      store.write(
          transaction ->
              transaction.failQueuedTranslation(
                  domain, queued.logRecord(), queued.language(), reason));
      return Optional.of(reason);
    }
    store.write(
        transaction -> {
          transaction.insertTranslation(domain, translation);
          return transaction.dequeueTranslation(domain, queued.logRecord(), queued.language());
        });
    return Optional.empty();
  }
}
