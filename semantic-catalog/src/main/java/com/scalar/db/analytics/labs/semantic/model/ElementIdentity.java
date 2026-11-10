/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
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
  @JsonSubTypes.Type(value = ElementIdentity.Edge.class, name = "edge")
})
public sealed interface ElementIdentity {
  ElementKind kind();

  /**
   * A concept, Measure, Dimension, or business term. It is the same as an existing element only
   * when the proposer points to that element by its URN, so an element with this identity is always
   * new.
   */
  record Unique(ElementKind kind) implements ElementIdentity {
    public Unique {
      if (kind.edge() || kind.technical()) {
        throw new IllegalArgumentException(kind + " is not identified by pointing");
      }
    }
  }

  /**
   * A Dataset or a Field. It is the same as an existing element of its kind when their
   * technical-reference Facts that are neither rejected nor retired point to the same physical
   * object, so a table rename keeps the element when only the technical reference is replaced.
   *
   * @param parent the Dataset of a Field; absent for a Dataset
   * @param technicalReference the physical object the element refers to, which is also the matching
   *     value of the element's technical-reference Fact
   */
  record PhysicalObject(ElementKind kind, @Nullable Ref parent, String technicalReference)
      implements ElementIdentity {
    public PhysicalObject {
      if (!kind.technical()) {
        throw new IllegalArgumentException(kind + " is not identified by a technical reference");
      }
      if ((kind == ElementKind.FIELD) != (parent != null)) {
        throw new IllegalArgumentException("A Field, and only a Field, has a parent Dataset");
      }
    }
  }

  /**
   * A join, an identifier correspondence, or a concept relationship. It is the same as an existing
   * edge when kind, ends, direction, and identifying part match; an undirected edge ignores the
   * order of its ends. A different identifying part, such as another join condition, is a different
   * edge.
   *
   * @param from the end the edge starts from, or one end of an undirected edge
   * @param to the end the edge goes to, or the other end of an undirected edge
   * @param identityKey the identity key of the identifying part, which decides sameness
   * @param identifyingPart the identifying part as stated, such as the pairs of corresponding
   *     Fields of a join condition
   */
  record Edge(
      ElementKind kind,
      Ref from,
      Ref to,
      boolean directed,
      IdentityKey identityKey,
      JsonNode identifyingPart)
      implements ElementIdentity {
    public Edge {
      if (!kind.edge()) {
        throw new IllegalArgumentException(kind + " is not an edge");
      }
    }
  }
}
