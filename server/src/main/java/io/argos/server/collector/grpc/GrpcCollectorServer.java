/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The gRPC listener of the collector (12 §3.1, 16 §3.1, §4.6, 11 §2): a Netty server of its own on
 * {@code argos.collector.bind:port} (9100), separate from HTTP (SEC-75).
 *
 * <table>
 *   <caption>Hardening (12 §3.1, 16 §4.6, ADR-0010 C-130 (3))</caption>
 *   <tr><td>maxInboundMessageSize</td><td>{@code argos.collector.max-message-bytes} (4 MiB,
 *       decompressed; plus the bounded gzip decompressor and marshaller)</td></tr>
 *   <tr><td>maxInboundMetadataSize</td><td>{@value #MAX_METADATA_BYTES} B</td></tr>
 *   <tr><td>maxConcurrentCallsPerConnection</td><td>{@value #MAX_CALLS_PER_CONNECTION}</td></tr>
 *   <tr><td>permitKeepAliveTime</td><td>60 s, without calls not permitted</td></tr>
 *   <tr><td>handshake timeout</td><td>10 s</td></tr>
 *   <tr><td>maxConnectionIdle / Age / AgeGrace</td><td>10 min / 24 h / 5 min</td></tr>
 *   <tr><td>threads</td><td>boss 1, worker = cores, calls on {@value #EXECUTOR_THREADS} fixed
 *       {@code argos-grpc-exec-N} threads (12 §11)</td></tr>
 * </table>
 *
 * Only {@code argos.v1.AgentService} is registered (no reflection, no health service).
 */
public final class GrpcCollectorServer {

  /** 16 §4.6: metadata (headers) at most 8 KiB. */
  public static final int MAX_METADATA_BYTES = 8 * 1024;

  /** ADR-0010 C-130 (3): the agent uses 4 data calls and the session stream. */
  public static final int MAX_CALLS_PER_CONNECTION = 8;

  /** 12 §3.1, §11: the fixed call executor. */
  public static final int EXECUTOR_THREADS = 8;

  /** 11 §2, 16 §4.6: keepalive pings closer than this are refused ({@code too_many_pings}). */
  public static final Duration PERMIT_KEEPALIVE = Duration.ofSeconds(60);

  /** 11 §2, SEC-15: TLS handshake within 10 s. */
  public static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);

  /** 12 §3.1, 16 §4.6. */
  public static final Duration MAX_CONNECTION_IDLE = Duration.ofMinutes(10);

  /** 16 §4.6: connections are renewed daily (configuration renegotiation). */
  public static final Duration MAX_CONNECTION_AGE = Duration.ofHours(24);

  /** 16 §4.6. */
  public static final Duration MAX_CONNECTION_AGE_GRACE = Duration.ofMinutes(5);

  private final String bind;
  private final int port;
  private final int maxMessageBytes;
  private final ServerServiceDefinition service;
  private final SslContext sslContext;
  private final ServerStreamTracer.Factory tracer;
  private Server server;
  private ExecutorService executor;
  private EventLoopGroup boss;
  private EventLoopGroup workers;

  /**
   * @param bind listen address ({@code argos.collector.bind})
   * @param port listen port; 0 picks a free port (tests)
   * @param maxMessageBytes {@code argos.collector.max-message-bytes}
   * @param service the intercepted {@code AgentService}
   * @param sslContext the TLS context, or {@code null} for the plaintext listener
   * @param tracer stream tracer of the listener (transport-level rejection counts), or {@code null}
   */
  public GrpcCollectorServer(
      String bind,
      int port,
      int maxMessageBytes,
      ServerServiceDefinition service,
      SslContext sslContext,
      ServerStreamTracer.Factory tracer) {
    this.bind = Objects.requireNonNull(bind, "bind");
    this.port = port;
    this.maxMessageBytes = maxMessageBytes;
    this.service = Objects.requireNonNull(service, "service");
    this.sslContext = sslContext;
    this.tracer = tracer;
  }

  /** Binds the port and starts serving. */
  public synchronized void start() throws IOException {
    if (server != null) {
      throw new IllegalStateException("The gRPC collector is already started");
    }
    executor = Executors.newFixedThreadPool(EXECUTOR_THREADS, threads("argos-grpc-exec-"));
    boss =
        new MultiThreadIoEventLoopGroup(1, threads("argos-grpc-boss-"), NioIoHandler.newFactory());
    workers =
        new MultiThreadIoEventLoopGroup(
            Runtime.getRuntime().availableProcessors(),
            threads("argos-grpc-worker-"),
            NioIoHandler.newFactory());
    NettyServerBuilder builder =
        NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getByName(bind), port))
            .channelType(NioServerSocketChannel.class)
            .bossEventLoopGroup(boss)
            .workerEventLoopGroup(workers)
            .executor(executor)
            .maxInboundMessageSize(maxMessageBytes)
            .maxInboundMetadataSize(MAX_METADATA_BYTES)
            .maxConcurrentCallsPerConnection(MAX_CALLS_PER_CONNECTION)
            .permitKeepAliveTime(PERMIT_KEEPALIVE.toSeconds(), TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(false)
            .maxConnectionIdle(MAX_CONNECTION_IDLE.toMinutes(), TimeUnit.MINUTES)
            .maxConnectionAge(MAX_CONNECTION_AGE.toHours(), TimeUnit.HOURS)
            .maxConnectionAgeGrace(MAX_CONNECTION_AGE_GRACE.toMinutes(), TimeUnit.MINUTES)
            .handshakeTimeout(HANDSHAKE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
            .decompressorRegistry(BoundedGzipDecompressor.registry(maxMessageBytes))
            .addService(service);
    if (tracer != null) {
      builder.addStreamTracerFactory(tracer);
    }
    if (sslContext != null) {
      builder.sslContext(sslContext);
    }
    Server built = builder.build();
    try {
      built.start();
    } catch (IOException | RuntimeException e) {
      releaseThreads();
      throw e;
    }
    server = built;
  }

  /** The bound port ({@code -1} before {@link #start}). */
  public synchronized int port() {
    return server == null ? -1 : server.getPort();
  }

  /** Whether the listener runs. */
  public synchronized boolean running() {
    return server != null && !server.isShutdown();
  }

  /** Whether the listener uses TLS. */
  public boolean tls() {
    return sslContext != null;
  }

  /**
   * Stops accepting calls, waits up to {@code drain} for running calls (12 §3.9: 10 s), then
   * cancels what is left and releases the threads.
   */
  public void shutdown(Duration drain) {
    Server current;
    synchronized (this) {
      current = server;
    }
    if (current == null) {
      return;
    }
    current.shutdown();
    try {
      if (!current.awaitTermination(drain.toMillis(), TimeUnit.MILLISECONDS)) {
        current.shutdownNow();
        current.awaitTermination(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      current.shutdownNow();
      Thread.currentThread().interrupt();
    } finally {
      releaseThreads();
    }
  }

  private synchronized void releaseThreads() {
    if (executor != null) {
      executor.shutdownNow();
    }
    if (boss != null) {
      var unused = boss.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }
    if (workers != null) {
      var unused = workers.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }
  }

  private static ThreadFactory threads(String prefix) {
    AtomicInteger next = new AtomicInteger(1);
    return runnable -> {
      Thread t = new Thread(runnable, prefix + next.getAndIncrement());
      t.setDaemon(true);
      return t;
    };
  }
}
