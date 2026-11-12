/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ConfirmationPath;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One version of a Fact Record: the result of applying the Fact's events up to one of them.
 *
 * @param urn the Fact Revision's URN, which names the Fact and the event that produced it
 * @param event the log position of the Fact Event that produced this revision
 * @param dependencies the dependency references in effect: those of the latest acceptance
 * @param checks the validation result of the latest acceptance
 * @param provenance the provenance of the latest acceptance
 * @param confirmationPath how the latest confirmation was made; absent before the first one
 * @param confirmationBasis the log record the latest confirmation rests on; absent before the first
 *     one
 * @param lastConfirmedOrChecked the commit of the latest confirmation or acceptance
 */
public record FactRevision(
    String urn,
    String fact,
    String factAddress,
    JsonNode value,
    FactState state,
    LogPosition event,
    List<Dependency> dependencies,
    List<Check> checks,
    JsonNode provenance,
    @Nullable ConfirmationPath confirmationPath,
    @Nullable String confirmationBasis,
    long lastConfirmedOrChecked) {
  public FactRevision {
    dependencies = List.copyOf(dependencies);
    checks = List.copyOf(checks);
  }
}
