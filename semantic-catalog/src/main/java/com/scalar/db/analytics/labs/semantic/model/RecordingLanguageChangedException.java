/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

/**
 * An operation on a domain was not accepted, because the primary language declared for it is not
 * the recording language it was created with, which never changes (see {@code
 * docs/semantic-catalog/languages.md}, "Descriptions: one recording language"). Nothing is written.
 */
public final class RecordingLanguageChangedException extends SemanticCatalogException {
  private static final long serialVersionUID = 1L;

  public RecordingLanguageChangedException(String domain, String recording, String declared) {
    super(
        "Domain "
            + domain
            + " records in "
            + recording
            + ", but the primary language declared for it is "
            + declared
            + "; a domain's recording language, its primary language, cannot change");
  }
}
