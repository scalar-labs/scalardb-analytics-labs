/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

/**
 * The Generator, its version, and the build task an acceptance or retirement proposal came from.
 */
public record Provenance(String generator, String version, String task) {}
