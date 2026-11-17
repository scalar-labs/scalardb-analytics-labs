/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Document;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecordJson;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Review;
import com.scalar.db.analytics.labs.semantic.model.ReviewPresentation;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.UsageFeedback;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.InputKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.SessionPurpose;
import com.scalar.db.analytics.labs.semantic.projection.CommitResolver;
import com.scalar.db.analytics.labs.semantic.projection.ExpressionValues;
import com.scalar.db.analytics.labs.semantic.projection.FactRecord;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow;
import com.scalar.db.analytics.labs.semantic.projection.PositionedRecord;
import com.scalar.db.analytics.labs.semantic.projection.Projection;
import com.scalar.db.analytics.labs.semantic.projection.ProjectionSource;
import com.scalar.db.analytics.labs.semantic.projection.Projector;
import com.scalar.db.analytics.labs.semantic.projection.TransitionResolver;
import com.scalar.db.analytics.labs.semantic.storage.JdbcStore;
import com.scalar.db.analytics.labs.semantic.storage.StoreTransaction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;

/**
 * The store of the Semantic Catalog and the internal operations on it (see {@code
 * docs/semantic-catalog/storage.md}).
 *
 * <p>Opening the store prepares it for use: it checks that the store is PostgreSQL and applies
 * pending schema changes. There is no separate migration step.
 *
 * <p>Every operation runs in one {@code REPEATABLE READ} transaction and judges only what that
 * transaction read; nothing is cached in the process across transactions. Several processes may
 * open the same store at once. A commit is numbered one past the last commit in the writer's
 * snapshot, so two overlapping writers to one domain collide on that number and the later one runs
 * again from the start on top of the earlier one's commit.
 *
 * <p>A store holds a connection pool and must be {@linkplain #close() closed}.
 */
public final class SemanticCatalogStore implements AutoCloseable {
  private final JdbcStore store;
  private final ExpressionValues expressions;

  private SemanticCatalogStore(JdbcStore store, SqlParser parser) {
    this.store = store;
    this.expressions = new ExpressionValues(parser::parse);
  }

  /**
   * Opens the Semantic Catalog's own connection pool to the store and prepares the {@code
   * semantic_catalog} schema.
   *
   * @param parser the Query Engine's parser, with which expression values are parsed at acceptance
   * @throws SemanticCatalogException when the store cannot be reached or is not PostgreSQL; the
   *     message names the product found
   */
  public static SemanticCatalogStore open(StoreSettings settings, SqlParser parser) {
    Objects.requireNonNull(parser, "parser");
    return new SemanticCatalogStore(
        JdbcStore.open(
            settings.jdbcUrl(),
            settings.username(),
            settings.password(),
            settings.maximumPoolSize()),
        parser);
  }

  /**
   * Creates a domain under a caller-supplied domain identifier. A domain is the unit of management
   * that assigns one responsible party to a body of data and its semantics, and every other record
   * belongs to exactly one domain. Its first commit records the {@linkplain
   * LogRecord.PolicyVersion#DEFAULT default user policy version}.
   *
   * @param domain the domain identifier, in the form {@link Urns#DOMAIN} requires
   * @throws IllegalArgumentException when the identifier does not have that form
   * @throws SemanticCatalogException when the domain already exists
   */
  public void createDomain(String domain) {
    Urns.requireDomain(domain);
    try {
      store.writeOnce(
          transaction -> {
            if (transaction.domainExists(domain)) {
              throw new SemanticCatalogException("Domain " + domain + " already exists");
            }
            transaction.insertDomain(domain);
            var initial = CommitRequest.builder("create-domain", "semantic-catalog");
            initial.add(LogRecord.PolicyVersion.DEFAULT);
            commit(transaction, expressions, domain, initial.build(), true);
            return Boolean.TRUE;
          });
    } catch (DuplicateKeyException e) {
      // A concurrent caller created the same domain after this transaction's check.
      throw new SemanticCatalogException("Domain " + domain + " already exists", e);
    }
  }

  /** Whether a domain exists. */
  public boolean domainExists(String domain) {
    return store.read(transaction -> transaction.domainExists(domain));
  }

  /**
   * Writes a request's records to a domain's log as one commit, together with the transitions they
   * cause and the materialized records they change (see {@code docs/semantic-catalog/storage.md},
   * "Writes and reads", and {@code docs/semantic-catalog/construction.md}, "Commits and cascades").
   *
   * <p>In one transaction it numbers the commit one past the last commit in its snapshot, replaces
   * temporary numbers with URNs, drops proposals that are the same as existing records, validates
   * and applies the remaining records, resolves the policy evaluations, confirmations, rejections,
   * and retirements they cause, and writes the commit, its log records, and the changed
   * materialized rows. When every record is the same as an existing one, nothing is written.
   *
   * @return the commit written, the URN each temporary number stands for, and the transitions
   * @throws SemanticCatalogException when the domain does not exist
   * @throws com.scalar.db.analytics.labs.semantic.model.CommitRejectedException when a record fails
   *     validation; nothing is written then
   */
  public CommitResult commit(String domain, CommitRequest request) {
    return store.write(transaction -> commit(transaction, expressions, domain, request, true));
  }

