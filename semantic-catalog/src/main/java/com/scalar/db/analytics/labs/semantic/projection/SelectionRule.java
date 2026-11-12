/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Selects the Fact presented at one Fact Address at the end of a commit (see {@code
 * docs/semantic-catalog/catalog-snapshot.md}, "Selecting one Fact per Fact Address").
 *
 * <p>The <em>eligible</em> Facts are the presentable Facts that are not displaced. A Fact is
 * displaced when another presentable Fact at the same Fact Address depended on it at confirmation,
 * which is a replacement, or when a conflict resolution passed it over and the Fact chosen then has
 * stayed presentable since. A chosen Fact retired and confirmed again no longer carries the
 * resolution, so its new confirmation meets the passed-over Fact as any independent Fact would.
 * With one eligible Fact, it is selected. With two or more there is a conflict, and the selection
 * carries over from the previous commit:
 *
 * <ul>
 *   <li>a previously selected Fact that is still eligible stays selected;
 *   <li>one that is now displaced passes the selection to the single eligible Fact that depends on
 *       it, if there is exactly one, and otherwise stays selected while presentable;
 *   <li>one that was retired passes the selection to the Fact it had replaced, if that Fact is
 *       eligible;
 *   <li>otherwise nothing is selected.
 * </ul>
 *
 * <p>The rule sees only the state at the end of a commit, so the order of records inside a commit
 * never decides a selection.
 */
public final class SelectionRule {
  private SelectionRule() {}

  /**
   * One Fact at the Fact Address as it stands at the end of the commit.
   *
   * @param presentableSince the commit at which the Fact's current presentable period began, its
   *     latest confirmation; absent when the Fact is not {@code confirmed}
   * @param confirmationDependencies the Facts its dependency references at its latest confirmation
   *     name; those at the same Fact Address are the Facts it replaced
   */
  public record Fact(
      String fact, @Nullable Long presentableSince, Set<String> confirmationDependencies) {
    public Fact {
      confirmationDependencies = Set.copyOf(confirmationDependencies);
    }

    public boolean presentable() {
      return presentableSince != null;
    }
  }

  /**
   * A conflict resolution recorded in the log.
   *
   * @param chosen the Fact chosen
   * @param passedOver the Facts passed over
   * @param commit the commit that recorded the resolution
   */
  public record Resolution(String chosen, List<String> passedOver, long commit) {
    public Resolution {
      passedOver = List.copyOf(passedOver);
    }

    /**
     * Whether the resolution still holds: the chosen Fact has stayed presentable since the
     * resolution, without a retirement and a later confirmation in between.
     */
    boolean holdsFor(@Nullable Fact chosenFact) {
      var since = chosenFact == null ? null : chosenFact.presentableSince();
      return since != null && since <= commit;
    }
  }

  /**
   * The selection at the end of the commit.
   *
   * @param selected the selected Fact; absent when there is none
   * @param eligible the eligible Facts, sorted by URN
   */
  public record Result(@Nullable String selected, List<String> eligible) {
    public Result {
      eligible = List.copyOf(eligible);
    }
  }

  /**
   * Selects the Fact presented at a Fact Address.
   *
   * @param facts every Fact at the Fact Address
   * @param resolutions every conflict resolution recorded for the Fact Address
   * @param previous the Fact selected at the previous commit; absent when there was none
   */
  public static Result select(
      List<Fact> facts, List<Resolution> resolutions, @Nullable String previous) {
    Map<String, Fact> byUrn = new TreeMap<>();
    for (var fact : facts) {
      byUrn.put(fact.fact(), fact);
    }
    var eligible = new ArrayList<String>();
    for (var fact : byUrn.values()) {
      if (fact.presentable() && !displaced(fact, byUrn, resolutions)) {
        eligible.add(fact.fact());
      }
    }
    return new Result(selected(eligible, byUrn, previous), eligible);
  }

  private static @Nullable String selected(
      List<String> eligible, Map<String, Fact> byUrn, @Nullable String previous) {
    if (eligible.isEmpty()) {
      return null;
    }
    if (eligible.size() == 1) {
      return eligible.getFirst();
    }
    if (previous == null) {
      return null;
    }
    if (eligible.contains(previous)) {
      return previous;
    }
    var previousFact = byUrn.get(previous);
    if (previousFact == null) {
      return null;
    }
    if (previousFact.presentable()) {
      var replacing = eligible.stream().filter(urn -> dependsOn(byUrn.get(urn), previous)).toList();
      return replacing.size() == 1 ? replacing.getFirst() : previous;
    }
    return previousFact.confirmationDependencies().stream()
        .filter(eligible::contains)
        .sorted()
        .findFirst()
        .orElse(null);
  }

  private static boolean dependsOn(@Nullable Fact fact, String other) {
    return fact != null && fact.confirmationDependencies().contains(other);
  }

  private static boolean displaced(
      Fact fact, Map<String, Fact> byUrn, List<Resolution> resolutions) {
    for (var other : byUrn.values()) {
      if (other != fact
          && other.presentable()
          && other.confirmationDependencies().contains(fact.fact())) {
        return true;
      }
    }
    for (var resolution : resolutions) {
      if (resolution.passedOver().contains(fact.fact())
          && resolution.holdsFor(byUrn.get(resolution.chosen()))) {
        return true;
      }
    }
    return false;
  }
}
