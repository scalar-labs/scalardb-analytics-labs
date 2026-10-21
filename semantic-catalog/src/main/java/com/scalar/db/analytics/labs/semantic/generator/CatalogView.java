/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.ElementDescriptor;
import com.scalar.db.analytics.labs.semantic.model.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ObservationKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Target;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** What a Generator may read of the catalog, as of the latest commit. */
public interface CatalogView {
  String domain();

  long commit();

  Optional<ElementView> element(String urn);

  List<ElementView> elements(ElementKind kind);

  /** The elements whose higher-level element is the given one, such as a Dataset's Fields. */
  List<ElementView> children(String parentUrn);

  Optional<FactView> fact(String urn);

  /** Every Fact of an element, in any state and any applicability scope. */
  List<FactView> facts(String elementUrn);

  /** The Fact selected at the unrestricted-scope Fact Address of an element and predicate. */
  Optional<FactView> selected(String elementUrn, String predicate);

  Optional<ObservationView> observation(String urn);

  Optional<ObservationView> latestObservation(String inputResource);

  List<ObservationView> latestObservations(ObservationKind kind);

  /** An answer Fact Event by its log record identifier. */
  Optional<AnswerView> answer(String logRecordId);

  /** The answers recorded for a Fact, oldest first. */
  List<AnswerView> answers(String factUrn);

  record ElementView(ElementDescriptor descriptor, @Nullable FactState existence) {
    public String urn() {
      return descriptor.urn();
    }

    public ElementKind kind() {
      return descriptor.kind();
    }
  }

  /**
   * @param dependsOn the Fact URNs the latest revision depends on
   * @param generator the Generator that first proposed the Fact
   */
  record FactView(
      String urn,
      String address,
      String element,
      String predicate,
      Scope scope,
      JsonNode value,
      FactState state,
      String latestRevision,
      String generator,
      List<String> dependsOn) {}

  /**
   * @param id the log record identifier of the observation
   */
  record ObservationView(
      String urn,
      String id,
      String inputResource,
      ObservationKind kind,
      JsonNode content,
      @Nullable String version,
      @Nullable String question,
      String digest,
      long commit) {}

  record AnswerView(
      String id,
      String fact,
      Target target,
      AnswerType answerType,
      String principal,
      String wording) {}
}
