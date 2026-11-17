/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import java.util.List;

/**
 * What registering usage feedback recorded (see {@link
 * SemanticCatalogStore#registerUsageFeedback}). Feedback is never revised, so each registration
 * records a new Input Resource with its one observation.
 *
 * @param inputResource the URN of the feedback's new Input Resource
 * @param inputObservation the URN of its observation
 * @param catalogSnapshot the URN of the Catalog Snapshot the registration wrote
 * @param buildTasks the build tasks the registration created or added a trigger to
 */
public record FeedbackRegistration(
    String inputResource, String inputObservation, String catalogSnapshot, List<Long> buildTasks) {
  public FeedbackRegistration {
    buildTasks = List.copyOf(buildTasks);
  }
}
