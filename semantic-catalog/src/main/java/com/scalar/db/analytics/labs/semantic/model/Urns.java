/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import java.util.regex.Pattern;

/**
 * The form of URNs: {@code urn:scalardb:analytics:<domain>:<record kind>:<identifier>} (see {@code
 * docs/semantic-catalog/record-model.md}, "Identity and URNs").
 *
 * <p>The domain comes first after the namespace, so a domain identifier is embedded in every URN
 * the domain issues and must keep that form parseable.
 */
public final class Urns {
  /** The namespace of every URN; the product defines no abbreviation of its name. */
  public static final String PREFIX = "urn:scalardb:analytics:";

  /**
   * The form of a domain identifier: lowercase letters, digits, and hyphens, starting with a letter
   * or digit, at most 63 characters. It contains no colon, so the domain can be read back from a
   * URN.
   */
  public static final Pattern DOMAIN = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

  private Urns() {}

  /**
   * Checks that a caller-supplied domain identifier has the {@linkplain #DOMAIN required form}.
   *
   * @throws IllegalArgumentException when it does not
   */
  public static void requireDomain(String domain) {
    if (!DOMAIN.matcher(domain).matches()) {
      throw new IllegalArgumentException(
          "A domain identifier consists of lowercase letters, digits, and hyphens: " + domain);
    }
  }
}
