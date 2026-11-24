/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.semantic;

import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.SemanticCatalogDomains;
import com.scalar.db.analytics.labs.application.construction.QueryEngineSqlParser;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.semantic.Answers;
import com.scalar.db.analytics.labs.semantic.CatalogSnapshot;
import com.scalar.db.analytics.labs.semantic.ConflictResolution;
import com.scalar.db.analytics.labs.semantic.FeedbackRegistration;
import com.scalar.db.analytics.labs.semantic.LanguageRead;
import com.scalar.db.analytics.labs.semantic.Point;
import com.scalar.db.analytics.labs.semantic.SemanticCatalogStore;
import com.scalar.db.analytics.labs.semantic.TranslationRun;
import com.scalar.db.analytics.labs.semantic.llm.LanguageModel;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Hierarchy;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.KnowledgeRequest;
import com.scalar.db.analytics.labs.semantic.model.Languages;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.RecordingLanguageChangedException;
import com.scalar.db.analytics.labs.semantic.model.Review;
import com.scalar.db.analytics.labs.semantic.model.ReviewPresentation;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.UsageFeedback;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Preset;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The Semantic Catalog's public reads and operations for the workspace's domain (see {@code
 * docs/application.md}, "Interfaces"). Each call opens the store, names the workspace's domain,
 * attaches the calling principal, and turns the Semantic Catalog's failures into classified
 * application failures. It holds no decision rules of its own.
 *
 * <p>Until authentication exists, the principal is the person running the process, and answers and
 * conflict resolutions carry {@link #UNAUTHENTICATED_LOCAL_USER} as their authority-check reference
 * (see {@code docs/application.md}, "Callers and authority").
 */
public final class SemanticCatalogService {
  /** The authority-check reference issued while callers are not authenticated. */
  public static final String UNAUTHENTICATED_LOCAL_USER = "unauthenticated-local-user";

  private final Workspace workspace;
  private final String principal;

  public SemanticCatalogService(Workspace workspace) {
    this(workspace, localPrincipal());
  }

  /**
   * The principal of every call until callers are authenticated: the person running the process
   * (see {@code docs/application.md}, "Callers and authority").
   */
  public static String localPrincipal() {
    return System.getProperty("user.name", "unknown");
  }

  SemanticCatalogService(Workspace workspace, String principal) {
    this.workspace = workspace;
    this.principal = principal;
  }

  /**
   * A read's result with the Catalog Snapshot it read and, when the read was made in a language,
   * the translations beside it.
   *
   * @param translations present exactly when the read was made in a language
   */
  public record Read<T>(String catalogSnapshot, T result, Optional<Translations> translations) {
    Read(String catalogSnapshot, T result) {
      this(catalogSnapshot, result, Optional.empty());
    }
  }

  /**
   * The translations a read in a language returns beside its unchanged result (see {@code
   * docs/semantic-catalog/languages.md}, "Reading in a language").
   *
   * @param language the language read in
   * @param recordingLanguage the language every original is written in
   * @param texts for each URN in the result that names a record with text, its originals with the
   *     translation or a pending mark; empty when the language read in is the recording language
   */
  public record Translations(
      String language, String recordingLanguage, Map<String, LanguageRead.Text> texts) {}

  /** The point a caller names: a Catalog Snapshot URN of this domain, or the latest commit. */
  public Point point(@Nullable String catalogSnapshot) {
    if (catalogSnapshot == null) {
      return Point.LATEST;
    }
    var domain = workspace.semanticCatalogDomain();
    try {
      if (Urns.kind(catalogSnapshot).equals(Urns.CATALOG_SNAPSHOT)
          && Urns.domain(catalogSnapshot).equals(domain)) {
        return Point.at(Long.parseLong(Urns.identifier(catalogSnapshot)));
      }
    } catch (IllegalArgumentException e) {
      // Reported below as invalid input.
    }
    throw new ApplicationFailure(
        "INVALID_REQUEST",
        "Not a Catalog Snapshot URN of the workspace's domain: " + catalogSnapshot);
  }

  /**
   * Reads along the hierarchy from a position.
   *
   * @param language the language to read in; originals only when absent
   */
  public Read<Hierarchy.Level> read(
      Point point,
      Review.Position position,
      Hierarchy.Depth depth,
      Knowledge.View view,
      @Nullable String language) {
    return snapshot(point, language, snapshot -> snapshot.read(position, depth, view));
  }

  /** Finds the elements whose existence is selected at a point. */
  public Read<List<Knowledge.Element>> elements(
      Point point, @Nullable ElementKind kind, @Nullable String parent) {
    return snapshot(point, snapshot -> snapshot.findElements(kind, parent));
  }

  /**
   * Finds the elements that business terms with a written form refer to at a point, matching the
   * names of one declared language, or of every declared language when none is named.
   */
  public Read<List<Knowledge.NamedElement>> elements(
      Point point,
      @Nullable ElementKind kind,
      @Nullable String parent,
      String writtenForm,
      @Nullable String language) {
    if (language != null) {
      requireDeclared(language);
    }
    return snapshot(point, snapshot -> snapshot.findElements(kind, parent, writtenForm, language));
  }

  /**
   * Requests knowledge at a point.
   *
   * @param language the language to read in; originals only when absent
   */
  public Read<List<Knowledge.Answer>> knowledge(
      Point point,
      List<KnowledgeRequest> requests,
      Knowledge.View view,
      @Nullable String language) {
    return snapshot(point, language, snapshot -> snapshot.request(requests, view));
  }

  /**
   * Retrieves a Fact Revision's content, by its URN, or by a Fact URN as the Fact's latest revision
   * at a point.
   *
   * @param language the language to read in; originals only when absent
   */
  public Read<Knowledge.RevisionContent> fact(Point point, String urn, @Nullable String language) {
    return snapshot(
        point,
        language,
        snapshot -> {
          String revision;
          try {
            revision =
                Urns.kind(urn).equals(Urns.FACT)
                    ? snapshot
                        .latestRevision(urn)
                        .orElseThrow(() -> notFound("No Fact " + urn + " at this point"))
                        .urn()
                    : urn;
          } catch (IllegalArgumentException e) {
            throw invalid("Not a Fact or Fact Revision URN: " + urn);
          }
          return snapshot
              .revisionContent(revision)
              .orElseThrow(() -> notFound("No Fact Revision " + urn + " at this point"));
        });
  }

  /**
   * Retrieves the review list below a position.
   *
   * @param language the language to read in; originals only when absent
   */
  public Read<List<Review.Group>> review(
      Point point, Review.Query query, @Nullable String language) {
    return snapshot(point, language, snapshot -> snapshot.reviewList(query));
  }

  /**
   * Registers a review presentation of Facts to a person, presented now. Each Fact is presented at
   * the Fact Address it belongs to.
   *
   * @param recipient the person asked; the calling principal when absent
   */
  public long present(
      ReviewPresentation.Kind kind,
      List<String> facts,
      @Nullable String recipient,
      String context) {
    return store(
        store -> {
          var targets =
              store.read(
                  domain(),
                  Point.LATEST,
                  snapshot ->
                      facts.stream()
                          .map(
                              fact ->
                                  new ReviewPresentation.Target(fact, factAddress(snapshot, fact)))
                          .toList());
          var presentation =
              construct(
                  () ->
                      new ReviewPresentation(
                          kind,
                          targets,
                          recipient == null ? principal : recipient,
                          Instant.now().toString(),
                          context,
                          List.of()));
          return store.registerReviewPresentation(domain(), presentation);
        });
  }

  private static String factAddress(CatalogSnapshot snapshot, String fact) {
    try {
      return snapshot
          .latestRevision(fact)
          .orElseThrow(() -> notFound("No Fact " + fact))
          .factAddress();
    } catch (IllegalArgumentException e) {
      throw invalid("Not a Fact URN: " + fact);
    }
  }

  /** Submits the calling principal's answers in one commit. */
  public Answers.Outcomes answer(List<Answers.Answer> answers) {
    var submission =
        construct(() -> new Answers.Submission(principal, UNAUTHENTICATED_LOCAL_USER, answers));
    return store(store -> store.submitAnswers(domain(), submission));
  }

  /** Resolves a conflict as the calling principal, returning the Catalog Snapshot written. */
  public String select(
      String factAddress,
      String chosen,
      List<String> passedOver,
      String wording,
      @Nullable Long reviewPresentation) {
    var resolution =
        construct(
            () ->
                new ConflictResolution(
                    factAddress,
                    chosen,
                    passedOver,
                    principal,
                    UNAUTHENTICATED_LOCAL_USER,
                    wording,
                    reviewPresentation));
    return store(
        store ->
            Urns.catalogSnapshot(
                domain(), store.resolveConflict(domain(), resolution).commit().orElseThrow()));
  }

  /** Registers usage feedback as the calling principal. */
  public FeedbackRegistration feedback(UsageFeedback feedback) {
    return store(store -> store.registerUsageFeedback(domain(), principal, feedback));
  }

  /** The domain's build tasks, oldest first, or one task by number. */
  public List<BuildTask> tasks(@Nullable Long task) {
    return store(
        store ->
            task == null
                ? store.buildTasks(domain())
                : List.of(
                    store
                        .buildTask(domain(), task)
                        .orElseThrow(() -> notFound("No build task " + task))));
  }

  /** Returns a failed build task to {@code created}, so the next run runs it. */
  public BuildTask resume(long task) {
    return store(store -> store.resumeBuildTask(domain(), task));
  }

  /**
   * Translates the domain's translation queue with the language model the workspace configuration
   * names (see {@code docs/semantic-catalog/languages.md}, "Translations"), waiting while another
   * process translates it. It runs apart from build tasks, so a running build does not delay it.
   *
   * @throws ApplicationFailure when the workspace configuration names no language model
   */
  public TranslationRun translate() {
    var settings =
        workspace
            .languageModelSettings()
            .orElseThrow(
                () ->
                    new ApplicationFailure(
                        "LANGUAGE_MODEL_NOT_CONFIGURED",
                        "Translations need a language model; set semanticCatalog.languageModel in"
                            + " the workspace configuration."));
    var model = LanguageModel.open(settings);
    return store(store -> store.runTranslations(domain(), model));
  }

  /** Checks a user policy version the calling principal proposes, at the latest point. */
  public Review.PolicyCheck checkPolicy(Preset preset, boolean countsDeclaredUnenforced) {
    var proposed = new LogRecord.PolicyVersion(preset, countsDeclaredUnenforced, principal, "");
    return store(store -> store.checkPolicyVersion(domain(), proposed));
  }

  /** Sets a user policy version as the calling principal. */
  public SemanticCatalogStore.PolicyVersionSet setPolicy(
      Preset preset, boolean countsDeclaredUnenforced, String reason) {
    var version = new LogRecord.PolicyVersion(preset, countsDeclaredUnenforced, principal, reason);
    return store(store -> store.setPolicyVersion(domain(), version));
  }

  /**
   * The domain's declared languages and its primary one, which is its recording language. Reading
   * them checks the recording language, as every other call does.
   */
  public Languages languages() {
    return store(store -> store.languages(domain()));
  }

  private String domain() {
    return workspace.semanticCatalogDomain();
  }

  /**
   * Reads at a point in a language: the result keeps its originals, and the translations come
   * beside it. A read in a language other than the recording one requests the pending translations.
   */
  private <T> Read<T> snapshot(
      Point point, @Nullable String language, Function<CatalogSnapshot, T> reader) {
    if (language == null) {
      return snapshot(point, reader);
    }
    requireDeclared(language);
    // The reader returns the bare result, which the store collects the text-bearing URNs from, so
    // the Catalog Snapshot it read is carried out beside it.
    var catalogSnapshot = new AtomicReference<String>();
    LanguageRead<T> read =
        store(
            store ->
                store.read(
                    domain(),
                    point,
                    language,
                    snapshot -> {
                      catalogSnapshot.set(snapshot.urn());
                      return reader.apply(snapshot);
                    }));
    return new Read<>(
        Objects.requireNonNull(catalogSnapshot.get()),
        read.result(),
        Optional.of(new Translations(read.language(), read.recordingLanguage(), read.texts())));
  }

  /** Rejects a language the workspace configuration does not declare for the domain. */
  private void requireDeclared(String language) {
    var languages = workspace.semanticCatalogLanguages();
    if (!languages.declares(language)) {
      throw invalid(
          "The domain does not declare the language "
              + language
              + "; its languages are "
              + String.join(", ", languages.declared()));
    }
  }

  private <T> Read<T> snapshot(Point point, Function<CatalogSnapshot, T> reader) {
    return store(
        store ->
            store.read(
                domain(), point, snapshot -> new Read<>(snapshot.urn(), reader.apply(snapshot))));
  }

  private <T> T store(Function<SemanticCatalogStore, T> call) {
    try (var store =
        SemanticCatalogStore.open(
            workspace.semanticCatalogSettings(),
            QueryEngineSqlParser.INSTANCE,
            ignored -> workspace.semanticCatalogLanguages())) {
      SemanticCatalogDomains.ensure(store, domain());
      return call.apply(store);
    } catch (RecordingLanguageChangedException e) {
      throw new ApplicationFailure(
          "RECORDING_LANGUAGE_CHANGED", Objects.requireNonNull(e.getMessage()));
    } catch (SemanticCatalogException e) {
      throw classify(e);
    } catch (IllegalArgumentException e) {
      throw invalid(Optional.ofNullable(e.getMessage()).orElse("The request is invalid."));
    } catch (RuntimeException e) {
      // Storage failures inside a transaction surface unwrapped, carrying the driver's exception.
      if (causedBySql(e)) {
        throw unavailable();
      }
      throw e;
    }
  }

  private static <T> T construct(Supplier<T> constructor) {
    try {
      return constructor.get();
    } catch (IllegalArgumentException | NullPointerException e) {
      throw invalid(Optional.ofNullable(e.getMessage()).orElse("The request is invalid."));
    }
  }

  /**
   * Classifies a Semantic Catalog failure. A failure to reach storage says only that; any other
   * failure is the Semantic Catalog refusing the request, whose reason names records, not secrets.
   */
  private static ApplicationFailure classify(SemanticCatalogException exception) {
    if (causedBySql(exception)) {
      return unavailable();
    }
    return new ApplicationFailure(
        "SEMANTIC_CATALOG_REJECTED",
        Optional.ofNullable(exception.getMessage())
            .orElse("The Semantic Catalog refused the request."));
  }

  private static boolean causedBySql(Throwable exception) {
    for (var cause = exception.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException) {
        return true;
      }
    }
    return false;
  }

  private static ApplicationFailure unavailable() {
    return new ApplicationFailure(
        "SEMANTIC_CATALOG_UNAVAILABLE", "The Semantic Catalog store could not be read or written.");
  }

  private static ApplicationFailure invalid(String message) {
    return new ApplicationFailure("INVALID_REQUEST", message);
  }

  private static ApplicationFailure notFound(String message) {
    return new ApplicationFailure("NOT_FOUND", message);
  }
}
