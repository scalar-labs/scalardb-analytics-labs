/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * An identity key: the closed choices and record references in a value that decide whether two
 * records are the same (see {@code docs/semantic-catalog/storage.md}, "Tables").
 *
 * <p>Two Facts of a closed-range predicate at the same Fact Address are the same exactly when their
 * identity keys are equal. A concept relationship's relationship kind is compared in the same form;
 * an identifier correspondence's matching condition has its own {@linkplain
 * MatchingCondition#canonical() canonical form}.
 *
 * <p>Which parts of a value are closed choices or references depends on the predicate, so the
 * proposer, usually a Generator, states the identity key; the store only compares {@linkplain
 * #canonical() canonical forms}. Reference sets, such as the Fields of a key, are compared
 * regardless of order.
 *
 * @param parts the choices and references, in the order the predicate defines
 */
public record IdentityKey(List<Part> parts) {
  public IdentityKey {
    parts = List.copyOf(parts);
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("An identity key has at least one part");
    }
  }

  public static IdentityKey of(Part... parts) {
    return new IdentityKey(List.of(parts));
  }

  /** An identity key of one closed choice. */
  public static IdentityKey choice(String value) {
    return of(new Part.Choice(value));
  }

  /** One part of an identity key. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "part")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Part.Choice.class, name = "choice"),
    @JsonSubTypes.Type(value = Part.Reference.class, name = "reference"),
    @JsonSubTypes.Type(value = Part.References.class, name = "references")
  })
  public sealed interface Part {
    /** A closed choice, such as a row kind or the physical object a technical reference names. */
    record Choice(String value) implements Part {}

    /** A reference to one record, such as a Measure's base Dataset. */
    record Reference(Ref record) implements Part {}

    /** A set of references, such as the Fields of a key, compared regardless of order. */
    record References(List<Ref> records) implements Part {
      public References {
        records = List.copyOf(records);
      }
    }
  }

  /** The same identity key with each reference replaced. */
  public IdentityKey mapRefs(UnaryOperator<Ref> mapping) {
    return new IdentityKey(
        parts.stream()
            .<Part>map(
                part ->
                    switch (part) {
                      case Part.Choice choice -> choice;
                      case Part.Reference reference ->
                          new Part.Reference(mapping.apply(reference.record()));
                      case Part.References references ->
                          new Part.References(references.records().stream().map(mapping).toList());
                    })
            .toList());
  }

  /**
   * The canonical form stored and compared: a JSON array of the parts, with each reference set
   * sorted by URN and without duplicates. Every reference must be resolved to a URN.
   */
  public String canonical() {
    var nodes = JsonNodeFactory.instance;
    var array = nodes.arrayNode();
    for (var part : parts) {
      switch (part) {
        case Part.Choice choice -> array.add(choice.value());
        case Part.Reference reference ->
            array.addObject().put("ref", reference.record().urnValue());
        case Part.References references -> {
          var urns = array.addObject().putArray("refs");
          references.records().stream().map(Ref::urnValue).sorted().distinct().forEach(urns::add);
        }
      }
    }
    return array.toString();
  }
}
