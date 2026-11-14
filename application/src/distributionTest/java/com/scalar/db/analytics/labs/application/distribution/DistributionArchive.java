/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** The distribution archive under test, as Gradle passes it to the distribution tests. */
final class DistributionArchive {
  private DistributionArchive() {}

  /** Unpacks the archive into a new directory and returns the distribution's root directory. */
  static Path unpack(Path directory) throws Exception {
    var archive = Path.of(DistributionSmokeTest.requiredProperty("distribution.archive"));
    var unpacked = Files.createDirectories(directory);
    var tar =
        new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", unpacked.toString())
            .inheritIO()
            .start();
    assertThat(tar.waitFor(5, TimeUnit.MINUTES) && tar.exitValue() == 0).isTrue();
    try (var entries = Files.list(unpacked)) {
      var roots = entries.toList();
      assertThat(roots).hasSize(1);
      return roots.get(0);
    }
  }
}
