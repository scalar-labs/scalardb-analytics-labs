/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * The handoff from the host application to the ScalarDB Analytics DuckDB extension.
 *
 * The extension does not load or start product components itself. After loading the extension, the
 * host registers one host table that carries the services the extension may call. The extension
 * depends only on this ABI, so any host that provides the table can use it.
 *
 * Catalog provider contract:
 * - Calls are unary gRPC calls in serialized form, identified by the fully qualified gRPC method
 *   name. The provider decides which methods it serves; read-only metadata methods are expected.
 * - begin_scope opens a scope that owns response memory and returns a nonzero scope ID, or 0 if the
 *   provider refuses. end_scope releases every response returned in that scope.
 * - call returns the size of the serialized response message and stores a pointer to it in
 *   out_data. A negative result -n means the n bytes at out_data are a serialized google.rpc.Status
 *   describing the failure. The data stays valid until end_scope for that scope. INT64_MIN and a
 *   null pointer with a nonzero size are invalid.
 * - Callbacks may be invoked concurrently from any thread, including DuckDB worker threads that the
 *   host did not create. They must not unwind exceptions or longjmp across this boundary.
 * - Callbacks must stay callable from registration until scalardb_analytics_unregister_host
 *   returns SCALARDB_ANALYTICS_HOST_OK.
 *
 * Evolution rule: compatible additions, such as connector library handles, are appended to
 * scalardb_analytics_host_api without changing SCALARDB_ANALYTICS_HOST_ABI_VERSION. The host sets
 * struct_size to the size of the table it built, and the extension reads an appended member only
 * when struct_size covers it, so a host built against an older header stays accepted. The ABI
 * version changes only for incompatible changes.
 */

#ifndef SCALARDB_ANALYTICS_HOST_H
#define SCALARDB_ANALYTICS_HOST_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SCALARDB_ANALYTICS_HOST_ABI_VERSION 1u

typedef uint64_t (*scalardb_analytics_begin_scope_fn)(void *ctx);
typedef void (*scalardb_analytics_end_scope_fn)(void *ctx, uint64_t scope);
typedef int64_t (*scalardb_analytics_call_fn)(void *ctx, uint64_t scope, const char *method,
                                              const uint8_t *request, int64_t request_size,
                                              const uint8_t **out_data);

typedef struct scalardb_analytics_catalog_provider {
	void *ctx;
	scalardb_analytics_begin_scope_fn begin_scope;
	scalardb_analytics_end_scope_fn end_scope;
	scalardb_analytics_call_fn call;
} scalardb_analytics_catalog_provider;

typedef struct scalardb_analytics_host_api {
	uint32_t abi_version;
	uint32_t struct_size;
	const scalardb_analytics_catalog_provider *catalog;
} scalardb_analytics_host_api;

typedef enum scalardb_analytics_host_result {
	SCALARDB_ANALYTICS_HOST_OK = 0,
	SCALARDB_ANALYTICS_HOST_INVALID_ARGUMENT = 1,
	SCALARDB_ANALYTICS_HOST_UNSUPPORTED_ABI = 2,
	SCALARDB_ANALYTICS_HOST_INCOMPLETE = 3,
	SCALARDB_ANALYTICS_HOST_ALREADY_REGISTERED = 4,
	SCALARDB_ANALYTICS_HOST_NOT_REGISTERED = 5,
	SCALARDB_ANALYTICS_HOST_BUSY = 6
} scalardb_analytics_host_result;

/*
 * Registers the host table. The extension copies the table and the catalog provider, so the caller
 * may release that memory after this returns. Only one host can be registered at a time.
 */
int32_t scalardb_analytics_register_host(const scalardb_analytics_host_api *host);

/*
 * Unregisters the host. Returns SCALARDB_ANALYTICS_HOST_BUSY while any catalog scope is open; after
 * SCALARDB_ANALYTICS_HOST_OK the extension no longer invokes the host's callbacks.
 */
int32_t scalardb_analytics_unregister_host(void);

#ifdef __cplusplus
}
#endif

#endif
