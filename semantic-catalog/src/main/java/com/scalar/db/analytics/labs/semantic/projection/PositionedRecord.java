/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;

/**
 * A log record at its log position: either read from the log, or about to be written there with the
 * position the commit gave it.
 */
public record PositionedRecord(LogPosition position, LogRecord record) {}
