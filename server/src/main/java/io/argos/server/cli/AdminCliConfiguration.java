/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.config.ArgosProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The application context of {@code argos-admin}: the server configuration ({@link
 * ArgosProperties}, bound from the same sources as the server: packaged {@code application.yml},
 * the operator's configuration file, {@code ARGOS_*} environment, system properties, command line)
 * and nothing else. No component scan and no auto-configuration: no web server, gRPC listener, WAL,
 * scheduler or start phase runs, so the command can run next to a running server.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArgosProperties.class)
class AdminCliConfiguration {}
