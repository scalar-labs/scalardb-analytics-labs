/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include "scalardb_analytics_host.h"

#include <cstdint>
#include <string>

namespace scalardb_analytics {

// The outcome of one catalog call. A failed call carries only the gRPC status code and the
// provider's status message, which the provider guarantees to be free of credentials.
struct CatalogCallResult {
	bool ok;
	std::string message;
	int32_t status_code;
	std::string status_message;
};

// One catalog scope. Responses returned within the scope stay owned by the provider until the
// scope ends, so this class copies them before returning. Opening a scope prevents the host from
// unregistering until the scope ends.
class CatalogScope {
public:
	// Throws duckdb::IOException if no host is registered or the provider refuses the scope.
	CatalogScope();
	~CatalogScope();

	CatalogScope(const CatalogScope &) = delete;
	CatalogScope &operator=(const CatalogScope &) = delete;

	CatalogCallResult Call(const std::string &method, const std::string &request);

private:
	scalardb_analytics_catalog_provider catalog;
	uint64_t provider_scope;
};

// Returns the canonical name of a gRPC status code, such as "UNAVAILABLE".
const char *StatusCodeName(int32_t code);

} // namespace scalardb_analytics
