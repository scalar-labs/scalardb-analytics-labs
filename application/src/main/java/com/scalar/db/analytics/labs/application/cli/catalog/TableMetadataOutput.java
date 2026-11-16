/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.scalar.db.analytics.labs.application.metadata.TableMetadataRetrieval;
import com.scalar.db.analytics.labs.semantic.TableMetadataRegistration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The JSON form of what a table metadata retrieval did for one data source.
 *
 * @param tables one entry per registered table, in listed order
 */
record TableMetadataOutput(List<TableOutput> tables) {
  /**
   * One table's result.
   *
   * @param recorded what the Semantic Catalog recorded: {@code observation}, {@code
   *     derived-record}, {@code nothing} for a version no newer than one it has, or {@code
   *     not-retrieved} when the connector could not read the table
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record TableOutput(
      List<String> table,
      @Nullable String tableIdentifier,
      @Nullable Long version,
      String recorded,
      @Nullable String inputResource,
      @Nullable String inputObservation,
      @Nullable String reason) {}

  static TableMetadataOutput of(TableMetadataRetrieval.Outcome outcome) {
    return new TableMetadataOutput(
        outcome.tables().stream().map(TableMetadataOutput::output).toList());
  }

  private static TableOutput output(TableMetadataRetrieval.TableOutcome outcome) {
    return switch (outcome) {
      case TableMetadataRetrieval.TableOutcome.NotRetrieved notRetrieved ->
          new TableOutput(
              notRetrieved.table(), null, null, "not-retrieved", null, null, notRetrieved.reason());
      case TableMetadataRetrieval.TableOutcome.Registered registered ->
          switch (registered.registration()) {
            case TableMetadataRegistration.Observed observed ->
                new TableOutput(
                    registered.table(),
                    registered.tableIdentifier(),
                    registered.version(),
                    "observation",
                    observed.inputResource(),
                    observed.inputObservation(),
                    null);
            case TableMetadataRegistration.DerivedRecordOnly derived ->
                new TableOutput(
                    registered.table(),
                    registered.tableIdentifier(),
                    registered.version(),
                    "derived-record",
                    derived.inputResource(),
                    derived.inputObservation(),
                    null);
            case TableMetadataRegistration.NotNewer ignored ->
                new TableOutput(
                    registered.table(),
                    registered.tableIdentifier(),
                    registered.version(),
                    "nothing",
                    null,
                    null,
                    null);
          };
    };
  }
}
