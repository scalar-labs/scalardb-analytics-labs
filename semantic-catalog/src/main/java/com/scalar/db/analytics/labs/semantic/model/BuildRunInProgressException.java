/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

/**
 * Running a domain's build tasks was not accepted, because another process holds the domain's
 * builder semaphore and is running them. Answers, reads, and other operations proceed meanwhile.
 */
public final class BuildRunInProgressException extends SemanticCatalogException {
  private static final long serialVersionUID = 1L;

  public BuildRunInProgressException(String domain) {
    super("Another process is running the build tasks of domain " + domain);
  }
}
