/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import org.jspecify.annotations.Nullable;

/**
 * The text of one log record requested in a language and not translated yet (see {@code
 * docs/semantic-catalog/languages.md}, "Translations").
 *
 * @param logRecord the URN of the log record that holds the text
 * @param language the language it is to be translated into
 * @param failure why the last attempt to translate it failed; null while none has
 */
public record QueuedTranslation(String logRecord, String language, @Nullable String failure) {}
