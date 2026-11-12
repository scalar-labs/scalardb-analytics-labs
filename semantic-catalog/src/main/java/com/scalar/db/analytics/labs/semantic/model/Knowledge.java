/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a Fact Address offers at a point: its selected Fact, or a status with the material to judge
 * it (see {@code docs/semantic-catalog/catalog-snapshot.md}, "What the Query Agent receives").
 * Exactly one applies, judged in this order: a conflict, a selected Fact, candidates awaiting
 * judgement, and missing knowledge.
 */
public sealed interface Knowledge {
  /**
   * One Fact is selected and nothing conflicts with it.
   *
   * @param candidates the {@code candidate} Facts at the same Fact Address: new candidates that may
   *     replace it
   */
  record Selected(RevisionContent fact, List<String> candidates) implements Knowledge {
    public Selected {
      candidates = List.copyOf(candidates);
    }
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
   * @param rejectedOrRetired the rejected and retired Facts, if any
   */
  record MissingKnowledge(List<String> rejectedOrRetired) implements Knowledge {
    public MissingKnowledge {
      rejectedOrRetired = List.copyOf(rejectedOrRetired);
    }
  }

  /** A candidate awaiting judgement, and what it waits for. */
  record Candidate(String fact, Waiting waiting) {}

  /** What a candidate waits for before it can be confirmed. */
  enum Waiting {
    /** A premise dependency that is not confirmed yet. */
    DEPENDENCIES,
    /** A policy evaluation. */
    POLICY_EVALUATION,
    /** A consent the latest policy evaluation requested. */
    CONSENT
  }

  /**
   * A Fact Revision as read at a point (see {@code docs/semantic-catalog/public-api.md}, "Fact
   * Revision content").
   *
   * @param newCandidates whether {@code candidate} Facts exist at the same Fact Address
   * @param newerObservation whether an input the Fact depends on has an observation newer than the
   *     one depended on, up to the point
   */
  record RevisionContent(FactRevision revision, boolean newCandidates, boolean newerObservation) {}

  /**
   * What a request receives for one Fact Address of the requested predicate on a target.
   *
   * @param scopeDescription the applicability scope's description; absent for the empty scope
   * @param scopeEntities the URNs of the entities the applicability scope mentions
   */
  record AtAddress(
      String factAddress,
      String element,
      String predicate,
      @Nullable String scopeDescription,
      List<String> scopeEntities,
      Knowledge knowledge) {
    public AtAddress {
      scopeEntities = List.copyOf(scopeEntities);
    }
  }

  /**
   * The answer to one request: one entry per Fact Address of the predicate on each target. An empty
   * list is missing knowledge with no Fact Address at all, or no edge between the given ends.
   */
  record Answer(KnowledgeRequest request, List<AtAddress> addresses) {
    public Answer {
      addresses = List.copyOf(addresses);
    }
  }

  /**
   * An element whose existence Fact is selected at the point.
   *
   * @param parent the Dataset of a Field; absent for other kinds
   */
  record Element(String element, ElementKind kind, @Nullable String parent) {}
}
