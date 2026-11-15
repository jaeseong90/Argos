/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.TokenHolder;
import java.io.File;

/**
 * The transport's view of the configuration (10 §15 {@code server.*}, {@code sender.*}), read once
 * when the transport starts ({@code server.*} and {@code sender.*} are not remote keys).
 */
final class TransportSettings {
  /** Default collector port (10 §15 {@code server.address}, 11 §2). */
  static final int DEFAULT_PORT = 9100;

  final String target;
  final boolean tls;
  final File caFile;
  final boolean insecureSkipVerify;
  final TokenHolder token;
  final long connectTimeoutMillis;
  final long deadlineMillis;
  final int batchSize;
  final long flushIntervalMillis;
  final int maxInFlight;
  final boolean gzip;

  TransportSettings(
      String target,
      boolean tls,
      File caFile,
      boolean insecureSkipVerify,
      TokenHolder token,
      long connectTimeoutMillis,
      long deadlineMillis,
      int batchSize,
      long flushIntervalMillis,
      int maxInFlight,
      boolean gzip) {
    this.target = target;
    this.tls = tls;
    this.caFile = caFile;
    this.insecureSkipVerify = insecureSkipVerify;
    this.token = token == null ? TokenHolder.NONE : token;
    this.connectTimeoutMillis = connectTimeoutMillis;
    this.deadlineMillis = deadlineMillis;
    this.batchSize = Math.max(1, batchSize);
    this.flushIntervalMillis = Math.max(1, flushIntervalMillis);
    this.maxInFlight = Math.max(1, maxInFlight);
    this.gzip = gzip;
  }

  /** The settings of {@code config} with the effective {@code token} (SEC-06 three ways). */
  static TransportSettings of(AgentConfig config, TokenHolder token) {
    String ca = config.serverTlsCaFile();
    return new TransportSettings(
        target(config.serverAddress()),
        config.serverTls(),
        ca == null || ca.trim().isEmpty() ? null : new File(ca.trim()),
        config.serverTlsInsecureSkipVerify(),
        token,
        config.serverConnectTimeoutMs(),
        config.serverDeadlineMs(),
        config.senderBatchSize(),
        config.senderFlushIntervalMs(),
        config.senderMaxInFlight(),
        !"none".equalsIgnoreCase(config.senderCompression()));
  }

  /**
   * The gRPC target of {@code server.address} ({@code host:port}; the default port 9100 when none
   * is given, IPv6 literals in brackets), or null when the address is missing or blank.
   */
  static String target(String address) {
    if (address == null || address.trim().isEmpty()) {
      return null;
    }
    String value = address.trim();
    if (value.startsWith("[")) {
      int close = value.indexOf(']');
      if (close > 0 && close == value.length() - 1) {
        return value + ":" + DEFAULT_PORT;
      }
      return value;
    }
    int colon = value.indexOf(':');
    if (colon < 0) {
      return value + ":" + DEFAULT_PORT;
    }
    if (value.indexOf(':', colon + 1) >= 0) {
      // A bare IPv6 literal without brackets and port.
      return "[" + value + "]:" + DEFAULT_PORT;
    }
    return value;
  }
}
