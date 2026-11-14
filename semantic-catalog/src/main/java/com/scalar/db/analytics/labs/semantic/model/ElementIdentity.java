/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.List;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * An element identity: how a proposed element is identified, which decides whether it is the same
 * as an existing element (see {@code docs/semantic-catalog/record-model.md}, "Identity and URNs").
 *
 * <p>Sameness is decided by rules per kind, never from URNs, and mechanical decisions are limited
 * to cases that are easy to identify. Each variant carries exactly what its rule needs.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "identity")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ElementIdentity.Unique.class, name = "unique"),
  @JsonSubTypes.Type(value = ElementIdentity.PhysicalObject.class, name = "physical-object"),
  @JsonSubTypes.Type(
      value = ElementIdentity.IdentifierCorrespondence.class,
      name = "identifier-correspondence"),
  @JsonSubTypes.Type(
      value = ElementIdentity.ConceptRelationship.class,
      name = "concept-relationship")
})
public sealed interface ElementIdentity {
  ElementKind kind();

  /**
   * A concept, Measure, Dimension, or business term. It is the same as an existing element only
   * when the proposer points to that element by its URN, so an element with this identity is always
   * new, even when another element has the same slug.
   *
   * @param slug the short business name its proposer gives, such as {@code ec-sales}, from which
   *     its URN is built; it is never knowledge about the element
   */
  record Unique(ElementKind kind, String slug) implements ElementIdentity {
    public Unique {
      if (kind.edge() || kind.technical()) {
        throw new IllegalArgumentException(kind + " is not identified by pointing");
      }
      Urns.requireSlug(slug);
    }
  }

  /**
   * A Dataset or a Field. It is the same as an existing element of its kind when their
   * technical-reference Facts that are neither rejected nor retired point to the same physical
   * object. A technical reference never moves, so a renamed table or column is a new element.
   *
   * @param parent the Dataset of a Field, which refers to the Field's table; absent for a Dataset
   * @param technicalReference the physical object the element refers to, whose canonical form is
   *     also the matching value of the element's technical-reference Fact
   */
  record PhysicalObject(@Nullable Ref parent, TechnicalReference technicalReference)
      implements ElementIdentity {
    public PhysicalObject {
      if ((technicalReference instanceof TechnicalReference.Column) != (parent != null)) {
        throw new IllegalArgumentException("A Field, and only a Field, has a parent Dataset");
      }
    }

    public static PhysicalObject dataset(TechnicalReference.Table table) {
      return new PhysicalObject(null, table);
    }

    public static PhysicalObject field(Ref dataset, TechnicalReference.Column column) {
      return new PhysicalObject(dataset, column);
    }

    @JsonIgnore
    @Override
    public ElementKind kind() {
      return technicalReference.elementKind();
    }
  }

  /**
   * An edge. It is the same as an existing edge when kind, ends, direction, and identifying part
   * match; an undirected edge ignores the order of its ends. A different identifying part, such as
   * another matching condition, is a different edge.
   */
  sealed interface Edge extends ElementIdentity {
    /** The elements of the end the edge starts from, or of one end of an undirected edge. */
    List<Ref> fromEnd();

    /** The elements of the end the edge goes to, or of the other end of an undirected edge. */
    List<Ref> toEnd();

    boolean directed();

    /**
     * The canonical identity key of the identifying part, which decides sameness. Every reference
     * must be resolved to a URN.
     */
    String identityKey();

    /** The same edge with each element reference replaced. */
    Edge mapRefs(UnaryOperator<Ref> mapping);
  }

  /**
   * An identifier correspondence: the Field set at one end and the Field set at the other denote
   * the same object. It is directed, and its set relationship reads from the starting end to the
   * other, so a subset means that the starting end's values are among the other's. Its ends are the
   * Fields its matching condition starts from and arrives at.
   *
   * @param condition the matching condition, which is the identifying part
   */
  record IdentifierCorrespondence(MatchingCondition condition) implements Edge {
    @JsonIgnore
    @Override
    public ElementKind kind() {
      return ElementKind.IDENTIFIER_CORRESPONDENCE;
    }

    @JsonIgnore
    @Override
    public List<Ref> fromEnd() {
      return condition.fromFields();
    }

    @JsonIgnore
    @Override
    public List<Ref> toEnd() {
      return condition.toFields();
    }

    @JsonIgnore
    @Override
    public boolean directed() {
      return true;
    }

    @JsonIgnore
    @Override
    public String identityKey() {
      return condition.canonical();
    }

    @Override
    public IdentifierCorrespondence mapRefs(UnaryOperator<Ref> mapping) {
      return new IdentifierCorrespondence(condition.mapRefs(mapping));
    }
  }

  /**
   * A concept relationship between two concepts.
   *
   * @param directed false when both concepts denote the same object
   * @param relationshipKind the relationship kind, which is the identifying part
   */
  record ConceptRelationship(Ref from, Ref to, boolean directed, String relationshipKind)
      implements Edge {
    public ConceptRelationship {
      if (relationshipKind.isBlank()) {
        throw new IllegalArgumentException("A concept relationship names its kind");
      }
    }

    @JsonIgnore
    @Override
    public ElementKind kind() {
      return ElementKind.CONCEPT_RELATIONSHIP;
    }

    @JsonIgnore
    @Override
    public List<Ref> fromEnd() {
      return List.of(from);
    }

    @JsonIgnore
    @Override
    public List<Ref> toEnd() {
      return List.of(to);
    }

    @JsonIgnore
    @Override
    public String identityKey() {
      return IdentityKey.choice(relationshipKind).canonical();
    }

    @Override
    public ConceptRelationship mapRefs(UnaryOperator<Ref> mapping) {
      return new ConceptRelationship(
          mapping.apply(from), mapping.apply(to), directed, relationshipKind);
    }
  }
}
