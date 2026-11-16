/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A read along the hierarchy the Physical Catalog uses: data source, namespace, and table (see
 * {@code docs/semantic-catalog/public-api.md}, "Reading along the hierarchy").
 *
 * <p>A Dataset is a table and its Fields are columns. A Measure belongs to the table of the Dataset
 * its selected base-dataset Fact names, and an identifier correspondence to the tables of its ends'
 * Fields. Every other element, and a Measure without a selected base Dataset, is domain-wide.
 */
public final class Hierarchy {
  private Hierarchy() {}

  /** How far a read above a table goes. */
  public enum Depth {
    /** The position and its direct children, each with its counts. */
    CHILDREN,
    /** The position and every level below it, down to each table unit. */
    RECURSIVE
  }

  /**
   * What a level holds.
   *
   * @param confirmed the Fact Addresses with a selected Fact
   * @param awaitingReview the review items, as the review list returns them
   * @param missingCorePredicates the closed-core predicates of its elements with missing knowledge:
   *     no Fact Address, or no Fact Address with a selected, eligible, or {@code candidate} Fact
   */
  public record Counts(int confirmed, int awaitingReview, int missingCorePredicates) {}

  /**
   * An element with the result of requesting all its predicates.
   *
   * @param physicalCatalogReference the canonical form of a Dataset's or a Field's Physical Catalog
   *     reference; absent for other kinds
   */
  public record ElementKnowledge(
      Knowledge.Element element,
      @Nullable String physicalCatalogReference,
      Knowledge.Answer predicates) {}

  /**
   * A table read as one unit.
   *
   * @param dataset the Dataset that is the table
   * @param fields its Fields
   * @param measures the Measures based on it
   * @param correspondences the identifier correspondences it is an end of
   */
  public record TableUnit(
      ElementKnowledge dataset,
      List<ElementKnowledge> fields,
      List<ElementKnowledge> measures,
      List<ElementKnowledge> correspondences) {
    public TableUnit {
      fields = List.copyOf(fields);
      measures = List.copyOf(measures);
      correspondences = List.copyOf(correspondences);
    }
  }

  /** One level of the hierarchy, with its counts. */
  public sealed interface Level {
    Counts counts();
  }

  /**
   * The domain root.
   *
   * @param dataSources the data sources; their namespaces are listed only in a recursive read
   * @param domainWide the domain-wide elements
   */
  public record Root(Counts counts, List<DataSource> dataSources, List<ElementKnowledge> domainWide)
      implements Level {
    public Root {
      dataSources = List.copyOf(dataSources);
      domainWide = List.copyOf(domainWide);
    }
  }

  /**
   * A data source, named by its registration.
   *
   * @param namespaces its namespaces; empty when the data source is a child the read does not go
   *     into
   */
  public record DataSource(String registration, Counts counts, List<Namespace> namespaces)
      implements Level {
    public DataSource {
      namespaces = List.copyOf(namespaces);
    }
  }

  /**
   * A namespace of a data source.
   *
   * @param tables its tables; empty when the namespace is a child the read does not go into
   */
  public record Namespace(String registration, String namespace, Counts counts, List<Table> tables)
      implements Level {
    public Namespace {
      tables = List.copyOf(tables);
    }
  }

  /**
   * A table.
   *
   * @param canonical the table's canonical form, such as {@code shop.public.orders}
   * @param unit the table unit; absent when the table is a child the read does not go into, or when
   *     its Dataset's existence is not selected yet and only review items place it here
   */
  public record Table(
      String registration,
      String namespace,
      String table,
      String canonical,
      Counts counts,
      @Nullable TableUnit unit)
      implements Level {}
}
