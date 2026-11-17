/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.FactRevision;
import com.scalar.db.analytics.labs.semantic.model.Hierarchy;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.Review;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.SequencedSet;
import org.jspecify.annotations.Nullable;

/**
 * The records whose text a read returns, named by the URNs the read returns them by (see {@code
 * docs/semantic-catalog/languages.md}, "Reading in a language"). It is the one definition of where
 * text appears in a read's result; which parts of a record are text is {@code TranslatableText}'s.
 *
 * <p>A Fact's value is named by its Fact, the text of the Fact Event a Fact Revision rests on by
 * the Fact Revision, a Fact Address's descriptions by the Fact Address, and an answer, an
 * evaluation, or an observation by its own URN. A record whose content the read does not expand,
 * such as a candidate it names only by URN, contributes nothing. A record named here may still have
 * no text, such as a Fact whose value is closed.
 */
final class ReadTexts {
  private ReadTexts() {}

  /** The URNs naming text in a read's result, in the order first met. */
  static SequencedSet<String> of(@Nullable Object result) {
    var urns = new LinkedHashSet<String>();
    collect(result, urns);
    return urns;
  }

  private static void collect(@Nullable Object value, SequencedSet<String> urns) {
    switch (value) {
      case null -> {}
      case Collection<?> items -> items.forEach(item -> collect(item, urns));
      case Optional<?> optional -> optional.ifPresent(item -> collect(item, urns));
      case Hierarchy.Root root -> {
        collect(root.dataSources(), urns);
        collect(root.domainWide(), urns);
      }
      case Hierarchy.DataSource source -> collect(source.namespaces(), urns);
      case Hierarchy.Namespace namespace -> collect(namespace.tables(), urns);
      case Hierarchy.Table table -> collect(table.unit(), urns);
      case Hierarchy.TableUnit unit -> {
        collect(unit.dataset(), urns);
        collect(unit.fields(), urns);
        collect(unit.measures(), urns);
        collect(unit.correspondences(), urns);
      }
      case Hierarchy.ElementKnowledge element -> collect(element.predicates(), urns);
      case Knowledge.Answer answer -> collect(answer.addresses(), urns);
      case Knowledge.AtAddress address -> {
        if (address.predicateDefinition() != null || address.scopeDescription() != null) {
          urns.add(address.factAddress());
        }
        collect(address.knowledge(), urns);
      }
      case Knowledge.Selected selected -> collect(selected.fact(), urns);
      case Knowledge.Conflict conflict -> {
        collect(conflict.eligible(), urns);
        collect(conflict.selected(), urns);
      }
      case Knowledge.AwaitingJudgement ignored -> {}
      case Knowledge.MissingKnowledge missing -> collect(missing.rejectedOrRetired(), urns);
      case Knowledge.Ended ended -> {
        collect(ended.fact(), urns);
        collect(ended.answers(), urns);
      }
      case Knowledge.RevisionContent content -> {
        collect(content.revision(), urns);
        collect(content.referenced(), urns);
      }
      case FactRevision revision -> {
        urns.add(revision.fact());
        urns.add(revision.urn());
      }
      case Knowledge.Referenced.Observation observation -> urns.add(observation.urn());
      case Knowledge.Referenced.Revision revision -> {
        collect(revision.revision(), urns);
        collect(revision.answers(), urns);
      }
      case Knowledge.Referenced.Answer answer -> collect(answer.answer(), urns);
      case Knowledge.GivenAnswer answer -> urns.add(answer.logRecord());
      case Review.Group group -> collect(group.items(), urns);
      case Review.Candidate candidate -> {
        collect(candidate.target(), urns);
        collect(candidate.fact(), urns);
        collect(candidate.evaluation(), urns);
        collect(candidate.premises(), urns);
      }
      case Review.Retirement retirement -> {
        collect(retirement.target(), urns);
        collect(retirement.fact(), urns);
        collect(retirement.evaluation(), urns);
      }
      case Review.Conflict conflict -> {
        collect(conflict.target(), urns);
        collect(conflict.eligible(), urns);
        collect(conflict.selected(), urns);
      }
      case Review.Target target -> {
        if (target.scopeDescription() != null) {
          urns.add(target.factAddress());
        }
      }
      case Review.Evaluation evaluation -> urns.add(evaluation.logRecord());
      default -> {}
    }
  }
}
