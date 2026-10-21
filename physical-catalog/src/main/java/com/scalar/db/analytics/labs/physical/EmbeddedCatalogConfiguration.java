/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.physical;

import com.scalar.db.analytics.server.config.GrpcServerConfiguration;
import com.scalar.db.analytics.server.config.UseCaseConfiguration;
import com.scalar.db.analytics.server.config.auth.NoOpAuthzConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Assembles the upstream catalog services without the upstream server application.
 *
 * <p>The upstream server's component scan would also start its TCP gRPC listener. This
 * configuration imports only the store, use-case, and service-implementation configuration, so the
 * services are reachable solely through the in-process server owned by {@link PhysicalCatalog}.
 * Authorization is the upstream no-op implementation: the embedding application is the only caller.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
@Import({UseCaseConfiguration.class, GrpcServerConfiguration.class, NoOpAuthzConfiguration.class})
class EmbeddedCatalogConfiguration {}
