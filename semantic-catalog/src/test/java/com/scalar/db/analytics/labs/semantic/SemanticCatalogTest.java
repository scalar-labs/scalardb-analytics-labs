/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.SemanticCatalog.AnswerInput;
import com.scalar.db.analytics.labs.semantic.SemanticCatalog.Configuration;
import com.scalar.db.analytics.labs.semantic.SnapshotReads.Request;
import com.scalar.db.analytics.labs.semantic.generator.Generator;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult.Candidate;
import com.scalar.db.analytics.labs.semantic.generator.GeneratorResult.RetirementProposal;
import com.scalar.db.analytics.labs.semantic.generator.LanguageModel;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.DependencyRef;
import com.scalar.db.analytics.labs.semantic.model.ElementDescriptor;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Facts;
import com.scalar.db.analytics.labs.semantic.model.Json;
import com.scalar.db.analytics.labs.semantic.model.Predicates;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ObservationKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PresentationKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.RetirementReason;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Target;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SemanticCatalogTest {
  private static final String DOMAIN = "sales";
  private static final String OWNER = "owner";
  private static final String DATASET = Urns.dataset(DOMAIN, "shop.public.orders");
  private static final String STATUS = Urns.field(DATASET, "status");

  private final ScriptedGenerator generator = new ScriptedGenerator();
  private StorageSettings store;

  @BeforeEach
  void setUp() {
    store = TestStore.freshStore();
    SemanticCatalog.create(store, DOMAIN, OWNER, configuration()).close();
  }

  private Configuration configuration() {
    return new Configuration(
        List.of(generator), SqlExecutor.UNAVAILABLE, LanguageModel.UNAVAILABLE);
  }

  private SemanticCatalog open() {
    return SemanticCatalog.open(store, DOMAIN, configuration());
  }

  /** Registers a document and runs the build task it creates with the scripted candidates. */
  private Outputs.TaskRun build(SemanticCatalog catalog, List<Candidate> candidates) {
    return build(catalog, candidates, List.of());
  }

  private Outputs.TaskRun build(
      SemanticCatalog catalog, List<Candidate> candidates, List<RetirementProposal> retirements) {
    generator.next.add(new GeneratorResult(candidates, retirements, List.of()));
    var registered =
        catalog.registerObservation(
            OWNER,
            null,
            ObservationKind.DOCUMENT,
            null,
            null,
            Json.object().put("text", "run " + generator.next.size() + System.nanoTime()));
    assertThat(registered.effects().tasks()).hasSize(1);
    return catalog.runTask(registered.effects().tasks().getFirst());
  }

  @Test
  void deterministicCandidatesAreConfirmedByMachineJudgementInDependencyOrder() {
    try (var catalog = open()) {
      var run = build(catalog, List.of(datasetExists(), fieldExists()));

      assertThat(run.state()).isEqualTo(BuildTask.State.SUCCEEDED);
      assertThat(catalog.factState(datasetExistsFact())).isEqualTo(FactState.CONFIRMED);
      assertThat(catalog.factState(fieldExistsFact())).isEqualTo(FactState.CONFIRMED);
      var found = catalog.reads().findElements(null, ElementKind.FIELD, DATASET, null);
      assertThat(found.elements())
          .extracting(Outputs.ElementSummary::element)
          .containsExactly(STATUS);
    }
  }

  @Test
  void inferredCandidateWaitsForConsentAndIsConfirmedByTheAnswer() {
    try (var catalog = open()) {
      build(catalog, List.of(datasetExists(), fieldExists(), meaning("Order status code")));
      var meaningFact = meaningFact("Order status code");

      var queue = catalog.reads().reviewQueue(null, null, null, null);
      assertThat(queue.items())
          .extracting(Outputs.ReviewItem::kind)
          .containsExactly(PresentationKind.CONFIRMATION);
      var knowledge =
          catalog
              .reads()
              .requestKnowledge(
                  null, List.of(new Request(STATUS, null, null, null, Predicates.MEANING)), null);
      assertThat(knowledge.responses().getFirst().addresses().getFirst().status())
          .isEqualTo("awaiting-judgement");
      assertThat(knowledge.responses().getFirst().addresses().getFirst().awaiting())
          .isEqualTo("consent");

      var presentation =
          catalog.registerPresentation(
              PresentationKind.CONFIRMATION, List.of(meaningFact), OWNER, null, "chat", null);
      var submitted =
          catalog.submitAnswers(
              OWNER,
              "call-1",
              "authz:owner",
              List.of(
                  new AnswerInput(
                      meaningFact,
                      Target.CONFIRMATION,
                      AnswerType.CONSENT,
                      "Yes",
                      presentation.presentation())));

      assertThat(submitted.answers().getFirst().transition()).isEqualTo("confirmed");
      var after =
          catalog
              .reads()
              .requestKnowledge(
                  submitted.effects().snapshot(),
                  List.of(new Request(STATUS, null, null, null, Predicates.MEANING)),
                  null);
      var selected = after.responses().getFirst().addresses().getFirst().selected();
      assertThat(selected).isNotNull();
      assertThat(Objects.requireNonNull(selected).value().path("text").asText())
          .isEqualTo("Order status code");
      assertThat(selected.decidingParty()).isEqualTo(OWNER);
      assertThat(catalog.reads().reviewQueue(null, null, null, null).items()).isEmpty();
    }
  }

  @Test
  void aCandidateIsReviewedInTheSameRoundAsTheCandidateItDependsOn() {
    try (var catalog = open()) {
      var concept = Urns.node(DOMAIN, ElementKind.CONCEPT, "order");
      var conceptExists =
          Candidate.existence(
              ElementDescriptor.node(concept, ElementKind.CONCEPT, null, "order"),
              List.of(),
              List.of(inference()));
      var conceptExistsFact =
          Facts.fact(
              DOMAIN,
              concept,
              Predicates.EXISTS,
              Scope.UNRESTRICTED,
              Json.object().put("exists", true));
      var conceptMeaning =
          new Candidate(
              concept,
              null,
              Predicates.MEANING,
              Scope.UNRESTRICTED,
              Json.object().put("text", "An order a customer placed"),
              List.of(new DependencyRef.OnFact(conceptExistsFact)),
              List.of(inference()),
              List.of());
      var conceptMeaningFact =
          Facts.fact(
              DOMAIN,
              concept,
              Predicates.MEANING,
              Scope.UNRESTRICTED,
              Json.object().put("text", "An order a customer placed"));
      build(catalog, List.of(conceptExists, conceptMeaning));

      var queue = catalog.reads().reviewQueue(null, PresentationKind.CONFIRMATION, null, null);
      assertThat(queue.items())
          .extracting(item -> item.facts().getFirst().fact())
          .containsExactly(conceptExistsFact, conceptMeaningFact);

      var submitted =
          catalog.submitAnswers(
              OWNER,
              null,
              "authz:owner",
              List.of(
                  new AnswerInput(
                      conceptMeaningFact, Target.CONFIRMATION, AnswerType.CONSENT, "Yes", null),
                  new AnswerInput(
                      conceptExistsFact, Target.CONFIRMATION, AnswerType.CONSENT, "Yes", null)));

      assertThat(submitted.answers())
          .extracting(Outputs.AnswerResult::transition)
          .containsOnly("confirmed");
      assertThat(catalog.factState(conceptMeaningFact)).isEqualTo(FactState.CONFIRMED);
      assertThat(catalog.reads().reviewQueue(null, null, null, null).items()).isEmpty();
    }
  }

  @Test
  void resendingWithTheSameCallIdentifierWritesNothing() {
    try (var catalog = open()) {
      build(catalog, List.of(datasetExists(), fieldExists(), meaning("Order status code")));
      var answers =
          List.of(
              new AnswerInput(
                  meaningFact("Order status code"),
                  Target.CONFIRMATION,
                  AnswerType.CONSENT,
                  "Yes",
                  null));
      var first = catalog.submitAnswers(OWNER, "call-1", "authz:owner", answers);
      var commit = catalog.latestCommit();

      var second = catalog.submitAnswers(OWNER, "call-1", "authz:owner", answers);

      assertThat(catalog.latestCommit()).isEqualTo(commit);
      assertThat(second).isEqualTo(first);
      var different =
          List.of(
              new AnswerInput(
                  meaningFact("Order status code"),
                  Target.CONFIRMATION,
                  AnswerType.UNKNOWN,
                  "?",
                  null));
      assertThatThrownBy(() -> catalog.submitAnswers(OWNER, "call-1", "authz:owner", different))
          .isInstanceOf(SemanticCatalogException.class)
          .extracting(e -> ((SemanticCatalogException) e).kind())
          .isEqualTo(SemanticCatalogException.Kind.INVALID_INPUT);
    }
  }

  @Test
  void incompatibleFactsConsentedTogetherConflictUntilOneIsSelected() {
    try (var catalog = open()) {
      build(
          catalog,
          List.of(
              datasetExists(),
              fieldExists(),
              meaning("Order status code"),
              meaning("Shipping state")));
      var first = meaningFact("Order status code");
      var second = meaningFact("Shipping state");

      var submitted =
          catalog.submitAnswers(
              OWNER,
              null,
              "authz:owner",
              List.of(
                  new AnswerInput(first, Target.CONFIRMATION, AnswerType.CONSENT, "Yes", null),
                  new AnswerInput(
                      second, Target.CONFIRMATION, AnswerType.CONSENT, "Also yes", null)));

      var knowledge =
          catalog
              .reads()
              .requestKnowledge(
                  submitted.effects().snapshot(),
                  List.of(new Request(STATUS, null, null, null, Predicates.MEANING)),
                  null);
      var address = knowledge.responses().getFirst().addresses().getFirst();
      assertThat(address.status()).isEqualTo("conflict");
      assertThat(address.selected()).isNull();
      assertThat(catalog.reads().reviewQueue(null, PresentationKind.CONFLICT, null, null).items())
          .hasSize(1);

      var selection =
          catalog.selectInConflict(
              OWNER,
              null,
              "authz:owner",
              address.factAddress(),
              second,
              List.of(first),
              "The second is right",
              null);

      assertThat(selection.selected()).isEqualTo(second);
      var resolved =
          catalog
              .reads()
              .requestKnowledge(
                  null, List.of(new Request(STATUS, null, null, null, Predicates.MEANING)), null);
      assertThat(
              Objects.requireNonNull(
                      resolved.responses().getFirst().addresses().getFirst().selected())
                  .fact())
          .isEqualTo(second);
      // The earlier point still reads the conflict.
      var earlier =
          catalog
              .reads()
              .requestKnowledge(
                  submitted.effects().snapshot(),
                  List.of(new Request(STATUS, null, null, null, Predicates.MEANING)),
                  null);
      assertThat(earlier.responses().getFirst().addresses().getFirst().status())
          .isEqualTo("conflict");
    }
  }

  @Test
  void aFactThatTakesTheSelectedFactIntoAccountReplacesIt() {
    try (var catalog = open()) {
      build(catalog, List.of(datasetExists(), fieldExists(), meaning("Order status code")));
      var original = meaningFact("Order status code");
      catalog.submitAnswers(
          OWNER,
          null,
          "authz:owner",
          List.of(new AnswerInput(original, Target.CONFIRMATION, AnswerType.CONSENT, "Yes", null)));

      var revised =
          meaning(
              "Order fulfilment status code",
              List.of(
                  new DependencyRef.OnFact(fieldExistsFact()), new DependencyRef.OnFact(original)));
      build(catalog, List.of(revised));
      var replacement = meaningFact("Order fulfilment status code");
      catalog.submitAnswers(
          OWNER,
          null,
          "authz:owner",
          List.of(
              new AnswerInput(
                  replacement, Target.CONFIRMATION, AnswerType.CONSENT, "Better", null)));

      var knowledge =
          catalog
              .reads()
              .requestKnowledge(
                  null, List.of(new Request(STATUS, null, null, null, Predicates.MEANING)), null);
      var address = knowledge.responses().getFirst().addresses().getFirst();
      assertThat(address.status()).isNull();
      assertThat(Objects.requireNonNull(address.selected()).fact()).isEqualTo(replacement);
    }
  }

  @Test
  void rejectingAFactRejectsTheCandidatesThatDependOnIt() {
    try (var catalog = open()) {
      build(catalog, List.of(datasetExists(), fieldExists(), meaning("Order status code")));
      var meaning = meaningFact("Order status code");
      var unit =
          new Candidate(
              STATUS,
              null,
              Predicates.UNIT,
              Scope.UNRESTRICTED,
              Json.object().put("unit", "code"),
              List.of(new DependencyRef.OnFact(meaning)),
              List.of(inference()),
              List.of());
      build(catalog, List.of(unit));
      var unitFact =
          Facts.fact(
              DOMAIN,
              STATUS,
              Predicates.UNIT,
              Scope.UNRESTRICTED,
              Json.object().put("unit", "code"));
      assertThat(catalog.factState(unitFact)).isEqualTo(FactState.CANDIDATE);

      var submitted =
          catalog.submitAnswers(
              OWNER,
              null,
              "authz:owner",
              List.of(
                  new AnswerInput(meaning, Target.CONFIRMATION, AnswerType.REJECTION, "No", null)));

      assertThat(catalog.factState(unitFact)).isEqualTo(FactState.REJECTED);
      assertThat(submitted.cascaded())
          .extracting(Outputs.CascadedTransition::fact)
          .contains(unitFact);
      // Proposing the rejected Fact again without a new dependency reference records nothing.
      var again = build(catalog, List.of(meaning("Order status code")));
      assertThat(Objects.requireNonNull(again.result()).path("notAccepted")).hasSize(1);
    }
  }

  @Test
  void aRetirementSupportedByTheDeclarationRetiresTheFactAndItsDependents() {
    try (var catalog = open()) {
      build(catalog, List.of(datasetExists(), fieldExists(), meaning("Order status code")));
      catalog.submitAnswers(
          OWNER,
          null,
          "authz:owner",
          List.of(
              new AnswerInput(
                  meaningFact("Order status code"),
                  Target.CONFIRMATION,
                  AnswerType.CONSENT,
                  "Yes",
                  null)));

      build(
          catalog,
          List.of(),
          List.of(
              new RetirementProposal(
                  fieldExistsFact(),
                  RetirementReason.ELEMENT_GONE,
                  List.of(),
                  List.of(enforced()))));

      assertThat(catalog.factState(fieldExistsFact())).isEqualTo(FactState.RETIRED);
      assertThat(catalog.factState(meaningFact("Order status code"))).isEqualTo(FactState.RETIRED);
      assertThat(catalog.reads().findElements(null, ElementKind.FIELD, null, null).elements())
          .isEmpty();
    }
  }

  @Test
  void theMaterializedStateMatchesAReplayAndSurvivesReopening() {
    String snapshot;
    try (var catalog = open()) {
      build(
          catalog,
          List.of(
              datasetExists(),
              fieldExists(),
              meaning("Order status code"),
              meaning("Shipping state")));
      catalog.submitAnswers(
          OWNER,
          null,
          "authz:owner",
          List.of(
              new AnswerInput(
                  meaningFact("Order status code"),
                  Target.CONFIRMATION,
                  AnswerType.CONSENT,
                  "Yes",
                  null),
              new AnswerInput(
                  meaningFact("Shipping state"),
                  Target.CONFIRMATION,
                  AnswerType.CONSENT,
                  "Yes",
                  null)));
      snapshot = catalog.latestSnapshot();
      assertThat(catalog.verify().consistent()).isTrue();
    }
    try (var catalog = open()) {
      assertThat(catalog.latestSnapshot()).isEqualTo(snapshot);
      var verification = catalog.verify();
      assertThat(verification.differences()).isEmpty();
      var knowledge =
          catalog
              .reads()
              .requestKnowledge(
                  snapshot,
                  List.of(new Request(STATUS, null, null, null, Predicates.MEANING)),
                  null);
      assertThat(knowledge.responses().getFirst().addresses().getFirst().status())
          .isEqualTo("conflict");
    }
  }

  @Test
  void anotherProcessCanAnswerWhileAGeneratorRuns() {
    try (var builder = open();
        var reviewer = open()) {
      build(builder, List.of(datasetExists(), fieldExists(), meaning("Order status code")));
      var meaning = meaningFact("Order status code");
      var unitValue = Json.object().put("unit", "code");
      var unit =
          new Candidate(
              STATUS,
              null,
              Predicates.UNIT,
              Scope.UNRESTRICTED,
              unitValue,
              List.of(new DependencyRef.OnFact(meaning)),
              List.of(inference()),
              List.of());
      // While the builder's Generator runs, the reviewer, another instance on the same domain,
      // consents to the meaning the new candidate depends on.
      generator.during =
          () ->
              reviewer.submitAnswers(
                  OWNER,
                  null,
                  "authz:owner",
                  List.of(
                      new AnswerInput(
                          meaning, Target.CONFIRMATION, AnswerType.CONSENT, "Yes", null)));

      build(builder, List.of(unit));

      // The candidate was judged against the state that includes the reviewer's commit, so it is
      // evaluated and awaits consent, and both instances read the same catalog.
      var unitFact = Facts.fact(DOMAIN, STATUS, Predicates.UNIT, Scope.UNRESTRICTED, unitValue);
      var queue = reviewer.reads().reviewQueue(null, PresentationKind.CONFIRMATION, null, null);
      assertThat(queue.items())
          .extracting(item -> item.facts().getFirst().fact())
          .containsExactly(unitFact);
      assertThat(builder.verify().consistent()).isTrue();
      assertThat(reviewer.verify().consistent()).isTrue();
    }
  }

  @Test
  void aFailingGeneratorLeavesTheTaskFailedAndResumable() {
    try (var catalog = open()) {
      generator.failNext = true;
      var registered =
          catalog.registerObservation(
              OWNER, null, ObservationKind.DOCUMENT, null, null, Json.object().put("text", "doc"));
      var task = registered.effects().tasks().getFirst();

      var failed = catalog.runTask(task);

      assertThat(failed.state()).isEqualTo(BuildTask.State.FAILED);
      assertThat(failed.failureReason()).isEqualTo("scripted failure");
      generator.next.add(GeneratorResult.of(List.of(datasetExists())));
      var resumed = catalog.resumeTask(task);
      assertThat(resumed.state()).isEqualTo(BuildTask.State.SUCCEEDED);
      assertThat(catalog.factState(datasetExistsFact())).isEqualTo(FactState.CONFIRMED);
    }
  }

  // Fixtures

  private static Check enforced() {
    return new Check(
        Check.Method.DECLARED_ENFORCED,
        List.of(Predicates.VALUE_ITEM),
        Check.Result.SUPPORTED,
        null);
  }

  private static Check inference() {
    return new Check(
        Check.Method.INFERENCE, List.of(Predicates.VALUE_ITEM), Check.Result.SUPPORTED, null);
  }

  private static Candidate datasetExists() {
    return Candidate.existence(
        ElementDescriptor.node(DATASET, ElementKind.DATASET, null, "orders"),
        List.of(),
        List.of(enforced()));
  }

  private static String datasetExistsFact() {
    return Facts.fact(
        DOMAIN, DATASET, Predicates.EXISTS, Scope.UNRESTRICTED, Json.object().put("exists", true));
  }

  private static Candidate fieldExists() {
    return Candidate.existence(
        ElementDescriptor.node(STATUS, ElementKind.FIELD, DATASET, "status"),
        List.of(new DependencyRef.OnFact(datasetExistsFact())),
        List.of(enforced()));
  }

  private static String fieldExistsFact() {
    return Facts.fact(
        DOMAIN, STATUS, Predicates.EXISTS, Scope.UNRESTRICTED, Json.object().put("exists", true));
  }

  private static Candidate meaning(String text) {
    return meaning(text, List.of(new DependencyRef.OnFact(fieldExistsFact())));
  }

  private static Candidate meaning(String text, List<DependencyRef> dependencies) {
    return new Candidate(
        STATUS,
        null,
        Predicates.MEANING,
        Scope.UNRESTRICTED,
        Json.object().put("text", text),
        dependencies,
        List.of(inference()),
        List.of());
  }

  private static String meaningFact(String text) {
    return Facts.fact(
        DOMAIN, STATUS, Predicates.MEANING, Scope.UNRESTRICTED, Json.object().put("text", text));
  }

  /** A Generator that reads documents and returns scripted results in order. */
  private static final class ScriptedGenerator implements Generator {
    final Deque<GeneratorResult> next = new ArrayDeque<>();
    boolean failNext;
    Runnable during = () -> {};

    @Override
    public String name() {
      return "scripted";
    }

    @Override
    public String version() {
      return "1";
    }

    @Override
    public Set<String> inputs() {
      return Set.of(Generator.DOCUMENT);
    }

    @Override
    public GeneratorResult run(GeneratorContext context)
        throws com.scalar.db.analytics.labs.semantic.generator.GeneratorException {
      during.run();
      during = () -> {};
      if (failNext) {
        failNext = false;
        throw new com.scalar.db.analytics.labs.semantic.generator.GeneratorException(
            "scripted failure");
      }
      var result = next.poll();
      return result == null ? GeneratorResult.of(List.of()) : result;
    }
  }

  @SuppressWarnings("unused")
  private static JsonNode json(String text) {
    return Json.read(text);
  }
}
