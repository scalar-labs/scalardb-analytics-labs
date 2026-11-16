/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.scalar.db.analytics.labs.semantic.model.Knowledge.RevisionContent;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The review list: what needs the deciding party at a point (see {@code
 * docs/semantic-catalog/public-api.md}, "Retrieving the review list").
 */
public final class Review {
  private Review() {}

  /**
   * Where in the hierarchy a review list is read: the domain root, a data source, a namespace, or a
   * table. Absent parts are unrestricted; a namespace needs its data source, and a table its
   * namespace.
   */
  public record Position(
      @Nullable String registration, @Nullable String namespace, @Nullable String table) {
    public static final Position ROOT = new Position(null, null, null);

    public Position {
      if ((namespace != null && registration == null) || (table != null && namespace == null)) {
        throw new IllegalArgumentException(
            "A namespace is read within its data source, and a table within its namespace");
      }
    }

    /** Whether the position is the domain root. */
    public boolean root() {
      return registration == null;
    }

    /** Whether a table, in its canonical form, is below the position. */
    public boolean contains(String table) {
      if (registration == null) {
        return true;
      }
      var prefix =
          Urns.encode(registration)
              + (namespace == null ? "" : "." + Urns.encode(namespace))
              + (this.table == null ? "" : "." + Urns.encode(this.table));
      return this.table == null ? table.startsWith(prefix + ".") : table.equals(prefix);
    }
  }

  /**
   * What to read: a position, optionally narrowed to one kind of item, one element, or one Fact
   * Address.
   */
  public record Query(
      Position position,
      ReviewPresentation.@Nullable Kind kind,
      @Nullable String element,
      @Nullable String factAddress) {
    public static final Query ALL = new Query(Position.ROOT, null, null, null);
  }

  /**
   * The items about one table, or the domain-wide items.
   *
   * @param table the table's canonical form, such as {@code shop.public.orders}; absent for the
   *     group of domain-wide items: Concepts, business terms, concept relationships, Dimensions,
   *     and Measures without a selected base Dataset
   * @param items the items, ordered by the commit at which the need for judgement arose
   */
  public record Group(@Nullable String table, List<Item> items) {
    public Group {
      items = List.copyOf(items);
    }
  }

  /** What one item is about. */
  public record Target(
      String element,
      Vocabulary.ElementKind elementKind,
      @Nullable String physicalCatalogReference,
      String factAddress,
      String predicate,
      @Nullable String scopeDescription,
      List<String> scopeEntities) {
    public Target {
      scopeEntities = List.copyOf(scopeEntities);
    }
  }

  /** One review item. */
  public sealed interface Item {
    Target target();

    /** The commit at which the need for judgement arose; a re-evaluation gives a newer one. */
    long needArose();

    /** The review presentations that presented the item before, oldest first. */
    List<Long> earlierPresentations();

    /** The kind of presentation the item asks for. */
    ReviewPresentation.Kind kind();
  }

  /**
   * The judgement the latest policy evaluation asked for.
   *
   * @param logRecord the evaluation's log record
   */
  public record Evaluation(String logRecord, LogRecord.EvaluatePolicy record) {}

  /**
   * A candidate whose latest evaluation requested consent.
   *
   * @param premises the content of every premise dependency, expanded
   * @param premiseItems the premise dependencies that are review items themselves; non-empty
   *     exactly when the candidate rests on premises that await consent, which a rejection of
   *     theirs would reject with it
   * @param cascadesTo the candidates a rejection would reject with it, directly or through others
   * @param replaces the Fact selected at the Fact Address that consent would replace, if any
   */
  public record Candidate(
      Target target,
      Knowledge.RevisionContent fact,
      Evaluation evaluation,
      List<Knowledge.RevisionContent> premises,
      List<String> premiseItems,
      List<String> cascadesTo,
      @Nullable String replaces,
      long needArose,
      List<Long> earlierPresentations)
      implements Item {
    public Candidate {
      premises = List.copyOf(premises);
      premiseItems = List.copyOf(premiseItems);
      cascadesTo = List.copyOf(cascadesTo);
      earlierPresentations = List.copyOf(earlierPresentations);
    }

    @Override
    public ReviewPresentation.Kind kind() {
      return ReviewPresentation.Kind.CONFIRMATION;
    }
  }

  /**
   * An open retirement proposal whose latest evaluation requested consent.
   *
   * @param proposal the log record of the open retirement proposal
   * @param cascadesTo the confirmed Facts a retirement would retire with it, directly or through
   *     others
   */
  public record Retirement(
      Target target,
      Knowledge.RevisionContent fact,
      String proposal,
      LogRecord.ProposeRetire proposalRecord,
      Evaluation evaluation,
      List<String> cascadesTo,
      long needArose,
      List<Long> earlierPresentations)
      implements Item {
    public Retirement {
      cascadesTo = List.copyOf(cascadesTo);
      earlierPresentations = List.copyOf(earlierPresentations);
    }

    @Override
    public ReviewPresentation.Kind kind() {
      return ReviewPresentation.Kind.RETIREMENT;
    }
  }

  /**
   * A conflicting Fact Address.
   *
   * @param selected the Fact still presented from before the conflict, if any
   */
  public record Conflict(
      Target target,
      List<Knowledge.RevisionContent> eligible,
      @Nullable RevisionContent selected,
      long needArose,
      List<Long> earlierPresentations)
      implements Item {
    public Conflict {
      eligible = List.copyOf(eligible);
      earlierPresentations = List.copyOf(earlierPresentations);
    }

    @Override
    public ReviewPresentation.Kind kind() {
      return ReviewPresentation.Kind.CONFLICT;
    }
  }

  /**
   * What a proposed user policy version would do to the candidates waiting at the latest point (see
   * {@code docs/semantic-catalog/public-api.md}, "Checking a user policy version").
   *
   * @param catalogSnapshot the point evaluated
   * @param currentVersion the log record of the version in force
   * @param adoptedInline the candidates inline evaluation would adopt
   * @param toModel the candidates the policy evaluation model would decide, whose decisions are
   *     known only by running it
   */
  public record PolicyCheck(
      String catalogSnapshot,
      String currentVersion,
      LogRecord.PolicyVersion current,
      List<String> adoptedInline,
      List<String> toModel) {
    public PolicyCheck {
      adoptedInline = List.copyOf(adoptedInline);
      toModel = List.copyOf(toModel);
    }
  }
}
