/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.sender;

import io.argos.agent.core.config.AgentConfig;
import java.io.File;

/**
 * Settings of the P-3 comparison sender, read from the agent configuration (known keys of 10 §15
 * only, so the comparison jars need no new configuration key): {@code server.address} ({@code
 * host:port}), {@code server.tls} (default true), {@code server.tls.ca-file}, {@code
 * server.deadline-ms} (5,000), {@code server.connect-timeout-ms} (5,000) and {@code app.instance}
 * (a number: copied into {@code BatchHeader.instance_id} so the fake collector can tell runs
 * apart).
 */
public final class PocSettings {
  /** The comparison sender sends one small batch per interval (P-3: "every second"). */
  public static final long INTERVAL_MILLIS = 1_000;

  /** Records per batch ("small protobuf batch"). */
  public static final int RECORDS_PER_BATCH = 5;

  private final String host;
  private final int port;
  private final boolean tls;
  private final File caFile;
  private final int deadlineMillis;
  private final int connectTimeoutMillis;
  private final int instanceId;

  PocSettings(
      String host,
      int port,
      boolean tls,
      File caFile,
      int deadlineMillis,
      int connectTimeoutMillis,
      int instanceId) {
    this.host = host;
    this.port = port;
    this.tls = tls;
    this.caFile = caFile;
    this.deadlineMillis = deadlineMillis;
    this.connectTimeoutMillis = connectTimeoutMillis;
    this.instanceId = instanceId;
  }

  /**
   * Reads the settings; throws IllegalArgumentException for a missing or bad address. The typed
   * accessors of {@link AgentConfig} carry the defaults and ranges of 10 §15 (validated once by
   * {@code KeySpec}).
   */
  public static PocSettings from(AgentConfig config) {
    String address = config.serverAddress();
    if (address == null) {
      throw new IllegalArgumentException("server.address is not set");
    }
    int colon = address.lastIndexOf(':');
    if (colon <= 0 || colon == address.length() - 1) {
      throw new IllegalArgumentException("server.address must be host:port");
    }
    int port;
    try {
      port = Integer.parseInt(address.substring(colon + 1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("server.address must be host:port", e);
    }
    String ca = config.serverTlsCaFile();
    int instance = 0;
    String instanceText = config.appInstance();
    if (instanceText != null) {
      try {
        instance = Integer.parseInt(instanceText);
      } catch (NumberFormatException e) {
        instance = 0;
      }
    }
    return new PocSettings(
        address.substring(0, colon),
        port,
        config.serverTls(),
        ca == null ? null : new File(ca),
        config.serverDeadlineMs(),
        config.serverConnectTimeoutMs(),
        instance);
  }

  public String host() {
    return host;
  }

  public int port() {
    return port;
  }

  public boolean tls() {
    return tls;
  }

  /** CA file (PEM) or null for the JDK trust store. */
  public File caFile() {
    return caFile;
  }

  public int deadlineMillis() {
    return deadlineMillis;
  }

  public int connectTimeoutMillis() {
    return connectTimeoutMillis;
  }

  public int instanceId() {
    return instanceId;
  }

  @Override
  public String toString() {
    return host + ":" + port + (tls ? " tls" : " plaintext");
  }
}
