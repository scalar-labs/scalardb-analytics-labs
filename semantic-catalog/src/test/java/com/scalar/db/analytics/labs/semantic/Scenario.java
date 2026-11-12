/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.FactRevision;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.KnowledgeRequest;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecord.Basis;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.PolicyResult;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.RetirementReason;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * One domain under test, with shorthands for the records a caller such as judgement would write.
 */
final class Scenario {
  static final JsonNode NOTHING = JsonNodeFactory.instance.objectNode();

  final SemanticCatalogStore store;
  final String domain;
  final Ref policy;

  Scenario(SemanticCatalogStore store) {
    this.store = store;
    this.domain = TestStore.newDomain();
    store.createDomain(domain);
    var request = CommitRequest.builder("set-policy", "owner");
    var version =
        request.add(
            new LogRecord.PolicyVersion(
                JsonNodeFactory.instance.objectNode().put("preset", "auto")));
    this.policy = store.commit(domain, request.build()).ref(version);
  }

  /** Starts building one commit. */
  Commit begin() {
    return new Commit(CommitRequest.builder("test", "tester"));
  }

  CommitResult write(Commit commit) {
    return store.commit(domain, commit.request.build());
  }

  <T> T latest(Function<CatalogSnapshot, T> read) {
    return store.read(domain, Point.LATEST, read);
  }

  <T> T at(long commit, Function<CatalogSnapshot, T> read) {
    return store.read(domain, Point.at(commit), read);
  }

  long lastCommit() {
    return latest(CatalogSnapshot::commit);
  }

  Optional<FactState> state(String fact) {
    return latest(snapshot -> snapshot.factState(fact));
  }

  Optional<FactState> stateAt(long commit, String fact) {
    return at(commit, snapshot -> snapshot.factState(fact));
  }

  /** What one request for an element and a predicate receives at a commit. */
  List<Knowledge.AtAddress> knowledge(long commit, String element, String predicate) {
    return at(
        commit,
        snapshot ->
            snapshot
                .request(List.of(KnowledgeRequest.of(element, predicate)))
                .getFirst()
                .addresses());
  }

  /** What the only Fact Address of a predicate on an element offers at a commit. */
  Knowledge only(long commit, String element, String predicate) {
    var addresses = knowledge(commit, element, predicate);
    if (addresses.size() != 1) {
      throw new AssertionError("Expected one Fact Address, got " + addresses);
    }
    return addresses.getFirst().knowledge();
  }

  /** A Fact's latest Fact Revision. */
  FactRevision revision(String fact) {
    return latest(snapshot -> snapshot.latestRevision(fact).orElseThrow());
  }

  /** A dependency on a Fact's latest Fact Revision. */
  Dependency.OnFact on(String fact) {
    return new Dependency.OnFact(Ref.urn(fact), Ref.urn(revision(fact).urn()));
  }

  /** One commit request being built. */
  final class Commit {
    final CommitRequest.Builder request;

    Commit(CommitRequest.Builder request) {
      this.request = request;
    }

    Ref add(LogRecord record) {
      return request.add(record);
    }

    /** A Dataset with accepted existence and technical-reference Facts. */
    Ref dataset(String table) {
      var dataset = datasetProposal(table);
      accept(new Ref.Temp(dataset.number() + 2));
      accept(new Ref.Temp(dataset.number() + 4));
      return dataset;
    }

    /**
     * The records proposing a Dataset, its existence, and its technical reference, without
     * accepting the Facts: a proposal that only finds existing records.
     */
    Ref.Temp datasetProposal(String table) {
      var dataset =
          (Ref.Temp)
              add(
                  new LogRecord.Element(
                      new ElementIdentity.PhysicalObject(ElementKind.DATASET, null, table)));
      closedRangeFact(address(dataset, Vocabulary.EXISTENCE), "exists");
      closedRangeFact(address(dataset, Vocabulary.TECHNICAL_REFERENCE), table);
      return dataset;
    }

    /** A concept with an accepted existence Fact. */
    Ref concept() {
      var concept = add(new LogRecord.Element(new ElementIdentity.Unique(ElementKind.CONCEPT)));
      closedRangeCandidate(address(concept, Vocabulary.EXISTENCE), "exists");
      return concept;
    }

    Ref address(Ref element, String predicate) {
      return add(new LogRecord.FactAddress(element, predicate, Scope.EMPTY, null));
    }

    /** A Fact whose value is the same as another's exactly when the values are equal. */
    Ref closedRangeFact(Ref address, String value) {
      return add(
          new LogRecord.Fact(
              address, JsonNodeFactory.instance.textNode(value), IdentityKey.choice(value)));
    }

    /** A closed-range Fact, accepted as a candidate. */
    Ref closedRangeCandidate(Ref address, String value, Dependency... dependencies) {
      var fact = closedRangeFact(address, value);
      accept(fact, dependencies);
      return fact;
    }

    /** A Fact that is the same as another only when the proposer points to it, accepted. */
    Ref candidate(Ref address, String value, Dependency... dependencies) {
      var fact = add(new LogRecord.Fact(address, JsonNodeFactory.instance.textNode(value), null));
      accept(fact, dependencies);
      return fact;
    }

    Ref accept(Ref fact, Dependency... dependencies) {
      return add(new LogRecord.Accept(fact, List.of(dependencies), List.of(), NOTHING, NOTHING));
    }

    Ref evaluate(Ref fact, Transition transition, PolicyResult result) {
      return add(new LogRecord.EvaluatePolicy(fact, policy, transition, result, "policy", "test"));
    }

    /** A policy evaluation choosing machine judgement, and the confirmation resting on it. */
    Ref confirmByMachine(Ref fact) {
      var evaluation = evaluate(fact, Transition.CONFIRM, PolicyResult.MACHINE_JUDGEMENT);
      return add(new LogRecord.Confirm(fact, ConfirmationPath.MACHINE_JUDGEMENT, evaluation));
    }

    Ref answer(Ref fact, Transition transition, AnswerType type) {
      return add(
          new LogRecord.Answer(
              fact, transition, type, "owner", "authority-check", "wording", null));
    }

    Ref proposeRetire(Ref fact) {
      return add(
          new LogRecord.ProposeRetire(
              fact, RetirementReason.CONFIRMATION_WAS_WRONG, List.of(), NOTHING, NOTHING));
    }

    /** A retirement proposal decided by machine judgement, and the retirement resting on it. */
    Ref retireByMachine(Ref fact) {
      proposeRetire(fact);
      var evaluation = evaluate(fact, Transition.RETIRE, PolicyResult.MACHINE_JUDGEMENT);
      return add(new LogRecord.Retire(fact, new Basis.Event(evaluation)));
    }

    /** A deciding party's resolution of a conflict at a Fact Address. */
    Ref resolveConflict(Ref address, Ref chosen, Ref... passedOver) {
      return add(
          new LogRecord.ResolveConflict(
              address, chosen, List.of(passedOver), "owner", "authority-check", "wording", null));
    }
  }
}
