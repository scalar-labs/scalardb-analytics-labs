/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

#include "catalog_client.hpp"

#include "duckdb.hpp"
#include "protobuf_wire.hpp"
#include <climits>
#include <cstddef>
#include <mutex>

namespace scalardb_analytics {

namespace {

// The registered host. The provider is copied so the host may release its table after
// registration. Unregistration is refused while scopes are open, which keeps the copied callbacks
// valid for every open scope without holding the lock during calls.
struct HostRegistry {
	std::mutex mutex;
	bool registered = false;
	scalardb_analytics_catalog_provider catalog {};
	uint64_t open_scopes = 0;
};

HostRegistry &Registry() {
	static HostRegistry registry;
	return registry;
}

scalardb_analytics_catalog_provider OpenScope(uint64_t &provider_scope) {
	auto &registry = Registry();
	scalardb_analytics_catalog_provider catalog;
	{
		std::lock_guard<std::mutex> guard(registry.mutex);
		if (!registry.registered) {
			throw duckdb::IOException("The ScalarDB Analytics catalog is not available.");
		}
		catalog = registry.catalog;
		registry.open_scopes++;
	}
	provider_scope = catalog.begin_scope(catalog.ctx);
	if (provider_scope == 0) {
		std::lock_guard<std::mutex> guard(registry.mutex);
		registry.open_scopes--;
		throw duckdb::IOException("The ScalarDB Analytics catalog refused the request.");
	}
	return catalog;
}

} // namespace

CatalogScope::CatalogScope() : catalog(), provider_scope(0) {
	catalog = OpenScope(provider_scope);
}

CatalogScope::~CatalogScope() {
	catalog.end_scope(catalog.ctx, provider_scope);
	auto &registry = Registry();
	std::lock_guard<std::mutex> guard(registry.mutex);
	registry.open_scopes--;
}

CatalogCallResult CatalogScope::Call(const std::string &method, const std::string &request) {
	const uint8_t *data = nullptr;
	auto size = catalog.call(catalog.ctx, provider_scope, method.c_str(),
	                         reinterpret_cast<const uint8_t *>(request.data()), static_cast<int64_t>(request.size()),
	                         &data);
	if (size == INT64_MIN || (size != 0 && data == nullptr)) {
		throw duckdb::IOException("The ScalarDB Analytics catalog returned an invalid response.");
	}
	auto length = static_cast<size_t>(size < 0 ? -size : size);
	std::string bytes(reinterpret_cast<const char *>(data), length);
	if (size >= 0) {
		return CatalogCallResult {true, std::move(bytes), 0, ""};
	}

	// google.rpc.Status: 1 = code, 2 = message.
	int32_t code = 2; // UNKNOWN
	std::string message;
	ProtobufReader reader(bytes);
	ProtobufField field;
	while (reader.Next(field)) {
		if (field.number == 1 && field.wire_type == ProtobufWireType::VARINT) {
			code = static_cast<int32_t>(field.varint);
		} else if (field.number == 2 && field.wire_type == ProtobufWireType::LENGTH_DELIMITED) {
			message = field.bytes;
		}
	}
	return CatalogCallResult {false, "", code, std::move(message)};
}

const char *StatusCodeName(int32_t code) {
	static const char *const names[] = {"OK",
	                                    "CANCELLED",
	                                    "UNKNOWN",
	                                    "INVALID_ARGUMENT",
	                                    "DEADLINE_EXCEEDED",
	                                    "NOT_FOUND",
	                                    "ALREADY_EXISTS",
	                                    "PERMISSION_DENIED",
	                                    "RESOURCE_EXHAUSTED",
	                                    "FAILED_PRECONDITION",
	                                    "ABORTED",
	                                    "OUT_OF_RANGE",
	                                    "UNIMPLEMENTED",
	                                    "INTERNAL",
	                                    "UNAVAILABLE",
	                                    "DATA_LOSS",
	                                    "UNAUTHENTICATED"};
	if (code < 0 || code >= static_cast<int32_t>(sizeof(names) / sizeof(names[0]))) {
		return "UNKNOWN";
	}
	return names[code];
}

} // namespace scalardb_analytics

extern "C" {

__attribute__((visibility("default"))) int32_t
scalardb_analytics_register_host(const scalardb_analytics_host_api *host) {
	if (host == nullptr) {
		return SCALARDB_ANALYTICS_HOST_INVALID_ARGUMENT;
	}
	// Version 1 hosts provide at least the members up to the catalog provider. Members appended later
	// must be read only when struct_size covers them.
	constexpr size_t minimum_host_size =
	    offsetof(scalardb_analytics_host_api, catalog) + sizeof(scalardb_analytics_host_api::catalog);
	if (host->abi_version != SCALARDB_ANALYTICS_HOST_ABI_VERSION || host->struct_size < minimum_host_size) {
		return SCALARDB_ANALYTICS_HOST_UNSUPPORTED_ABI;
	}
	auto catalog = host->catalog;
	if (catalog == nullptr || catalog->begin_scope == nullptr || catalog->end_scope == nullptr ||
	    catalog->call == nullptr) {
		return SCALARDB_ANALYTICS_HOST_INCOMPLETE;
	}
	auto &registry = scalardb_analytics::Registry();
	std::lock_guard<std::mutex> guard(registry.mutex);
	if (registry.registered) {
		return SCALARDB_ANALYTICS_HOST_ALREADY_REGISTERED;
	}
	registry.catalog = *catalog;
	registry.registered = true;
	return SCALARDB_ANALYTICS_HOST_OK;
}

__attribute__((visibility("default"))) int32_t scalardb_analytics_unregister_host(void) {
	auto &registry = scalardb_analytics::Registry();
	std::lock_guard<std::mutex> guard(registry.mutex);
	if (!registry.registered) {
		return SCALARDB_ANALYTICS_HOST_NOT_REGISTERED;
	}
	if (registry.open_scopes != 0) {
		return SCALARDB_ANALYTICS_HOST_BUSY;
	}
	registry.registered = false;
	registry.catalog = {};
	return SCALARDB_ANALYTICS_HOST_OK;
}
}
