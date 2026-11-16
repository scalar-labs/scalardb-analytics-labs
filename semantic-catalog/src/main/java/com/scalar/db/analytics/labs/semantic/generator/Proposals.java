/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Fact;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Proposing closed-range Facts about existing elements, shared by the Generators. */
public final class Proposals {
  private Proposals() {}

  /** A dependency on the Fact Revision of a Fact's latest acceptance. */
  public static Dependency on(Fact fact) {
    return new Dependency.OnFact(Ref.urn(fact.urn()), Ref.urn(fact.revision()));
  }

  /**
   * What a proposal of a closed-range Fact reads at its Fact Address.
   *
   * @param same the existing Fact with the same value, if any
   * @param dependencies the replacement dependencies on the other Facts it replaces
   * @param revisions the Fact Revisions those dependencies name, as inputs read
   */
  public record AtAddress(
      Optional<Fact> same, List<Dependency> dependencies, List<String> revisions) {
    public AtAddress {
      dependencies = List.copyOf(dependencies);
      revisions = List.copyOf(revisions);
    }
  }

  /**
   * Reads the Fact Address a closed-range proposal targets. When another Fact is confirmed there,
   * the candidate reads it and depends on it, so the candidate replaces it once confirmed (see
   * {@code docs/semantic-catalog/generators.md}, "The Generator contract"). A replacement read
   * earlier stays, so a run after the replaced Fact is retired records nothing new.
   */
  public static AtAddress atAddress(
      GeneratorContext context, String element, String predicate, IdentityKey key) {
    var dependencies = new ArrayList<Dependency>();
    var revisions = new ArrayList<String>();
    var others = context.factsAt(element, predicate);
    var same =
        others.stream().filter(other -> key.canonical().equals(other.identityKey())).findFirst();
    for (var other : others) {
      if (key.canonical().equals(other.identityKey())) {
        continue;
      }
      var earlier =
          same.flatMap(
              found ->
                  found.dependencies().stream()
                      .filter(
                          dependency ->
                              dependency instanceof Dependency.OnFact onFact
                                  && onFact.fact().urnValue().equals(other.urn()))
                      .findFirst());
      if (other.state() == FactState.CONFIRMED || earlier.isPresent()) {
        var dependency = earlier.orElse(on(other));
        dependencies.add(dependency);
        revisions.add(dependency.version().urnValue());
      }
    }
    return new AtAddress(same, dependencies, revisions);
  }
}