  /**
   * Writes a request's records as one commit exactly as given, without resolving the transitions
   * they cause, for tests of the log that write judgements by hand.
   */
  CommitResult commitAsWritten(String domain, CommitRequest request) {
    return store.write(transaction -> commit(transaction, expressions, domain, request, false));
  }

  /**
   * Creates a run of a Generator, or adds a trigger to its created run, as a commit calling for it
   * would, for tests that run a Generator again on unchanged input.
   *
   * @return the number of the task
   */
  long requestGeneratorRun(String domain, String generator, String trigger) {
    return store.write(
        transaction ->
            BuildTasks.trigger(
                transaction, domain, new BuildTask.Work.GeneratorRun(generator), trigger));
  }

  static CommitResult commit(
      StoreTransaction transaction,
      ExpressionValues expressions,
      String domain,
      CommitRequest request,
      boolean transitions) {
    requireDomain(transaction, domain);
    var commit = transaction.lastCommit(domain) + 1;
    var projection = new Projection(domain, transaction.projectionSource(domain));
    var resolution =
        CommitResolver.resolve(
            domain,
            commit,
            request.records(),
            projection,
            transaction.identityLookup(domain),
            expressions);
    if (resolution.records().isEmpty()) {
      return new CommitResult(OptionalLong.empty(), resolution.urns(), Transitions.NONE, List.of());
    }
    // Inserted first, so a concurrent writer of the same number collides as early as possible.
    transaction.insertCommit(domain, commit, request.operation(), request.author());
    var projector = Projector.validating(projection);
    projector.begin(commit);
    resolution.records().forEach(projector::apply);
    var records = new ArrayList<>(resolution.records());
    var outcome =
        transitions
            ? TransitionResolver.resolve(projection, projector, commit, resolution.records())
            : TransitionResolver.Outcome.NONE;
    records.addAll(outcome.records());
    projector.end();
    return finish(transaction, domain, commit, projection, records, outcome, resolution.urns(), "");
  }

  /**
   * Ends a commit: writes its records and changed rows, and creates the build tasks its transitions
   * call for. A commit that recorded nothing is removed again, and its tasks are triggered by the
   * given record instead of its last one.
   */
  private static CommitResult finish(
      StoreTransaction transaction,
      String domain,
      long commit,
      Projection projection,
      List<PositionedRecord> records,
      TransitionResolver.Outcome outcome,
      java.util.Map<Integer, String> urns,
      String triggerWithoutCommit) {
    var moved =
        new Transitions(
            outcome.confirmed(), outcome.rejected(), outcome.retired(), outcome.awaitingModel());
    if (records.isEmpty()) {
      transaction.deleteCommit(domain, commit);
      var tasks =
          BuildTasks.evaluationsFor(
              transaction, domain, outcome.awaitingModel(), triggerWithoutCommit);
      return new CommitResult(OptionalLong.empty(), urns, moved, tasks);
    }
    persist(transaction, domain, projection, records);
    var tasks =
        new ArrayList<>(
            BuildTasks.evaluationsFor(
                transaction,
                domain,
                outcome.awaitingModel(),
                Urns.logRecord(domain, records.getLast().position())));
    BuildTasks.generatorRunsFor(transaction, domain, projection, records).stream()
        .filter(task -> !tasks.contains(task))
        .forEach(tasks::add);
    return new CommitResult(OptionalLong.of(commit), urns, moved, tasks);
  }

  /** Writes a commit's log records and the materialized rows its projection changed. */
  private static void persist(
      StoreTransaction transaction,
      String domain,
      Projection projection,
      List<PositionedRecord> records) {
    for (var record : records) {
      transaction.insertLogRecord(
          domain,
          record,
          targetFact(record),
          targetInputResource(domain, record, projection),
          targetFactAddress(record));
      var presentation = presentationAnswered(record.record());
      if (presentation != null) {
        transaction.addPresentationEvent(
            domain, presentation, Urns.logRecord(domain, record.position()));
      }
    }
    for (var row : projection.changedRows()) {
      transaction.writeRow(domain, row);
    }
    for (var row : projection.removedRows()) {
      transaction.deleteRow(domain, row);
    }
  }

  /** Resolves transitions in a commit with no request records. */
  @FunctionalInterface
  interface Resolution {
    TransitionResolver.Outcome resolve(Projection projection, Projector projector, long commit);
  }

