/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.SessionClose;
import io.argos.server.collector.grpc.CollectorTls;
import io.argos.server.collector.grpc.GrpcCollectorServer;
import io.argos.server.session.SessionRegistry;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

/**
 * Start and stop of the gRPC collector. The listener starts in the {@code LISTENERS} step of the
 * start sequence ({@code CollectorListenerPhase}, DSN-12-15). The stop follows 12 §3.9:
 *
 * <ol>
 *   <li>new Hellos are refused ({@code SERVER_SHUTTING_DOWN}) and new data calls get {@code
 *       UNAVAILABLE} ({@link CollectorState});
 *   <li>every session gets {@code SessionClose{SERVER_SHUTDOWN, retry_after_s = 30}} and its stream
 *       ends {@code UNAVAILABLE} with {@code argos-retry-after-ms 30000};
 *   <li>gRPC {@code shutdown} with a {@value #DRAIN_SECONDS} s drain for running calls, then {@code
 *       shutdownNow}.
 * </ol>
 *
 * The phase is high, so the collector stops before the beans it writes into: the WAL and the
 * ClickHouse writers (lane durable-ingest) flush afterwards, in their own stop or destroy step;
 * what is not loaded stays in the WAL and is recovered at the next start.
 */
public final class CollectorLifecycle implements SmartLifecycle, DisposableBean {

  private static final Logger LOG = LoggerFactory.getLogger(CollectorLifecycle.class);

  /**
   * Stops before the embedded web server ({@code SmartLifecycle.DEFAULT_PHASE − 1024}) and before
   * any lower-phase bean of the ingest path.
   */
  public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 512;

  /** 12 §3.9: running calls get 10 s to complete. */
  public static final int DRAIN_SECONDS = 10;

  private final String bind;
  private final int port;
  private final int maxMessageBytes;
  private final boolean tlsEnabled;
  private final Path certFile;
  private final Path keyFile;
  private final boolean allowPlaintext;
  private final ServerServiceDefinition service;
  private final ServerStreamTracer.Factory tracer;
  private final SessionRegistry registry;
  private final CollectorState state;
  private final Duration drain;
  private volatile GrpcCollectorServer server;

  /**
   * @param bind {@code argos.collector.bind}
   * @param port {@code argos.collector.port} (0 = a free port, tests)
   * @param maxMessageBytes {@code argos.collector.max-message-bytes}
   * @param tlsEnabled {@code argos.collector.tls.enabled}
   * @param certFile {@code argos.collector.tls.cert-file}
   * @param keyFile {@code argos.collector.tls.key-file}
   * @param allowPlaintext {@code argos.collector.allow-plaintext}
   * @param service the intercepted {@code AgentService}
   * @param tracer stream tracer of the listener, or {@code null}
   * @param registry the live sessions (closed at the stop)
   * @param state the shutdown flag
   * @param drain how long running calls may take at the stop
   */
  public CollectorLifecycle(
      String bind,
      int port,
      int maxMessageBytes,
      boolean tlsEnabled,
      Path certFile,
      Path keyFile,
      boolean allowPlaintext,
      ServerServiceDefinition service,
      ServerStreamTracer.Factory tracer,
      SessionRegistry registry,
      CollectorState state,
      Duration drain) {
    this.bind = Objects.requireNonNull(bind, "bind");
    this.port = port;
    this.maxMessageBytes = maxMessageBytes;
    this.tlsEnabled = tlsEnabled;
    this.certFile = Objects.requireNonNull(certFile, "certFile");
    this.keyFile = Objects.requireNonNull(keyFile, "keyFile");
    this.allowPlaintext = allowPlaintext;
    this.service = Objects.requireNonNull(service, "service");
    this.tracer = tracer;
    this.registry = Objects.requireNonNull(registry, "registry");
    this.state = Objects.requireNonNull(state, "state");
    this.drain = Objects.requireNonNull(drain, "drain");
  }

  /**
   * Builds the TLS context (when enabled) and binds the listener.
   *
   * @throws IllegalStateException when the TLS files or cipher suites are not usable
   * @throws IOException when the port cannot be bound
   */
  public synchronized void startListener() throws IOException {
    if (server != null) {
      throw new IllegalStateException("The gRPC collector listener is already started");
    }
    SslContext ssl = tlsEnabled ? CollectorTls.serverContext(certFile, keyFile) : null;
    GrpcCollectorServer created =
        new GrpcCollectorServer(bind, port, maxMessageBytes, service, ssl, tracer);
    created.start();
    server = created;
  }

  /** The listener (after {@link #startListener}). */
  public GrpcCollectorServer server() {
    GrpcCollectorServer current = server;
    if (current == null) {
      throw new IllegalStateException("The gRPC collector listener is not started");
    }
    return current;
  }

  /** The configured listen address. */
  public String bind() {
    return bind;
  }

  /** The configured port. */
  public int configuredPort() {
    return port;
  }

  /** {@code argos.collector.allow-plaintext}. */
  public boolean allowPlaintext() {
    return allowPlaintext;
  }

  /** The 12 §3.9 stop sequence; runs once. */
  public void shutdownCollector() {
    GrpcCollectorServer current = server;
    if (current == null || !state.beginShutdown()) {
      return;
    }
    int closed =
        registry.closeAll(
            SessionClose.newBuilder()
                .setReason(CloseReason.SERVER_SHUTDOWN)
                .setMessage("The collector is shutting down")
                .setRetryAfterS(CollectorState.SHUTDOWN_CLOSE_RETRY_AFTER_S)
                .build(),
            Status.UNAVAILABLE.withDescription("The collector is shutting down"),
            CollectorCalls.trailers(null, CollectorState.SHUTDOWN_CLOSE_RETRY_AFTER_S * 1000L));
    LOG.info(
        "gRPC collector stopping: {} session(s) closed, draining calls up to {}", closed, drain);
    current.shutdown(drain);
    LOG.info("gRPC collector stopped");
  }

  @Override
  public void start() {
    // The listener starts in the LISTENERS step of the start sequence (CollectorListenerPhase).
  }

  @Override
  public void stop() {
    shutdownCollector();
  }

  @Override
  public boolean isRunning() {
    GrpcCollectorServer current = server;
    return current != null && current.running() && !state.shuttingDown();
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  @Override
  public void destroy() {
    shutdownCollector();
  }
}
