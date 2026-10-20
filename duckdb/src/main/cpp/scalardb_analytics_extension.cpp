/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

#define DUCKDB_EXTENSION_MAIN

#include "duckdb.hpp"

namespace duckdb {

namespace {

void ScalarDbAnalyticsVersion(DataChunk &, ExpressionState &, Vector &result) {
	result.SetVectorType(VectorType::CONSTANT_VECTOR);
	result.SetValue(0, Value("1"));
}

void LoadInternal(ExtensionLoader &loader) {
	ScalarFunction version_function("scalardb_analytics_version", {}, LogicalType::VARCHAR,
	                                ScalarDbAnalyticsVersion);
	loader.RegisterFunction(version_function);
}

} // namespace

} // namespace duckdb

extern "C" {

DUCKDB_CPP_EXTENSION_ENTRY(scalardb_analytics, loader) {
	duckdb::LoadInternal(loader);
}
}
