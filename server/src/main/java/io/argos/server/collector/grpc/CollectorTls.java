/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import io.grpc.netty.GrpcSslContexts;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLContext;

/**
 * The TLS context of the gRPC listener (16 §3.1, §3.2, SEC-07): PEM certificate chain and PKCS#8
 * private key from {@code argos.collector.tls.cert-file} / {@code key-file}, the JDK SSL provider
 * only (no netty-tcnative), protocols TLS 1.3 and TLS 1.2, and exactly the AEAD cipher suites of 16
 * §3.2 (CBC, RSA key exchange, NULL, EXPORT, 3DES and RC4 are not in the list, so a client that
 * offers only those fails the handshake). No client certificates (mTLS is 1.x, 16 §3.6).
 */
public final class CollectorTls {

  /** 16 §3.1: TLS 1.2 and later only. */
  public static final List<String> PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");

  /** 16 §3.2, in order of preference. */
  public static final List<String> CIPHER_SUITES =
      List.of(
          "TLS_AES_256_GCM_SHA384",
          "TLS_AES_128_GCM_SHA256",
          "TLS_CHACHA20_POLY1305_SHA256",
          "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
          "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256");

  private CollectorTls() {}

  /**
   * The suites of {@link #CIPHER_SUITES} this JDK supports, in the list's order.
   *
   * @throws IllegalStateException when none is supported (the listener does not start, 16 §3.1 "비어
   *     있으면 기동 거부")
   */
  public static List<String> usableCipherSuites() {
    Set<String> supported;
    try {
      supported =
          new HashSet<>(
              Arrays.asList(SSLContext.getDefault().getSupportedSSLParameters().getCipherSuites()));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("The JDK has no default TLS implementation", e);
    }
    List<String> usable = new ArrayList<>();
    for (String suite : CIPHER_SUITES) {
      if (supported.contains(suite)) {
        usable.add(suite);
      }
    }
    if (usable.isEmpty()) {
      throw new IllegalStateException(
          "The JDK supports none of the collector's TLS cipher suites (16 §3.2); the gRPC listener"
              + " does not start");
    }
    return usable;
  }

  /**
   * The server TLS context of {@code certFile} and {@code keyFile}.
   *
   * @throws IllegalStateException with an operator-facing message (paths, never key material) when
   *     a file is missing or unreadable or the context cannot be built
   */
  public static SslContext serverContext(Path certFile, Path keyFile) {
    requireReadable(certFile, "argos.collector.tls.cert-file");
    requireReadable(keyFile, "argos.collector.tls.key-file");
    List<String> suites = usableCipherSuites();
    try {
      return GrpcSslContexts.configure(
              SslContextBuilder.forServer(certFile.toFile(), keyFile.toFile()), SslProvider.JDK)
          .protocols(PROTOCOLS)
          .ciphers(suites)
          .clientAuth(ClientAuth.NONE)
          .build();
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "Cannot load the gRPC TLS certificate "
              + certFile
              + " and key "
              + keyFile
              + " (PEM certificate chain and unencrypted PKCS#8 key expected): "
              + e.getClass().getSimpleName(),
          e);
    }
  }

  private static void requireReadable(Path file, String key) {
    if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
      throw new IllegalStateException(
          key
              + " "
              + file.toAbsolutePath()
              + " is missing or unreadable: the TLS gRPC listener needs it (set"
              + " argos.collector.tls.enabled=false only for a plaintext demo)");
    }
  }
}
