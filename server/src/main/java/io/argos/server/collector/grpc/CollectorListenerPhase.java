/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.collector.CollectorLifecycle;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gRPC collector listener as the last step of the start sequence ({@code LISTENERS},
 * DSN-12-15): it binds port 9100 only after the migrations and the WAL recovery, so that no batch
 * is received before the deduplication window has been restored (D-13).
 *
 * <p>A plaintext listener ({@code argos.collector.tls.enabled=false}) logs a WARN; when {@code
 * allow-plaintext} is also false every Hello is refused with {@code PLAINTEXT_NOT_ALLOWED} (11 §5
 * check 3), which the WARN says.
 */
public final class CollectorListenerPhase implements StartupPhase {

  private static final Logger LOG = LoggerFactory.getLogger(CollectorListenerPhase.class);

  private final CollectorLifecycle lifecycle;

  public CollectorListenerPhase(CollectorLifecycle lifecycle) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.LISTENERS;
  }

  @Override
  public String name() {
    return "grpc-collector";
  }

  @Override
  public void run() throws StartupPhaseException {
    try {
      lifecycle.startListener();
    } catch (IllegalStateException e) {
      throw new StartupPhaseException(e.getMessage(), e);
    } catch (IOException e) {
      throw new StartupPhaseException(
          "The gRPC collector cannot listen on "
              + lifecycle.bind()
              + ":"
              + lifecycle.configuredPort()
              + ": "
              + e.getMessage(),
          e);
    }
    GrpcCollectorServer server = lifecycle.server();
    if (server.tls()) {
      LOG.info(
          "gRPC collector listening on {}:{} (TLS 1.2+, JDK provider)",
          lifecycle.bind(),
          server.port());
    } else if (lifecycle.allowPlaintext()) {
      LOG.warn(
          "gRPC collector listening on {}:{} WITHOUT TLS (argos.collector.tls.enabled=false,"
              + " allow-plaintext=true): agent tokens and data travel unencrypted; demo use only"
              + " (SEC-08)",
          lifecycle.bind(),
          server.port());
    } else {
      LOG.warn(
          "gRPC collector listening on {}:{} WITHOUT TLS (argos.collector.tls.enabled=false) while"
              + " argos.collector.allow-plaintext=false: every agent Hello is refused with"
              + " PLAINTEXT_NOT_ALLOWED",
          lifecycle.bind(),
          server.port());
    }
  }
}
