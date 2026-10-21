/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.scalar.db.analytics.grpc.generated.error.v1.AnalyticsError;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;

/**
 * Keeps credentials inside the embedded catalog.
 *
 * <p>Response messages leave with every provider payload redacted. Failures leave with only their
 * gRPC code and upstream error code: upstream descriptions and error metadata can quote provider
 * payloads, so they are dropped rather than filtered.
 */
final class CredentialRedactingInterceptor implements ServerInterceptor {
  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    return next.startCall(new RedactingServerCall<>(call), headers);
  }

  private static final class RedactingServerCall<ReqT, RespT>
      extends ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {
    RedactingServerCall(ServerCall<ReqT, RespT> delegate) {
      super(delegate);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void sendMessage(RespT message) {
      if (message instanceof Message protobufMessage) {
        super.sendMessage((RespT) ProviderPayloads.redactMessages(protobufMessage));
      } else {
        super.sendMessage(message);
      }
    }

    @Override
    public void close(Status status, Metadata trailers) {
      if (status.isOk()) {
        super.close(status, trailers);
        return;
      }
      var sanitized = sanitize(status, trailers);
      super.close(sanitized.getStatus(), sanitized.getTrailers());
    }
  }

  private static io.grpc.StatusRuntimeException sanitize(Status status, Metadata trailers) {
    var errorCode = upstreamErrorCode(status, trailers);
    var rpcStatus =
        com.google.rpc.Status.newBuilder()
            .setCode(status.getCode().value())
            .setMessage(errorCode.isEmpty() ? status.getCode().name() : errorCode);
    if (!errorCode.isEmpty()) {
      rpcStatus.addDetails(Any.pack(AnalyticsError.newBuilder().setErrorCode(errorCode).build()));
    }
    return StatusProto.toStatusRuntimeException(rpcStatus.build());
  }

  private static String upstreamErrorCode(Status status, Metadata trailers) {
    var rpcStatus = StatusProto.fromStatusAndTrailers(status, trailers);
    for (Any detail : rpcStatus.getDetailsList()) {
      if (detail.is(AnalyticsError.class)) {
        try {
          return detail.unpack(AnalyticsError.class).getErrorCode();
        } catch (InvalidProtocolBufferException e) {
          return "";
        }
      }
    }
    return "";
  }
}
