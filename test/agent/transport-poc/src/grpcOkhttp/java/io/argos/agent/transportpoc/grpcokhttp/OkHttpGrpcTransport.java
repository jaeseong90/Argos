/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.grpcokhttp;

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
import io.grpc.okhttp.OkHttpChannelBuilder;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * grpc-okhttp transport with settings equivalent to 10 §10.3: JDK TLS ({@code SSLSocket}), direct
 * executor, its own bounded executors instead of gRPC's shared ones: {@code argos-io} runs the
 * blocking frame reader of the connection (at most 2 threads, so that a reconnect does not wait for
 * the old reader), {@code argos-io-timer} runs keepalive pings, deadlines and name resolution.
 * OkHttp works on heap buffers only (no direct memory, no Unsafe).
 */
public final class OkHttpGrpcTransport implements PocTransport {
  private static final int MAX_INBOUND_BYTES = 4 * 1024 * 1024;

  private final PocSettings settings;
  private final TlsSessionInterceptor tls = new TlsSessionInterceptor();
  private ThreadPoolExecutor readers;
  private ScheduledThreadPoolExecutor timer;
  private ManagedChannel channel;
  private AgentServiceGrpc.AgentServiceBlockingStub stub;

  public OkHttpGrpcTransport(PocSettings settings) {
    this.settings = settings;
  }

  @Override
  public void start() throws Exception {
    readers =
        new ThreadPoolExecutor(
            0,
            2,
            60,
            TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            ArgosThread.factory("argos-io"));
    timer = new ScheduledThreadPoolExecutor(1, ArgosThread.factory("argos-io-timer"));
    timer.setRemoveOnCancelPolicy(true);
    OkHttpChannelBuilder builder =
        OkHttpChannelBuilder.forAddress(settings.host(), settings.port())
            .transportExecutor(readers)
            .scheduledExecutorService(timer)
            .offloadExecutor(timer)
            .directExecutor()
            .maxInboundMessageSize(MAX_INBOUND_BYTES)
            .keepAliveTime(60, TimeUnit.SECONDS)
            .keepAliveTimeout(20, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .idleTimeout(Long.MAX_VALUE, TimeUnit.DAYS);
    if (settings.tls()) {
      builder.sslSocketFactory(TlsTrust.context(settings.caFile()).getSocketFactory());
    } else {
      builder.usePlaintext();
    }
    channel = builder.build();
    stub = AgentServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel, tls));
  }

  @Override
  public Ack send(TransactionBatch batch) {
    return stub.withDeadlineAfter(settings.deadlineMillis(), TimeUnit.MILLISECONDS)
        .sendTransactions(batch);
  }

  @Override
  public String connectionInfo() {
    return tls.session();
  }

  @Override
  public void close() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (timer != null) {
      timer.shutdownNow();
    }
    if (readers != null) {
      readers.shutdownNow();
    }
  }

  @Override
  public String name() {
    return "grpc-okhttp";
  }
}