  /**
   * Writes a commit whose records transition resolution alone produces, such as a policy
   * re-evaluation, or nothing when it produces none. The Facts it leaves to the policy evaluation
   * model get evaluation tasks, triggered by the commit's last record or, when nothing was written,
   * by the given trigger.
   */
  static CommitResult commitResolved(
      StoreTransaction transaction,
      String domain,
      String operation,
      String author,
      String triggerWithoutCommit,
      Resolution resolution) {
    var commit = transaction.lastCommit(domain) + 1;
    // Inserted first, so a concurrent writer of the same number collides before the resolution
    // does its work; removed again if the resolution records nothing.
    transaction.insertCommit(domain, commit, operation, author);
    var projection = new Projection(domain, transaction.projectionSource(domain));
    var projector = Projector.validating(projection);
    projector.begin(commit);
    var outcome = resolution.resolve(projection, projector, commit);
    projector.end();
    return finish(
        transaction,
        domain,
        commit,
        projection,
        outcome.records(),
        outcome,
        java.util.Map.of(),
        triggerWithoutCommit);
  }

  /** The review presentation an answer or a conflict resolution was given in, if any. */
  private static @Nullable Long presentationAnswered(LogRecord record) {
    var presentation =
        switch (record) {
          case LogRecord.Answer answer -> answer.reviewPresentation();
          case LogRecord.ResolveConflict resolution -> resolution.reviewPresentation();
          default -> null;
        };
    return presentation == null ? null : Long.valueOf(presentation);
  }

  // Registering observations

  /**
   * Registers one retrieved table's metadata as an observation of the table's physical schema (see
   * {@code docs/semantic-catalog/public-api.md}, "Registering an observation", and {@code
   * docs/semantic-catalog/construction.md}, "What starts construction").
   *
   * <p>A version no newer than the latest one received for the table records nothing. Otherwise the
   * table metadata and what else the connector derived become the version's derived record, and the
   * connector's source metadata becomes a new Input Observation of the table's physical-schema
   * Input Resource when it differs from the latest observation. The Input Resource is created with
   * the table's first observation. Registrations for one table run one at a time.
   *
   * @param author the person or agent behind the call, kept on the commit
   * @throws SemanticCatalogException when the domain does not exist; nothing is written then
   */
  public TableMetadataRegistration registerTableMetadata(
      String domain, String author, TableMetadataObservation observation) {
    var table = observation.tableIdentifier();
    var version = observation.version();
    var declared = observation.table().observed();
    return store.write(
        transaction -> {
          requireDomain(transaction, domain);
          var existing = transaction.lockTableResource(domain, table);
          var latest = existing.flatMap(resource -> optional(resource.latestVersion()));
          if (latest.isPresent() && version <= latest.get()) {
            return new TableMetadataRegistration.NotNewer(latest.get());
          }
          var previous =
              existing.flatMap(
                  resource ->
                      transaction.latestObservationRecord(domain, resource.inputResource()));
          if (existing.isPresent()
              && previous.isPresent()
              && previous.get().record() instanceof LogRecord.InputObservation held
              && held.content().equals(declared)) {
            var resource = existing.get().inputResource();
            recordVersion(transaction, domain, resource, observation);
            return new TableMetadataRegistration.DerivedRecordOnly(
                resource, Urns.of(domain, Urns.INPUT_OBSERVATION, previous.get().position()));
          }
          var request = CommitRequest.builder("register-observation", author);
          var resourceRef =
              request.add(
                  LogRecord.InputResource.physicalSchema(
                      table, observation.reference().orElse(null)));
          var observed = request.add(new LogRecord.InputObservation(resourceRef, null, declared));
          request.add(
              new LogRecord.ObservationEvent(
                  observed,
                  JsonNodeFactory.instance
                      .objectNode()
                      .put("tableIdentifier", table)
                      .put("tableMetadataVersion", version)));
          var written = commit(transaction, expressions, domain, request.build(), true);
          var resource = written.urn(resourceRef);
          recordVersion(transaction, domain, resource, observation);
          return new TableMetadataRegistration.Observed(
              resource,
              written.urn(observed),
              written.commit().orElseThrow(),
              existing.isEmpty(),
              written.buildTasks());
        });
  }

