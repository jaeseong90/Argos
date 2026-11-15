/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.agent.core.config.LoadedConfig;
import io.argos.agent.core.config.TokenHolder;
import io.argos.agent.core.export.Exporter;
import io.argos.agent.core.export.ExporterContext;
import io.argos.agent.core.export.ExporterFactory;
import io.argos.agent.core.export.NoopExporter;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.trace.TraceRuntime;
import java.security.SecureRandom;

/**
 * The {@code grpc} slot of the exporter SPI ({@code Exporters.GRPC_FACTORY_CLASS}): builds the
 * transport on {@code argos-sender} (never on the premain thread, C-79). When the transport cannot
 * run, the agent keeps recording and drops what it records (the queues never fill): no {@code
 * server.address} (the configuration already logged its ERROR), Java 8 before 8u252 with TLS (one
 * ERROR, ADR-0005 decision 6), or another start failure such as an unreadable {@code
 * server.tls.ca-file} (one ERROR).
 */
public final class GrpcExporterFactory implements ExporterFactory {
  @Override
  public Exporter create(ExporterContext context) throws Exception {
    LoadedConfig loaded = context.loaded();
    TraceRuntime runtime = context.runtime();
    if (runtime == null) {
      throw new IllegalStateException("the grpc exporter needs the agent's recording runtime");
    }
    TokenHolder token = loaded == null ? context.config().serverToken() : loaded.token();
    TransportSettings settings = TransportSettings.of(context.config(), token);
    if (settings.target == null) {
      return new NoopExporter();
    }
    // Off the premain thread (C-91, C-79): SecureRandom seeding can take most of a second on Java
    // 8.
    long epoch = new SecureRandom().nextLong();
    final GrpcExporter.IdsHolder ids = new GrpcExporter.IdsHolder();
    GrpcTransport transport;
    try {
      transport = GrpcTransport.create(settings, "argos-agent/" + context.agent().version(), ids);
    } catch (GrpcTransport.UnsupportedTlsException e) {
      AgentLogger.error(
          "agent transport disabled: this JVM has no TLS ALPN support (Java 8 needs 8u252 or"
              + " later; java.version {}); the application and the instrumentation keep running,"
              + " collected data is dropped",
          System.getProperty("java.version"));
      return new NoopExporter();
    } catch (Exception e) {
      AgentLogger.error(
          "agent transport disabled: it could not start ({}; check server.address and"
              + " server.tls.ca-file); collected data is dropped",
          e.getClass().getName());
      return new NoopExporter();
    }
    AgentLogger.info(
        "gRPC transport created (netty unsafe {}, direct memory limit {} bytes)",
        GrpcTransport.nettyUsesUnsafe(),
        GrpcTransport.nettyMaxDirectMemory());
    GrpcExporter.Parts parts = new GrpcExporter.Parts();
    parts.config = context.config();
    parts.settings = settings;
    parts.transport = transport;
    parts.counters = context.counters() != null ? context.counters() : new AgentCounters();
    // Created here, on argos-sender: the transport's classes stay off the premain thread (C-79).
    final TransportCounters transportCounters = new TransportCounters();
    SelfDiagnosticsCollector diagnostics = context.diagnostics();
    if (diagnostics != null) {
      diagnostics.extraCounters(
          new SelfDiagnosticsCollector.Gauges() {
            @Override
            public java.util.Map<String, Long> values() {
              return transportCounters.snapshot();
            }
          });
    }
    parts.transportCounters = transportCounters;
    parts.runtime = runtime;
    parts.agentVersion = context.agent().version();
    parts.configHash = loaded == null ? "" : loaded.configHash();
    parts.modules = context.modules();
    parts.agentEpoch = epoch;
    return GrpcExporter.create(parts, ids);
  }
}
