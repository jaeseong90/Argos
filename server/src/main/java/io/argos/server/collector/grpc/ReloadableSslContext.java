/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.netty.handler.ssl.SslContext;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;

/**
 * A server TLS context whose certificate and key can be replaced while the listener runs (16 §3.1
 * {@code reload.watch}, SEC-09, 12 §9.5 {@code CertReloader}): every new connection takes its
 * {@link SSLEngine} from the current context, so a {@link #swap} applies from the next handshake on
 * and connections that are already open keep the engine (and certificate) they started with.
 *
 * <p>The protocols, cipher suites and ALPN of every swapped-in context are those of {@link
 * CollectorTls} (the same builder), so the gRPC listener's checks on the context it was given at
 * start stay true.
 */
public final class ReloadableSslContext extends SslContext {

  private final AtomicReference<SslContext> current;

  /**
   * @param initial a server context of {@link CollectorTls}
   */
  public ReloadableSslContext(SslContext initial) {
    Objects.requireNonNull(initial, "initial");
    if (initial.isClient()) {
      throw new IllegalArgumentException("A server TLS context is required");
    }
    this.current = new AtomicReference<>(initial);
  }

  /**
   * Makes {@code next} the context of new handshakes.
   *
   * @return the context it replaces
   */
  public SslContext swap(SslContext next) {
    Objects.requireNonNull(next, "next");
    if (next.isClient()) {
      throw new IllegalArgumentException("A server TLS context is required");
    }
    return current.getAndSet(next);
  }

  /** The context of new handshakes. */
  public SslContext current() {
    return current.get();
  }

  @Override
  public boolean isClient() {
    return false;
  }

  @Override
  public List<String> cipherSuites() {
    return current.get().cipherSuites();
  }

  // The abstract method of SslContext; gRPC reads the ALPN configuration through it.
  @SuppressWarnings("deprecation")
  @Override
  public ApplicationProtocolNegotiator applicationProtocolNegotiator() {
    return current.get().applicationProtocolNegotiator();
  }

  @Override
  public SSLEngine newEngine(ByteBufAllocator alloc) {
    return current.get().newEngine(alloc);
  }

  @Override
  public SSLEngine newEngine(ByteBufAllocator alloc, String peerHost, int peerPort) {
    return current.get().newEngine(alloc, peerHost, peerPort);
  }

  @Override
  public SSLSessionContext sessionContext() {
    return current.get().sessionContext();
  }
}
