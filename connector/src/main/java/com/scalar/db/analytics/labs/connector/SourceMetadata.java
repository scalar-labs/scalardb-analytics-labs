/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.connector;

/**
 * What a Connector read about one table, in a shape only that Connector defines.
 *
 * @param connector the Connector that produced it
 * @param document the JSON document, as text
 */
public record SourceMetadata(String connector, String document) {}
