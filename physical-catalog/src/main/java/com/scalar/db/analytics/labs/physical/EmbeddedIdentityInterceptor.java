/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

import com.scalar.db.analytics.server.grpc.AuthenticationInterceptor;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.UUID;

/**
 * Supplies the identity of the embedding application to the upstream service implementations.
 *
 * <p>The embedded catalog has no network listener, so every caller is code in this process. The
 * upstream services still read the authenticated user from the gRPC context; this interceptor
 * provides one fixed identity in place of token authentication.
 */
final class EmbeddedIdentityInterceptor implements ServerInterceptor {
  static final UUID APPLICATION_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    var context =
        Context.current()
            .withValue(AuthenticationInterceptor.AUTHENTICATED_USER_ID, APPLICATION_USER_ID);
    return Contexts.interceptCall(context, call, headers, next);
  }
}
