/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator;

/**
 * A deterministic Generator that reads only the store. It finishes in milliseconds, so it runs
 * inside the transaction that records its output and reads the state it is recorded on; a collision
 * runs it again (see {@code docs/semantic-catalog/storage.md}, "Running build tasks").
 */
public interface StoreGenerator extends Generator {
  /**
   * Produces candidates and retirement proposals for the targets the run's triggers point to.
   *
   * @param context what the run reads
   * @param output where the run puts what it produces
   */
  void run(GeneratorContext context, Output output);
}
