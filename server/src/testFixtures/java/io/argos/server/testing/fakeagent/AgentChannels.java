/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import io.grpc.Compressor;
import io.grpc.CompressorRegistry;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * gRPC channels of the fake agent: plaintext, TLS trusting a test CA (JDK SSL provider only, like
 * the agent, 16 §3.4), and in-process. Inbound messages up to 16 MiB are accepted so that a test
 * sees every answer.
 *
 * <p>{@link Options#preCompressedGzip()} builds a channel whose {@code gzip} "compressor" passes
 * the bytes through unchanged: a raw payload that is already a gzip stream ({@link
 * FakeAgent#sendRaw}) then travels with {@code grpc-encoding: gzip} as it is. This is how the gzip
 * bomb of 18 §8 (1 MB that inflates to 1 GB) reaches the server without a 1 GB message in the test
 * JVM. Never send ordinary batches with gzip on such a channel.
 */
public final class AgentChannels {

  /** Inbound message cap of the fake agent's channels (the server's answers are tiny). */
  public static final int MAX_INBOUND_BYTES = 16 * 1024 * 1024;

  private AgentChannels() {}

  /**
   * Transport options.
   *
   * @param protocols TLS protocols the client offers (empty: the JDK defaults)
   * @param cipherSuites TLS cipher suites the client offers (empty: the JDK defaults)
   * @param authority the TLS authority (host name checked against the certificate), or {@code null}
   *     for the target host
   * @param preCompressedGzip pass raw payloads flagged as gzip through unchanged (see class doc)
   */
  public record Options(
      List<String> protocols,
      List<String> cipherSuites,
      String authority,
      boolean preCompressedGzip) {

    /** Copies the lists. */
    public Options {
      protocols = List.copyOf(protocols);
      cipherSuites = List.copyOf(cipherSuites);
    }

    /** JDK defaults, the target host as authority, real gzip. */
    public static Options defaults() {
      return new Options(List.of(), List.of(), null, false);
    }

    /** These options offering only {@code protocols} (for example {@code TLSv1.1}, T-SEC-07). */
    public Options withProtocols(String... newProtocols) {
      return new Options(List.of(newProtocols), cipherSuites, authority, preCompressedGzip);
    }

    /** These options offering only {@code suites} (for example CBC suites, T-SEC-07). */
    public Options withCipherSuites(String... suites) {
      return new Options(protocols, List.of(suites), authority, preCompressedGzip);
    }

    /** These options with another TLS authority. */
    public Options withAuthority(String newAuthority) {
      return new Options(protocols, cipherSuites, newAuthority, preCompressedGzip);
    }

    /** These options with the pass-through gzip compressor. */
    public Options withPreCompressedGzip() {
      return new Options(protocols, cipherSuites, authority, true);
    }
  }

  /** A plaintext channel to {@code host:port} (server {@code allow-plaintext}, 16 §3.4). */
  public static ManagedChannel plaintext(String host, int port) {
    return plaintext(host, port, Options.defaults());
  }

  /** A plaintext channel with {@code options} (only {@code preCompressedGzip} applies). */
  public static ManagedChannel plaintext(String host, int port, Options options) {
    NettyChannelBuilder builder =
        NettyChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .maxInboundMessageSize(MAX_INBOUND_BYTES)
            .userAgent("argos-fake-agent");
    if (options.preCompressedGzip()) {
      builder.compressorRegistry(passThroughGzip());
    }
    return builder.build();
  }

  /** A TLS channel to {@code host:port} that trusts only {@code caCertificate} (PEM). */
  public static ManagedChannel tls(String host, int port, Path caCertificate) {
    return tls(host, port, caCertificate, Options.defaults());
  }

  /** A TLS channel with {@code options}. */
  public static ManagedChannel tls(String host, int port, Path caCertificate, Options options) {
    NettyChannelBuilder builder =
        NettyChannelBuilder.forAddress(host, port)
            .sslContext(clientContext(caCertificate, options))
            .maxInboundMessageSize(MAX_INBOUND_BYTES)
            .userAgent("argos-fake-agent");
    if (options.authority() != null) {
      builder.overrideAuthority(options.authority());
    }
    if (options.preCompressedGzip()) {
      builder.compressorRegistry(passThroughGzip());
    }
    return builder.build();
  }

  /** An in-process channel to the in-process server {@code name}. */
  public static ManagedChannel inProcess(String name) {
    return InProcessChannelBuilder.forName(name)
        .maxInboundMessageSize(MAX_INBOUND_BYTES)
        .userAgent("argos-fake-agent")
        .build();
  }

  /**
   * The client TLS context: JDK provider (no netty-tcnative), ALPN {@code h2}, trust anchor {@code
   * caCertificate}, optionally restricted protocols and suites.
   */
  public static SslContext clientContext(Path caCertificate, Options options) {
    try {
      SslContextBuilder builder =
          GrpcSslContexts.configure(SslContextBuilder.forClient(), SslProvider.JDK)
              .trustManager(caCertificate.toFile());
      if (!options.protocols().isEmpty()) {
        builder.protocols(options.protocols());
      }
      if (!options.cipherSuites().isEmpty()) {
        builder.ciphers(options.cipherSuites());
      }
      return builder.build();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static CompressorRegistry passThroughGzip() {
    CompressorRegistry registry = CompressorRegistry.newEmptyInstance();
    registry.register(PassThroughGzip.INSTANCE);
    return registry;
  }

  /** A "gzip" compressor that writes the bytes unchanged (they are already a gzip stream). */
  private enum PassThroughGzip implements Compressor {
    INSTANCE;

    @Override
    public String getMessageEncoding() {
      return "gzip";
    }

    @Override
    public OutputStream compress(OutputStream os) {
      return os;
    }
  }
}
