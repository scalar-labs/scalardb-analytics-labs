/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a Fact Address offers at a point: its selected Fact, or a status with the material to judge
 * it (see {@code docs/semantic-catalog/catalog-snapshot.md}, "What the Query Agent receives").
 * Exactly one applies, judged in this order: a conflict, a selected Fact, candidates awaiting
 * judgement, and missing knowledge. Its status is written as the {@code status} property, so a
 * consumer of the JSON form never infers it from which fields are present.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Knowledge.Selected.class, name = "selected"),
  @JsonSubTypes.Type(value = Knowledge.Conflict.class, name = "conflict"),
  @JsonSubTypes.Type(value = Knowledge.AwaitingJudgement.class, name = "awaiting-judgement"),
  @JsonSubTypes.Type(value = Knowledge.MissingKnowledge.class, name = "missing-knowledge")
})
public sealed interface Knowledge {
  /**
   * Which knowledge a read returns.
   *
   * <p>Most uses, such as composing a query, need only confirmed knowledge, so that is the default.
   * A consumer that judges or reports on the catalog asks for unconfirmed knowledge too.
   */
  enum View {
    /**
     * Only Fact Addresses with a selected Fact: {@link Selected}, and a {@link Conflict} whose
     * selected Fact still stands. Candidates are not listed; {@link RevisionContent#newCandidates}
     * still says whether they exist.
     */
    CONFIRMED,
    /** Every Fact Address with its status and judgement material. */
    WITH_UNCONFIRMED
  }

  /**
   * One Fact is selected and nothing conflicts with it.
   *
   * @param candidates the {@code candidate} Facts at the same Fact Address: new candidates that may
   *     replace it; empty in the {@linkplain View#CONFIRMED confirmed view}
   */
  record Selected(RevisionContent fact, List<String> candidates) implements Knowledge {
    public Selected {
      candidates = List.copyOf(candidates);
    }
  }

  /** What the knowledge at a Fact Address is in a view, or empty when the view leaves it out. */
  default Optional<Knowledge> in(View view) {
    if (view == View.WITH_UNCONFIRMED) {
      return Optional.of(this);
    }
    return switch (this) {
      case Selected selected -> Optional.of(new Selected(selected.fact(), List.of()));
      case Conflict conflict ->
          conflict.selected() == null ? Optional.empty() : Optional.of(conflict);
      case AwaitingJudgement ignored -> Optional.empty();
      case MissingKnowledge ignored -> Optional.empty();
    };
  }

  /**
   * Two or more Facts are eligible and await a conflict resolution.
   *
   * @param selected the Fact still presented from before the conflict; absent when none is. It is
   *     usually one of the eligible Facts, but a selected Fact that two replacements displaced at
   *     once stays selected outside them, so its content is given here.
   */
  record Conflict(List<RevisionContent> eligible, @Nullable RevisionContent selected)
      implements Knowledge {
    public Conflict {
      eligible = List.copyOf(eligible);
    }
  }

  /** No Fact is eligible, but candidates exist, including those waiting for their dependencies. */
  record AwaitingJudgement(List<Candidate> candidates) implements Knowledge {
    public AwaitingJudgement {
      candidates = List.copyOf(candidates);
    }
  }

  /**
   * Neither an eligible Fact nor a candidate exists.
   *
   * @param rejectedOrRetired the rejected and retired Facts, if any, with the answers given to them
   */
  record MissingKnowledge(List<Ended> rejectedOrRetired) implements Knowledge {
    public MissingKnowledge {
      rejectedOrRetired = List.copyOf(rejectedOrRetired);
    }
  }

  /**
   * A candidate awaiting judgement, and what it waits for.
   *
   * @param state the Fact's state, always {@code candidate}: stated so a consumer reads it as a
   *     Fact state, as it reads every other Fact's
   * @param waitingFor what the candidate waits for before it can be confirmed, which is not a Fact
   *     state of its own
   */
  record Candidate(String fact, FactState state, Waiting waitingFor) {
    public Candidate {
      if (state != FactState.CANDIDATE) {
        throw new IllegalArgumentException("A candidate awaiting judgement is in state candidate");
      }
    }

    public static Candidate of(String fact, Waiting waitingFor) {
      return new Candidate(fact, FactState.CANDIDATE, waitingFor);
    }
  }

  /** What a candidate waits for before it can be confirmed. */
  enum Waiting {
    /** A premise dependency that is not confirmed yet. */
    DEPENDENCIES,
    /** A policy evaluation. */
    POLICY_EVALUATION,
    /** A consent the latest policy evaluation requested. */
    CONSENT
  }

  /** A rejected or retired Fact, and the answers given to it up to the point. */
  record Ended(RevisionContent fact, List<GivenAnswer> answers) {
    public Ended {
      answers = List.copyOf(answers);
    }
  }