  /**
   * Registers a document the semantics owner wrote or changed as an observation of a document Input
   * Resource (see {@code docs/semantic-catalog/public-api.md}, "Registering an observation").
   *
   * <p>Without an Input Resource the document is new, and its Input Resource is created with its
   * first observation. With one, the text becomes a new observation of that document unless it
   * equals the latest one, which records nothing. Registrations of one document run one at a time.
   * A new observation creates or triggers a run of each Generator that reads documents, in the same
   * transaction; meaning inference also reads the domain's documents when it runs for its other
   * inputs.
   *
   * @param author the person or agent behind the call, kept on the commit
   * @param inputResource the URN of the document's Input Resource, or null for a new document
   * @throws SemanticCatalogException when the domain does not exist, or the Input Resource does not
   *     exist or is not a document; nothing is written then
   */
  public DocumentRegistration registerDocument(
      String domain, String author, @Nullable String inputResource, Document document) {
    return store.write(
        transaction -> {
          requireDomain(transaction, domain);
          Ref resourceRef;
          var request = CommitRequest.builder("register-observation", author);
          if (inputResource == null) {
            resourceRef = request.add(LogRecord.InputResource.document());
          } else {
            var kind = transaction.lockInputResource(domain, inputResource);
            if (kind.isEmpty() || kind.get() != InputKind.DOCUMENT) {
              throw new SemanticCatalogException(
                  "No document Input Resource " + inputResource + " in domain " + domain);
            }
            var previous = transaction.latestObservationRecord(domain, inputResource);
            if (previous.isPresent()
                && previous.get().record() instanceof LogRecord.InputObservation held
                && LogRecordJson.fromStored(held.content(), Document.class)
                    .text()
                    .equals(document.text())) {
              return new DocumentRegistration.Unchanged(
                  inputResource,
                  Urns.of(domain, Urns.INPUT_OBSERVATION, previous.get().position()));
            }
            resourceRef = Ref.urn(inputResource);
          }
          var observed = request.add(LogRecord.InputObservation.of(resourceRef, document));
          request.add(
              new LogRecord.ObservationEvent(
                  observed,
                  JsonNodeFactory.instance.objectNode().put("document", document.name())));
          var written = commit(transaction, expressions, domain, request.build(), true);
          return new DocumentRegistration.Observed(
              inputResource == null ? written.urn(resourceRef) : inputResource,
              written.urn(observed),
              written.commit().orElseThrow(),
              inputResource == null,
              written.buildTasks());
        });
  }

  private static void recordVersion(
      StoreTransaction transaction,
      String domain,
      String inputResource,
      TableMetadataObservation observation) {
    if (observation.table() instanceof TableMetadataObservation.Read read) {
      var schema = read.sourceMetadataSchema();
      transaction.insertSourceMetadataSchema(
          domain, schema.digest(), read.sourceMetadata().connector(), schema.schema());
    }
    transaction.insertDerivedRecord(
        domain, observation.tableIdentifier(), observation.version(), observation.derivedRecord());
    transaction.setLatestTableMetadataVersion(domain, inputResource, observation.version());
  }

  private static Optional<Long> optional(OptionalLong value) {
    return value.isPresent() ? Optional.of(value.getAsLong()) : Optional.empty();
  }

  /**
   * The derived record of one table metadata version: the Analytics types, the read time, the
   * retrieval information, and the content digest the connector derived.
   *
   * @throws SemanticCatalogException when the domain does not exist
   */
  public Optional<JsonNode> derivedRecord(String domain, String tableIdentifier, long version) {
    return store.read(
        transaction -> {
          requireDomain(transaction, domain);
          return transaction.derivedRecord(domain, tableIdentifier, version);
        });
  }

  /**
   * The content of an Input Observation, as it was recorded.
   *
   * @return the content, or empty when the URN names no Input Observation of the domain
   * @throws SemanticCatalogException when the domain does not exist
   */
  public Optional<JsonNode> observationContent(String domain, String inputObservation) {
    return store.read(
        transaction -> {
          requireDomain(transaction, domain);
          if (!Urns.kind(inputObservation).equals(Urns.INPUT_OBSERVATION)) {
            return Optional.empty();
          }
          return transaction
              .logRecordAt(domain, Urns.position(inputObservation))
              .map(PositionedRecord::record)
              .filter(LogRecord.InputObservation.class::isInstance)
              .map(record -> ((LogRecord.InputObservation) record).content());
        });
  }

  // Operations of the deciding party and the semantics owner

