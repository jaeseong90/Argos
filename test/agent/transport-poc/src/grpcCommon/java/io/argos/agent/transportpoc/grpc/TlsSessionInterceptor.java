/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import javax.net.ssl.SSLSession;

/**
 * Records the TLS protocol and cipher suite of the connection a call used (for the P-3 log line
 * {@code transport-poc first-ack}); gRPC over TLS always negotiated ALPN {@code h2}.
 */
public final class TlsSessionInterceptor implements ClientInterceptor {
  private volatile String session = "no response yet";

  /** "TLSv1.3 TLS_AES_128_GCM_SHA256 alpn=h2", "plaintext" or "no response yet". */
  public String session() {
    return session;
  }

  @Override
  public <Q, R> ClientCall<Q, R> interceptCall(
      MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
    return new SimpleForwardingClientCall<Q, R>(next.newCall(method, options)) {
      @Override
      public void start(Listener<R> listener, Metadata headers) {
        final ClientCall<Q, R> call = delegate();
        super.start(
            new SimpleForwardingClientCallListener<R>(listener) {
              @Override
              public void onHeaders(Metadata responseHeaders) {
                SSLSession ssl = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
                session =
                    ssl == null
                        ? "plaintext"
                        : ssl.getProtocol() + " " + ssl.getCipherSuite() + " alpn=h2";
                super.onHeaders(responseHeaders);
              }
            },
            headers);
      }
    };
  }
}
