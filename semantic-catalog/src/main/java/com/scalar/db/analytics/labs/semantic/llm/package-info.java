/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Reaching a language model: the adapters that are the only part of the Semantic Catalog that knows
 * how a model is reached (see {@code docs/semantic-catalog/generators.md}, "Reaching a language
 * model"). A caller passes a prompt and receives the model's answer, so the provider can change
 * without changing what calls it.
 */
@NullMarked
package com.scalar.db.analytics.labs.semantic.llm;

import org.jspecify.annotations.NullMarked;
