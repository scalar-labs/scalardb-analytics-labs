/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Normalization;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * The matching condition of an identifier correspondence: how values of the Field set at one end
 * are matched to values of the Field set at the other (see {@code
 * docs/semantic-catalog/record-model.md}, "Elements and predicates").
 *
 * <p>The condition is the correspondence's identifying part, so it decides whether two proposals
 * are the same edge. Only the structure below can be stated: pairs of Fields, each side with
 * normalization steps from a closed list, never free expressions.
 *
 * @param hops the steps from the starting end to the other end, in order. A direct correspondence
 *     has one hop. More hops go through intermediate Fields, such as a mapping table: each hop
 *     matches the Fields reached so far to the Fields of the next step.
 */
public record MatchingCondition(List<Hop> hops) {
  public MatchingCondition {
    hops = List.copyOf(hops);
    if (hops.isEmpty()) {
      throw new IllegalArgumentException("A matching condition has at least one hop");
    }
  }

  /** A direct matching condition of one hop. */
  public static MatchingCondition direct(Pair... pairs) {
    return new MatchingCondition(List.of(new Hop(List.of(pairs))));
  }

  /**
   * One step of a matching condition: pairs of corresponding Fields, matched together. The order of
   * the pairs carries no meaning.
   */
  public record Hop(List<Pair> pairs) {
    public Hop {
      pairs = List.copyOf(pairs);
      if (pairs.isEmpty()) {
        throw new IllegalArgumentException(
            "A hop of a matching condition pairs at least one Field");
      }
      var froms = new HashSet<Ref>();
      var tos = new HashSet<Ref>();
      for (var pair : pairs) {
        if (!froms.add(pair.from().field()) || !tos.add(pair.to().field())) {
          throw new IllegalArgumentException(
              "A Field takes part in at most one pair on each side of a hop");
        }
      }
    }
  }

  /** Two Fields whose values, after their normalization, are compared for equality. */
  public record Pair(Side from, Side to) {
    public static Pair of(Ref from, Ref to) {
      return new Pair(new Side(from, List.of()), new Side(to, List.of()));
    }
  }

  /**
   * One side of a pair.
   *
   * @param normalization the steps applied to the Field's value before comparison, in order
   */
  public record Side(Ref field, List<Normalization> normalization) {
    public Side {
      normalization = List.copyOf(normalization);
    }

    public static Side of(Ref field, Normalization... normalization) {
      return new Side(field, List.of(normalization));
    }
  }

  /** The Fields of the end the correspondence starts from: the starting side of the first hop. */
  public List<Ref> fromFields() {
    return hops.getFirst().pairs().stream().map(pair -> pair.from().field()).toList();
  }

  /** The Fields of the end the correspondence goes to: the other side of the last hop. */
  public List<Ref> toFields() {
    return hops.getLast().pairs().stream().map(pair -> pair.to().field()).toList();
  }

  /** Every Field the condition names, ends and intermediate steps alike. */
  public List<Ref> fields() {
    return hops.stream()
        .flatMap(hop -> hop.pairs().stream())
        .flatMap(pair -> Stream.of(pair.from().field(), pair.to().field()))
        .distinct()
        .toList();
  }

  /** The same condition with each Field reference replaced. */
  public MatchingCondition mapRefs(UnaryOperator<Ref> mapping) {
    return new MatchingCondition(
        hops.stream()
            .map(
                hop ->
                    new Hop(
                        hop.pairs().stream()
                            .map(
                                pair ->
                                    new Pair(
                                        new Side(
                                            mapping.apply(pair.from().field()),
                                            pair.from().normalization()),
                                        new Side(
                                            mapping.apply(pair.to().field()),
                                            pair.to().normalization())))
                            .toList()))
            .toList());
  }

  /**
   * The canonical form stored and compared as the correspondence's identity key: the hops in order,
   * each with its pairs sorted, so the order in which pairs are stated does not make another edge
   * while a different Field or normalization step does. Every reference must be resolved to a URN.
   */
  public String canonical() {
    var nodes = JsonNodeFactory.instance;
    var array = nodes.arrayNode();
    for (var hop : hops) {
      var pairs =
          hop.pairs().stream()
              .map(MatchingCondition::canonicalPair)
              .sorted(Comparator.comparing(ObjectNode::toString))
              .toList();
      var hopNode = array.addArray();
      pairs.forEach(hopNode::add);
    }
    return array.toString();
  }

  private static ObjectNode canonicalPair(Pair pair) {
    var node = JsonNodeFactory.instance.objectNode();
    node.set("from", canonicalSide(pair.from()));
    node.set("to", canonicalSide(pair.to()));
    return node;
  }

  private static ObjectNode canonicalSide(Side side) {
    var node = JsonNodeFactory.instance.objectNode();
    node.put("field", side.field().urnValue());
    ArrayNode steps = node.putArray("normalization");
    side.normalization().forEach(step -> steps.add(step.name()));
    return node;
  }
}
