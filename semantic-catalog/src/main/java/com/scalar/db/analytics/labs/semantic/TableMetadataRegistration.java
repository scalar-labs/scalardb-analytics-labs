/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import java.util.List;

/**
 * What registering one table's metadata recorded (see {@link
 * SemanticCatalogStore#registerTableMetadata}). Every outcome is a success.
 */
public sealed interface TableMetadataRegistration {
  /**
   * Nothing was recorded, because the version is no newer than the latest one already received for
   * the table. A retrieval that arrives late therefore cannot roll the table back.
   *
   * @param latestVersion the latest version already received
   */
  record NotNewer(long latestVersion) implements TableMetadataRegistration {}

  /**
   * Only a derived record was recorded, because the source-declared part equals the table's latest
   * Input Observation; only the connector's mapping or the retrieval changed.
   *
   * @param inputResource the URN of the table's physical-schema Input Resource
   * @param inputObservation the URN of the latest Input Observation, which still holds
   */
  record DerivedRecordOnly(String inputResource, String inputObservation)
      implements TableMetadataRegistration {}

  /**
   * A new Input Observation was recorded, with its Observation Event and a derived record, in one
   * commit; the Input Resource is created with it when the table is new.
   *
   * @param inputResource the URN of the table's physical-schema Input Resource
   * @param inputObservation the URN of the new Input Observation
   * @param commit the number of the commit written
   * @param newInputResource whether the Input Resource was created by this registration
   * @param buildTasks the build tasks the commit created or added a trigger to
   */
  record Observed(
      String inputResource,
      String inputObservation,
      long commit,
      boolean newInputResource,
      List<Long> buildTasks)
      implements TableMetadataRegistration {
    public Observed {
      buildTasks = List.copyOf(buildTasks);
    }
  }
}
