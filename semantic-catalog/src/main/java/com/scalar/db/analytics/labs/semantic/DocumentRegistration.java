/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import java.util.List;

/**
 * What registering a document recorded (see {@link SemanticCatalogStore#registerDocument}). Every
 * outcome is a success.
 */
public sealed interface DocumentRegistration {
  /** The URN of the document's Input Resource. */
  String inputResource();

  /** The URN of the document's latest Input Observation, which holds after the registration. */
  String inputObservation();

  /**
   * Nothing was recorded, because the text equals the document's latest observation.
   *
   * @param inputResource the URN of the document's Input Resource
   * @param inputObservation the URN of the latest Input Observation, which still holds
   */
  record Unchanged(String inputResource, String inputObservation) implements DocumentRegistration {}

  /**
   * A new Input Observation was recorded with its Observation Event in one commit; the Input
   * Resource is created with it when the document is new.
   *
   * @param inputResource the URN of the document's Input Resource
   * @param inputObservation the URN of the new Input Observation
   * @param commit the number of the commit written
   * @param newInputResource whether the Input Resource was created by this registration
   * @param buildTasks the build tasks the observation created or added a trigger to, for the
   *     Generators that read documents
   */
  record Observed(
      String inputResource,
      String inputObservation,
      long commit,
      boolean newInputResource,
      List<Long> buildTasks)
      implements DocumentRegistration {
    public Observed {
      buildTasks = List.copyOf(buildTasks);
    }
  }
}
