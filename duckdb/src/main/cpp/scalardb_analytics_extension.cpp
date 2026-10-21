/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

#define DUCKDB_EXTENSION_MAIN

#include "catalog_client.hpp"
#include "duckdb.hpp"
#include "protobuf_wire.hpp"

namespace duckdb {

namespace {

constexpr const char *HEALTH_CHECK_METHOD = "grpc.health.v1.Health/Check";

// Calls the host's catalog health check. An empty request asks for the overall serving status.
string CatalogStatus() {
	scalardb_analytics::CatalogScope scope;
	auto result = scope.Call(HEALTH_CHECK_METHOD, "");
	if (!result.ok) {
		throw IOException("The ScalarDB Analytics catalog call failed: %s",
		                  scalardb_analytics::StatusCodeName(result.status_code));
	}
	// grpc.health.v1.HealthCheckResponse: 1 = status (1 = SERVING, 2 = NOT_SERVING).
	uint64_t status = 0;
	scalardb_analytics::ProtobufReader reader(result.message);
	scalardb_analytics::ProtobufField field;
	while (reader.Next(field)) {
		if (field.number == 1 && field.wire_type == scalardb_analytics::ProtobufWireType::VARINT) {
			status = field.varint;
		}
	}
	switch (status) {
	case 1:
		return "SERVING";
	case 2:
		return "NOT_SERVING";
	default:
		return "UNKNOWN";
	}
}

// Evaluated once per chunk so that parallel pipelines call the host from DuckDB worker threads.
void ScalarDbAnalyticsCatalogStatus(DataChunk &, ExpressionState &, Vector &result) {
	result.SetVectorType(VectorType::CONSTANT_VECTOR);
	result.SetValue(0, Value(CatalogStatus()));
}

void ScalarDbAnalyticsVersion(DataChunk &, ExpressionState &, Vector &result) {
	result.SetVectorType(VectorType::CONSTANT_VECTOR);
	result.SetValue(0, Value("1"));
}

void ScalarDbAnalyticsIsReadOnlyQuery(DataChunk &input, ExpressionState &state, Vector &result) {
	auto &database = DatabaseInstance::GetDatabase(state.GetContext());
	UnaryExecutor::Execute<string_t, bool>(input.data[0], result, input.size(), [&](string_t sql) {
		Connection parser_connection(database);
		auto statements = parser_connection.ExtractStatements(sql.GetString());
		return statements.size() == 1 && statements[0]->type == StatementType::SELECT_STATEMENT;
	});
}

void LoadInternal(ExtensionLoader &loader) {
	ScalarFunction version_function("scalardb_analytics_version", {}, LogicalType::VARCHAR,
	                                ScalarDbAnalyticsVersion);
	loader.RegisterFunction(version_function);

	ScalarFunction read_only_query_function("scalardb_analytics_is_read_only_query", {LogicalType::VARCHAR},
	                                        LogicalType::BOOLEAN, ScalarDbAnalyticsIsReadOnlyQuery);
	loader.RegisterFunction(read_only_query_function);

	ScalarFunction catalog_status_function("scalardb_analytics_catalog_status", {}, LogicalType::VARCHAR,
	                                       ScalarDbAnalyticsCatalogStatus);
	catalog_status_function.SetVolatile();
	catalog_status_function.SetFallible();
	loader.RegisterFunction(catalog_status_function);
}

} // namespace

} // namespace duckdb

extern "C" {

DUCKDB_CPP_EXTENSION_ENTRY(scalardb_analytics, loader) {
	duckdb::LoadInternal(loader);
}
}