  /**
   * Submits one deciding party's answers in one commit (see {@code
   * docs/semantic-catalog/public-api.md}, "Submitting answers"). Answers are applied in dependency
   * order, and independent answers in submission order, so a premise and its dependents can be
   * consented to at once. A correction registers its wording as usage feedback related to the
   * answered Fact, in the same commit.
   *
   * @throws SemanticCatalogException when the domain, an answered Fact, or a review presentation
   *     does not exist, or the authority-check reference is missing; nothing is written then
   */
  public Answers.Outcomes submitAnswers(String domain, Answers.Submission submission) {
    if (submission.authorityCheck().isBlank()) {
      throw new SemanticCatalogException("An answer lacks an authority-check reference");
    }
    return store.write(
        transaction -> {
          requireDomain(transaction, domain);
          var answers = submission.answers();
          var openProposals = openRetirementProposals(transaction, domain, answers);
          var request = CommitRequest.builder("submit-answers", submission.decidedBy());
          var answerRefs = new HashMap<Integer, Ref>();
          var feedbackRefs = new HashMap<Integer, Ref>();
          for (var index : inDependencyOrder(transaction, domain, answers)) {
            var answer = answers.get(index);
            if (answer.reviewPresentation() != null
                && transaction.presentation(domain, answer.reviewPresentation()).isEmpty()) {
              throw new SemanticCatalogException(
                  "Unknown review presentation " + answer.reviewPresentation());
            }
            if (answer.type() == AnswerType.CORRECTION) {
              var resource = request.add(LogRecord.InputResource.usageFeedback());
              request.add(
                  LogRecord.InputObservation.of(
                      resource,
                      new UsageFeedback(
                          answer.wording(),
                          SessionPurpose.CONSTRUCTING_THE_CATALOG,
                          Objects.requireNonNull(answer.contextSummary()),
                          List.of(Ref.urn(answer.fact())))));
              feedbackRefs.put(index, resource);
            }
            answerRefs.put(
                index,
                request.add(
                    new LogRecord.Answer(
                        Ref.urn(answer.fact()),
                        answer.transition(),
                        answer.type(),
                        submission.decidedBy(),
                        submission.authorityCheck(),
                        answer.wording(),
                        answer.reviewPresentation() == null
                            ? null
                            : answer.reviewPresentation().toString())));
          }
          var result = commit(transaction, expressions, domain, request.build(), true);
          var commit = result.commit().orElseThrow();
          var outcomes = new ArrayList<Answers.Outcome>();
          var answered = new HashSet<String>();
          for (var index = 0; index < answers.size(); index++) {
            var answer = answers.get(index);
            answered.add(answer.fact());
            var answerUrn = result.urn(Objects.requireNonNull(answerRefs.get(index)));
            var feedback = feedbackRefs.get(index);
            outcomes.add(
                new Answers.Outcome(
                    answer.fact(),
                    answerUrn,
                    answerResult(
                        transaction,
                        domain,
                        answer,
                        answerUrn,
                        commit,
                        openProposals.contains(answer.fact())),
                    feedback == null ? null : result.urn(feedback)));
          }
          var moved = result.transitions();
          return new Answers.Outcomes(
              Urns.catalogSnapshot(domain, commit),
              outcomes,
              new Transitions(
                  without(moved.confirmed(), answered),
                  without(moved.rejected(), answered),
                  without(moved.retired(), answered),
                  moved.awaitingModel()),
              result.buildTasks());
        });
  }

