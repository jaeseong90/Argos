/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.grpcnetty;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.transportpoc.grpc.TlsSessionInterceptor;
import io.argos.agent.transportpoc.proto.Ack;
import io.argos.agent.transportpoc.proto.AgentServiceGrpc;
import io.argos.agent.transportpoc.proto.TransactionBatch;
import io.argos.agent.transportpoc.sender.PocSettings;
import io.argos.agent.transportpoc.sender.PocTransport;
import io.argos.agent.transportpoc.sender.TlsTrust;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.JdkLoggerFactory;
import java.util.concurrent.TimeUnit;

/**
 * grpc-netty transport with the fixed settings of 10 §10.3: NIO only, JDK TLS ({@link
 * SslProvider#JDK}), one event loop thread ({@code argos-io}), direct executor and the event loop
 * as offload executor (no gRPC shared executor or timer threads), 4 MiB inbound limit, keepalive 60
 * s / 20 s without calls, idle mode off, Netty without Unsafe, unpooled heap buffers and an 8 MiB
 * direct memory limit ({@link NettyProps}).
 */
public final class NettyGrpcTransport implements PocTransport {
  private static final int MAX_INBOUND_BYTES = 4 * 1024 * 1024;

  private final PocSettings settings;
  private final TlsSessionInterceptor tls = new TlsSessionInterceptor();
  private EventLoopGroup group;
  private ManagedChannel channel;
  private AgentServiceGrpc.AgentServiceBlockingStub stub;
  private String nettyInfo = "";

  public NettyGrpcTransport(PocSettings settings) {
    this.settings = settings;
  }

  @Override
  // NioEventLoopGroup (10 §10.3) is deprecated in Netty 4.2 in favour of
  // MultiThreadIoEventLoopGroup,
  // which Netty 4.1 lacks; this one source serves both comparison lines (4.2 and 4.1).
  @SuppressWarnings("deprecation")
  public void start() throws Exception {
    // Before the first Netty class initialises (PlatformDependent reads them once).
    NettyProps.apply();
    // No logging-framework probing (SLF4J, Log4j 2 are not visible to the agent class loader
    // anyway): the JDK logger, which the comparison jars map to the agent log (JulLogger).
    InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE);
    // Fully qualified: with --release 8, javac also reports the deprecation on an import.
    group = new io.netty.channel.nio.NioEventLoopGroup(1, ArgosThread.factory("argos-io"));
    NettyChannelBuilder builder =
        NettyChannelBuilder.forAddress(settings.host(), settings.port())
            .channelType(NioSocketChannel.class)
            .eventLoopGroup(group)
            .directExecutor()
            .offloadExecutor(group)
            .maxInboundMessageSize(MAX_INBOUND_BYTES)
            .keepAliveTime(60, TimeUnit.SECONDS)
            .keepAliveTimeout(20, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .idleTimeout(Long.MAX_VALUE, TimeUnit.DAYS);
    if (settings.tls()) {
      SslContext ssl =
          GrpcSslContexts.configure(SslContextBuilder.forClient(), SslProvider.JDK)
              .trustManager(TlsTrust.trustManagers(settings.caFile()))
              .build();
      builder.sslContext(ssl);
    } else {
      builder.usePlaintext();
    }
    channel = builder.build();
    stub = AgentServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel, tls));
    nettyInfo =
        "netty hasUnsafe="
            + PlatformDependent.hasUnsafe()
            + " maxDirectMemory="
            + PlatformDependent.maxDirectMemory()
            + " directBufferPreferred="
            + PlatformDependent.directBufferPreferred()
            + " allocator="
            + ByteBufAllocator.DEFAULT.getClass().getSimpleName();
  }

  @Override
  public Ack send(TransactionBatch batch) {
    return stub.withDeadlineAfter(settings.deadlineMillis(), TimeUnit.MILLISECONDS)
        .sendTransactions(batch);
  }

  @Override
  public String connectionInfo() {
    return tls.session() + "; " + nettyInfo;
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored") // shutdown hook: the JVM may exit before it ends
  public void close() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (group != null) {
      group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }
  }

  @Override
  public String name() {
    return "grpc-netty";
  }
}