  /**
   * A human answer given to a Fact.
   *
   * @param logRecord the URN of the answer's log record
   * @param decidedBy the deciding party
   * @param wording the wording answered; for a correction, the given value or condition
   * @param reviewPresentation the review presentation the answer was given in, if any
   */
  record GivenAnswer(
      String logRecord,
      Transition transition,
      AnswerType answerType,
      String decidedBy,
      String wording,
      @Nullable String reviewPresentation) {}

  /**
   * A Fact Revision as read at a point (see {@code docs/semantic-catalog/public-api.md}, "Fact
   * Revision content"), with the records behind it expanded one level.
   *
   * @param newCandidates whether {@code candidate} Facts exist at the same Fact Address
   * @param newerObservation whether an input the Fact depends on has an observation newer than the
   *     one depended on, up to the point
   * @param unexaminedItems the validation items of the predicate that no supporting check examined,
   *     listed as information, never as a measure of whether the Fact holds
   * @param referenced the content of each record its dependencies, checks, and inputs read refer
   *     to, once per URN in the order first referred to; their own references stay URNs
   */
  record RevisionContent(
      FactRevision revision,
      boolean newCandidates,
      boolean newerObservation,
      List<String> unexaminedItems,
      List<Referenced> referenced) {
    public RevisionContent {
      unexaminedItems = List.copyOf(unexaminedItems);
      referenced = List.copyOf(referenced);
    }
  }

  /** A record a Fact Revision refers to, with its content at the point. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Referenced.Observation.class, name = "observation"),
    @JsonSubTypes.Type(value = Referenced.Revision.class, name = "revision"),
    @JsonSubTypes.Type(value = Referenced.Answer.class, name = "answer")
  })
  sealed interface Referenced {
    /** The URN referred to. */
    String urn();

    /** An Input Observation and its content as recorded. */
    record Observation(String urn, String inputResource, JsonNode content) implements Referenced {}

    /**
     * A Fact Revision, or a Fact's latest Fact Revision at the point when a Fact is referred to.
     *
     * @param stateAtPoint the Fact's state at the point, which may differ from the revision's
     * @param answers for a Fact rejected or retired at the point, the answers given to it
     */
    record Revision(
        String urn, FactRevision revision, FactState stateAtPoint, List<GivenAnswer> answers)
        implements Referenced {
      public Revision {
        answers = List.copyOf(answers);
      }
    }

    /** An answer, which inputs read refer to by its log record. */
    record Answer(String urn, GivenAnswer answer) implements Referenced {}
  }

  /**
   * What a request receives for one Fact Address of the requested predicate on a target.
   *
   * @param language the language of a language-qualified predicate's Fact Address, such as the
   *     language of a business term's name; absent for other predicates
   * @param predicateDefinition the definition of a free-description predicate; absent otherwise
   * @param scopeDescription the applicability scope's description; absent for the empty scope
   * @param scopeEntities the URNs of the entities the applicability scope mentions
   */
  record AtAddress(
      String factAddress,
      String element,
      String predicate,
      @Nullable String language,
      @Nullable String predicateDefinition,
      @Nullable String scopeDescription,
      List<String> scopeEntities,
      Knowledge knowledge) {
    public AtAddress {
      scopeEntities = List.copyOf(scopeEntities);
    }
  }

  /**
   * The answer to one request: one entry per Fact Address of the requested predicate, or of every
   * predicate, on each target. No entry and no missing predicate means no edge between the given
   * ends.
   *
   * @param addresses the Fact Addresses the view includes
   * @param missing the requested predicates with missing knowledge on a target, in either view:
   *     those with no Fact Address, or whose every Fact Address offers missing knowledge. The
   *     requested predicates are the named one, or for a request for all predicates each
   *     closed-core predicate of the target's kind.
   */
  record Answer(KnowledgeRequest request, List<AtAddress> addresses, List<Missing> missing) {
    public Answer {
      addresses = List.copyOf(addresses);
      missing = List.copyOf(missing);
    }
  }

  /**
   * A predicate requested of an element that has no Fact Address for it, or whose every Fact
   * Address offers missing knowledge.
   *
   * @param language for a language-qualified predicate, the declared language it is missing in;
   *     absent for other predicates
   */
  record Missing(String element, String predicate, @Nullable String language) {
    /** A missing predicate that is not language-qualified. */
    public Missing(String element, String predicate) {
      this(element, predicate, null);
    }
  }

  /**
   * An element whose existence Fact is selected at the point.
   *
   * @param parent the Dataset of a Field; absent for other kinds
   */
  record Element(String element, ElementKind kind, @Nullable String parent) {}
}
