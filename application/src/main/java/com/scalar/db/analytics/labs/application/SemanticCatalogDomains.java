/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application;

import com.scalar.db.analytics.labs.semantic.SemanticCatalogStore;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;

/**
 * Creates the workspace's Semantic Catalog domain on first use, until {@code workspace init}
 * creates it (see {@code docs/application.md}, "Workspace").
 */
public final class SemanticCatalogDomains {
  private SemanticCatalogDomains() {}

  /**
   * Creates the domain unless it exists.
   *
   * @throws SemanticCatalogException when the domain neither exists nor could be created
   */
  public static void ensure(SemanticCatalogStore semanticCatalog, String domain) {
    try {
      if (!semanticCatalog.domainExists(domain)) {
        semanticCatalog.createDomain(domain);
      }
    } catch (SemanticCatalogException e) {
      // Another process may have created it between the check and the creation.
      if (!semanticCatalog.domainExists(domain)) {
        throw e;
      }
    }
  }
}
