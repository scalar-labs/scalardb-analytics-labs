/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderPayloadsTest {
  @Test
  void removesCredentialsAtEveryDepth() {
    var payload =
        """
        {"type":"scalardb","host":"db","password":"top-secret",
         "configs":{"scalar.db.password":"nested-secret","scalar.db.storage":"jdbc"},
         "accounts":[{"name":"reader","accessToken":"array-secret"}]}
        """;

    var redacted = ProviderPayloads.redactPayload(payload);

    assertThat(redacted)
        .doesNotContain("top-secret")
        .doesNotContain("nested-secret")
        .doesNotContain("array-secret")
        .contains("\"host\":\"db\"")
        .contains("\"scalar.db.storage\":\"jdbc\"")
        .contains("\"name\":\"reader\"");
  }

  @Test
  void replacesUnparsablePayloads() {
    assertThat(ProviderPayloads.redactPayload("not json password=secret")).isEqualTo("{}");
  }
}