  /**
   * Orders answers so that an answer to a Fact comes after the answers to the Facts it depends on,
   * keeping submission order otherwise. Dependencies form no cycle, so the order always exists.
   *
   * @return the indices of the answers in the order they are applied
   */
  private static List<Integer> inDependencyOrder(
      StoreTransaction transaction, String domain, List<Answers.Answer> answers) {
    var projection = new Projection(domain, transaction.projectionSource(domain));
    var answeredFacts = new HashSet<String>();
    for (var answer : answers) {
      if (projection.fact(answer.fact()).flatMap(slice -> slice.record().state()).isEmpty()) {
        throw new SemanticCatalogException("Unknown Fact " + answer.fact());
      }
      answeredFacts.add(answer.fact());
    }
    var ordered = new ArrayList<Integer>();
    var placed = new HashSet<String>();
    var remaining = new ArrayList<Integer>();
    for (var index = 0; index < answers.size(); index++) {
      remaining.add(index);
    }
    while (!remaining.isEmpty()) {
      var next =
          remaining.stream()
              .filter(
                  index ->
                      dependsOnAnswered(projection, answers.get(index).fact(), answeredFacts)
                          .stream()
                          .allMatch(placed::contains))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("Answered Facts depend on each other"));
      remaining.remove(next);
      ordered.add(next);
      // A Fact is placed once its last answer is placed.
      var fact = answers.get(next).fact();
      if (remaining.stream().noneMatch(index -> answers.get(index).fact().equals(fact))) {
        placed.add(fact);
      }
    }
    return ordered;
  }

  /** The answered Facts a Fact depends on, directly or through other Facts. */
  private static Set<String> dependsOnAnswered(
      Projection projection, String fact, Set<String> answered) {
    var found = new HashSet<String>();
    var visited = new HashSet<String>();
    var pending = new ArrayDeque<String>();
    pending.push(fact);
    while (!pending.isEmpty()) {
      var next = pending.pop();
      if (!visited.add(next)) {
        continue;
      }
      for (var dependency :
          projection.fact(next).map(slice -> slice.record().factDependencies()).orElse(Set.of())) {
        if (!dependency.equals(fact) && answered.contains(dependency)) {
          found.add(dependency);
        }
        pending.push(dependency);
      }
    }
    return found;
  }

  /** The answered Facts that have an open retirement proposal before the submission. */
  private static Set<String> openRetirementProposals(
      StoreTransaction transaction, String domain, List<Answers.Answer> answers) {
    var projection = new Projection(domain, transaction.projectionSource(domain));
    var open = new HashSet<String>();
    for (var answer : answers) {
      projection
          .fact(answer.fact())
          .filter(slice -> slice.record().openRetirementProposal().isPresent())
          .ifPresent(slice -> open.add(answer.fact()));
    }
    return open;
  }

  /**
   * What an answer caused, read from the Fact Record after the commit.
   *
   * @param proposalOpen whether the Fact had an open retirement proposal before the submission
   */
  private static Answers.Result answerResult(
      StoreTransaction transaction,
      String domain,
      Answers.Answer answer,
      String answerUrn,
      long commit,
      boolean proposalOpen) {
    var record =
        transaction
            .factRecord(domain, answer.fact())
            .map(
                row ->
                    FactRecord.restore(
                        LogRecordJson.fromStored(row.record(), FactRecord.Stored.class)))
            .orElseThrow();
    if (answer.type() == AnswerType.UNKNOWN) {
      return Answers.Result.UNKNOWN;
    }
    var restsOnAnswer =
        transaction.factRecords(domain, answer.fact(), commit).stream()
            .filter(positioned -> positioned.position().commit() == commit)
            .anyMatch(positioned -> restsOn(positioned.record(), answerUrn));
    return switch (answer.transition()) {
      case CONFIRM ->
          switch (answer.type()) {
            case CONSENT ->
                restsOnAnswer
                    ? Answers.Result.CONFIRMED
                    : record.is(FactState.CANDIDATE)
                        ? Answers.Result.UNCONFIRMED_DEPENDENCIES
                        : Answers.Result.ALREADY_JUDGED;
            case REJECTION, CORRECTION ->
                restsOnAnswer ? Answers.Result.REJECTED : Answers.Result.ALREADY_JUDGED;
            case UNKNOWN -> Answers.Result.UNKNOWN;
          };
      case RETIRE ->
          switch (answer.type()) {
            case CONSENT -> restsOnAnswer ? Answers.Result.RETIRED : Answers.Result.ALREADY_JUDGED;
            case REJECTION, CORRECTION ->
                proposalOpen && record.is(FactState.CONFIRMED)
                    ? Answers.Result.RETIREMENT_DECLINED
                    : Answers.Result.ALREADY_JUDGED;
            case UNKNOWN -> Answers.Result.UNKNOWN;
          };
    };
  }

  private static boolean restsOn(LogRecord record, String answerUrn) {
    return switch (record) {
      case LogRecord.Confirm confirm -> confirm.basis().urnValue().equals(answerUrn);
      case LogRecord.Reject rejection ->
          rejection.basis() instanceof LogRecord.Basis.Event event
              && event.logRecord().urnValue().equals(answerUrn);
      case LogRecord.Retire retirement ->
          retirement.basis() instanceof LogRecord.Basis.Event event
              && event.logRecord().urnValue().equals(answerUrn);
      default -> false;
    };
  }

  private static List<String> without(List<String> facts, Set<String> excluded) {
    return facts.stream().filter(fact -> !excluded.contains(fact)).toList();
  }

  /**
   * Resolves a conflict at a Fact Address by choosing one of its eligible Facts (see {@code
   * docs/semantic-catalog/public-api.md}, "Resolving a conflict").
   *
   * @return the commit written
   * @throws com.scalar.db.analytics.labs.semantic.model.CommitRejectedException when the chosen and
   *     passed-over Facts are not exactly the eligible Facts at the previous commit
   * @throws SemanticCatalogException when the authority-check reference is missing or the review
   *     presentation does not exist
   */
  public CommitResult resolveConflict(String domain, ConflictResolution resolution) {
    if (resolution.authorityCheck().isBlank()) {
      throw new SemanticCatalogException(
          "A conflict resolution lacks an authority-check reference");
    }
    return store.write(
        transaction -> {
          requireDomain(transaction, domain);
          if (resolution.reviewPresentation() != null
              && transaction.presentation(domain, resolution.reviewPresentation()).isEmpty()) {
            throw new SemanticCatalogException(
                "Unknown review presentation " + resolution.reviewPresentation());
          }
          var request = CommitRequest.builder("resolve-conflict", resolution.decidedBy());
          request.add(
              new LogRecord.ResolveConflict(
                  Ref.urn(resolution.factAddress()),
                  Ref.urn(resolution.chosen()),
                  resolution.passedOver().stream().map(Ref::urn).toList(),
                  resolution.decidedBy(),
                  resolution.authorityCheck(),
                  resolution.wording(),
                  resolution.reviewPresentation() == null
                      ? null
                      : resolution.reviewPresentation().toString()));
          return commit(transaction, expressions, domain, request.build(), true);
        });
  }

  /**
   * Sets a new user policy version (see {@code docs/semantic-catalog/public-api.md}, "Setting a
   * user policy version"). Evaluations from then on name it, and an evaluation made under an
   * earlier version no longer stands. The same transaction creates the re-evaluation build task, or
   * adds a trigger to the one not run yet.
   *
   * @return the version's log record and the re-evaluation task
   */
  public PolicyVersionSet setPolicyVersion(String domain, LogRecord.PolicyVersion version) {
    return store.write(
        transaction -> {
          requireDomain(transaction, domain);
          var request = CommitRequest.builder("set-policy-version", version.setBy());
          var ref = request.add(version);
          var urn = commit(transaction, expressions, domain, request.build(), true).urn(ref);
          var task =
              BuildTasks.trigger(transaction, domain, new BuildTask.Work.PolicyReEvaluation(), urn);
          return new PolicyVersionSet(urn, task);
        });
  }

  /**
   * What setting a user policy version wrote.
   *
   * @param logRecord the URN of the version's log record
   * @param reEvaluation the number of the re-evaluation build task
   */
  public record PolicyVersionSet(String logRecord, long reEvaluation) {}

  /**
   * Registers a review presentation, outside the log (see {@code
   * docs/semantic-catalog/public-api.md}, "Registering a review presentation"). Answers and
   * conflict resolutions given in it reference the number it returns.
   *
   * @throws SemanticCatalogException when the domain or a target Fact does not exist
   */
  public long registerReviewPresentation(String domain, ReviewPresentation presentation) {
    return store.write(
        transaction -> {
          requireDomain(transaction, domain);
          var projection = new Projection(domain, transaction.projectionSource(domain));
          for (var target : presentation.targets()) {
            var address = projection.factAddress(target.fact());
            if (address.isEmpty() || !address.get().equals(target.factAddress())) {
              throw new SemanticCatalogException(
                  "Unknown Fact " + target.fact() + " at " + target.factAddress());
            }
          }
          return transaction.insertPresentation(
              domain,
              new ReviewPresentation(
                  presentation.kind(),
                  presentation.targets(),
                  presentation.recipient(),
                  presentation.presentedAt(),
                  presentation.context(),
                  List.of()));
        });
  }

  /**
   * Checks a proposed user policy version against the latest point: which evaluable candidates
   * inline evaluation would adopt, and which the policy evaluation model would decide (see {@code
   * docs/semantic-catalog/public-api.md}, "Checking a user policy version"). A commit between this
   * read and setting the version can change the actual outcome.
   */
  public Review.PolicyCheck checkPolicyVersion(String domain, LogRecord.PolicyVersion proposed) {
    return read(domain, Point.LATEST, snapshot -> snapshot.policyCheck(proposed));
  }

  // Build tasks

  /**
   * Runs a domain's build tasks (see {@code docs/semantic-catalog/public-api.md}, "Running build
   * tasks and resuming a failed one"). It takes the domain's builder semaphore, returns every task
   * a stopped run left {@code running} to {@code created}, and runs created tasks one at a time,
   * oldest first, including those created while it runs, reporting each as it finishes. It returns
   * once no created task remains or, when asked to keep waiting, once stopped.
   *
   * @throws com.scalar.db.analytics.labs.semantic.model.BuildRunInProgressException when another
   *     process runs the domain's build tasks
   * @throws SemanticCatalogException when the domain does not exist
   */
  public BuildRun.Summary runBuildTasks(
      String domain,
      BuildRun.Environment environment,
      BuildRun.Mode mode,
      BuildRun.Listener listener) {
    if (!domainExists(domain)) {
      throw new SemanticCatalogException("Unknown domain " + domain);
    }
    return new BuildRunner(store, expressions, domain, environment).run(mode, listener);
  }

  /**
   * Resumes a failed build task: returns it to {@code created}, so the next run runs it on the
   * latest state. Nothing is written to the log.
   *
   * @throws SemanticCatalogException when the domain or the task does not exist, or the task is not
   *     {@code failed}
   */
  public BuildTask resumeBuildTask(String domain, long task) {
    return store.write(
        transaction -> {
          requireDomain(transaction, domain);
          var current =
              transaction
                  .buildTask(domain, task)
                  .orElseThrow(() -> new SemanticCatalogException("Unknown build task " + task));
          if (!(current.status() instanceof BuildTask.Status.Failed)) {
            throw new SemanticCatalogException(
                "Build task "
                    + task
                    + " is "
                    + current.status().state().value()
                    + "; only a failed task is resumed");
          }
          var resumed = BuildRunner.withStatus(current, new BuildTask.Status.Created());
          transaction.updateBuildTask(domain, resumed, BuildTask.State.FAILED);
          return resumed;
        });
  }

  /** A build task by number. */
  public Optional<BuildTask> buildTask(String domain, long task) {
    return store.read(transaction -> transaction.buildTask(domain, task));
  }

  /** Every build task of a domain, oldest first. */
  public List<BuildTask> buildTasks(String domain) {
    return store.read(
        transaction -> {
          requireDomain(transaction, domain);
          return transaction.buildTasks(domain);
        });
  }

  /** A review presentation by the number its registration returned. */
  public Optional<ReviewPresentation> reviewPresentation(String domain, long presentation) {
    return store.read(transaction -> transaction.presentation(domain, presentation));
  }

  private static @Nullable String targetFact(PositionedRecord positioned) {
    return switch (positioned.record()) {
      case LogRecord.Fact fact -> fact.issuedUrn();
      case LogRecord.FactEvent event -> event.fact().urnValue();
      default -> null;
    };
  }

  private static @Nullable String targetFactAddress(PositionedRecord positioned) {
    return positioned.record() instanceof LogRecord.ResolveConflict resolution
        ? resolution.factAddress().urnValue()
        : null;
  }

  private static @Nullable String targetInputResource(
      String domain, PositionedRecord positioned, Projection projection) {
    return switch (positioned.record()) {
      case LogRecord.InputResource ignored ->
          Urns.of(domain, Urns.INPUT_RESOURCE, positioned.position());
      case LogRecord.InputObservation observation -> observation.inputResource().urnValue();
      case LogRecord.ObservationEvent event ->
          projection.observationResource(event.observation().urnValue()).orElse(null);
      default -> null;
    };
  }

  /**
   * Reads the Catalog Snapshot of a domain at a point. The reader runs in one read-only {@code
   * REPEATABLE READ} transaction, so everything it reads belongs to one snapshot of the store and
   * does not change if a commit lands meanwhile. The snapshot is valid only inside the reader.
   *
   * @throws SemanticCatalogException when the domain does not exist, or the point names a commit
   *     not written yet
   */
  public <T> T read(String domain, Point point, Function<CatalogSnapshot, T> reader) {
    return store.read(
        transaction -> {
          requireDomain(transaction, domain);
          var last = transaction.lastCommit(domain);
          var commit =
              switch (point) {
                case Point.Latest ignored -> last;
                case Point.At at -> {
                  if (at.commit() > last) {
                    throw new SemanticCatalogException(
                        "Domain " + domain + " has no commit " + at.commit() + " yet");
                  }
                  yield at.commit();
                }
              };
          return reader.apply(new CatalogSnapshot(transaction, domain, commit));
        });
  }

  /**
   * Runs the replay check: replays a domain's whole log from empty and compares the result, URNs
   * included, with the materialized records (see {@code docs/semantic-catalog/storage.md}, "Replay
   * check"). It takes time proportional to the log, so it is not run at startup.
   *
   * @throws SemanticCatalogException when the domain does not exist
   */
  public ReplayCheck replayCheck(String domain) {
    return store.read(
        transaction -> {
          requireDomain(transaction, domain);
          var projection = new Projection(domain, ProjectionSource.EMPTY);
          var projector = Projector.replaying(projection);
          var commit = 0L;
          for (var record : transaction.logRecords(domain)) {
            if (record.position().commit() != commit) {
              if (commit != 0) {
                projector.end();
              }
              commit = record.position().commit();
              projector.begin(commit);
            }
            projector.apply(record);
          }
          if (commit != 0) {
            projector.end();
          }
          var replayed = new HashSet<MaterializedRow>(projection.rows().toList());
          var stored = new HashSet<MaterializedRow>(transaction.rows(domain));
          // The latest version received is the one column not derived from the log; it is
          // compared against the derived records instead.
          var unexpected =
              new ArrayList<>(
                  stored.stream()
                      .filter(row -> !replayed.contains(row))
                      .map(Object::toString)
                      .toList());
          unexpected.addAll(transaction.derivedVersionMismatches(domain));
          return new ReplayCheck(
              commit,
              replayed.stream().filter(row -> !stored.contains(row)).map(Object::toString).toList(),
              unexpected);
        });
  }

  private static void requireDomain(StoreTransaction transaction, String domain) {
    if (!transaction.domainExists(domain)) {
      throw new SemanticCatalogException("Unknown domain " + domain);
    }
  }

  /** Closes the connection pool. */
  @Override
  public void close() {
    store.close();
  }
}
