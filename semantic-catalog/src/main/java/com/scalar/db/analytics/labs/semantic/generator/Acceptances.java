/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.Fact;
import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.InputKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Additional acceptances of a Fact that several Generators support (see {@code
 * docs/semantic-catalog/record-model.md}, "Validation results and dependencies").
 *
 * <p>Each acceptance states the complete validation result and dependency references in effect
 * after it, replacing those of the latest acceptance. A Generator accepting a Fact that another
 * Generator also supports therefore restates the other's checks and dependencies from the latest
 * acceptance and replaces only its own: uniqueness that an unenforced declaration states is
 * confirmed once a data check adds its support, which it could not be if the data check's
 * acceptance dropped the declaration's.
 */
public final class Acceptances {
  private Acceptances() {}

  /** Which checks and dependencies of an acceptance a Generator states itself. */
  public interface Ownership {
    boolean ownsCheck(Check check);

    boolean ownsDependency(Dependency dependency);
  }

  /** The checks and dependencies an acceptance states. */
  public record Restated(List<Dependency> dependencies, List<Check> checks) {
    public Restated {
      dependencies = List.copyOf(dependencies);
      checks = List.copyOf(checks);
    }
  }

  /**
   * What an acceptance states: the Generator's own checks and dependencies, followed by those of
   * the Fact's latest acceptance that other Generators stated. A dependency on a Fact the Generator
   * states anew replaces the earlier one on the same Fact.
   *
   * @param same the existing Fact the acceptance is of, if any
   */
  public static Restated restate(
      Optional<Fact> same, Ownership own, List<Dependency> dependencies, List<Check> checks) {
    var restatedDependencies = new ArrayList<>(dependencies);
    var restatedChecks = new ArrayList<>(checks);
    if (same.isPresent()) {
      var factsStated = new HashSet<String>();
      for (var dependency : dependencies) {
        if (dependency instanceof Dependency.OnFact onFact) {
          factsStated.add(onFact.fact().urnValue());
        }
      }
      for (var dependency : same.get().dependencies()) {
        var replaced =
            dependency instanceof Dependency.OnFact onFact
                && factsStated.contains(onFact.fact().urnValue());
        if (!own.ownsDependency(dependency)
            && !replaced
            && !restatedDependencies.contains(dependency)) {
          restatedDependencies.add(dependency);
        }
      }
      for (var check : same.get().checks()) {
        if (!own.ownsCheck(check) && !restatedChecks.contains(check)) {
          restatedChecks.add(check);
        }
      }
    }
    return new Restated(restatedDependencies, restatedChecks);
  }

  /**
   * What schema interpretation states: the checks of declarations and the dependencies on physical
   * schema observations. Checks of declarations come only from schema interpretation.
   */
  public static Ownership schemaInterpretation(GeneratorContext context) {
    return new Ownership() {
      @Override
      public boolean ownsCheck(Check check) {
        return check.method() == ValidationMethod.DECLARED_ENFORCED
            || check.method() == ValidationMethod.DECLARED_UNENFORCED;
      }

      @Override
      public boolean ownsDependency(Dependency dependency) {
        return dependency instanceof Dependency.OnInput input
            && context
                .inputKind(input.inputResource().urnValue())
                .filter(kind -> kind == InputKind.PHYSICAL_SCHEMA)
                .isPresent();
      }
    };
  }

  /**
   * What a data check states: the checks naming it as their check, and the dependencies on the data
   * observations of its kind of check.
   */
  public static Ownership dataCheck(GeneratorContext context, String check) {
    return new Ownership() {
      @Override
      public boolean ownsCheck(Check found) {
        return check.equals(found.details().path("check").asText(null));
      }

      @Override
      public boolean ownsDependency(Dependency dependency) {
        return dependency instanceof Dependency.OnInput input
            && context
                .dataCheck(input.inputResource().urnValue())
                .filter(dataCheck -> dataCheck.startsWith(check + " "))
                .isPresent();
      }
    };
  }
}
