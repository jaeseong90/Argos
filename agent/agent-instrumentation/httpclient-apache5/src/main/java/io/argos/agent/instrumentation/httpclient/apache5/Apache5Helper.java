/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.apache5;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.net.URIAuthority;

/**
 * The HTTP step of one classic HttpClient 5 call (10 §6.5), injected into the class loader of the
 * library (10 §5.4): reads the target and the request through the library's public API, sets the
 * propagation headers with {@code HttpMessage.setHeader} and hands everything else to the bootstrap
 * {@link Tracer}. No state: the call's step is the {@code long[]} {@code {seq, hostId, startNanos}}
 * kept in an advice local.
 */
public final class Apache5Helper {
  static final String TRACEPARENT = "traceparent";
  static final String TRACESTATE = "tracestate";

  private Apache5Helper() {}

  /**
   * Sets the propagation headers and opens the HTTP step; returns {@code {seq, hostId,
   * startNanos}}, or null when no step is recorded. The library types are {@code Object} in the
   * signature: the advice that calls this is described from the agent jar, where they do not exist.
   */
  public static long[] start(TraceContext ctx, Object targetHost, Object classicRequest) {
    HttpHost target = (HttpHost) targetHost;
    ClassicHttpRequest request = (ClassicHttpRequest) classicRequest;
    long startNanos = System.nanoTime();
    Tracer tracer = Argos.tracer();
    String scheme;
    String host = null;
    int port = -1;
    if (target != null) {
      scheme = target.getSchemeName();
      host = target.getHostName();
      port = target.getPort();
    } else {
      // A request with an absolute URI: the client resolves the target from its authority.
      scheme = request.getScheme();
      URIAuthority authority = request.getAuthority();
      if (authority != null) {
        host = authority.getHostName();
        port = authority.getPort();
      }
    }
    String existingTraceparent = value(request.getFirstHeader(TRACEPARENT));
    String[] headers =
        tracer.outgoingHeaders(
            ctx, host, existingTraceparent, value(request.getFirstHeader(TRACESTATE)));
    String preserved = null;
    if (headers != null) {
      if (headers[0] != null) {
        request.setHeader(TRACEPARENT, headers[0]);
      } else {
        preserved = existingTraceparent;
      }
      if (headers[1] != null) {
        request.setHeader(TRACESTATE, headers[1]);
      }
    }
    long hostId = tracer.hostId(scheme, host, port);
    int seq =
        tracer.httpStart(
            ctx,
            hostId,
            request.getMethod(),
            host == null ? null : port > 0 ? host + ":" + port : host,
            request.getPath(),
            preserved,
            startNanos);
    return seq < 0 ? null : new long[] {seq, hostId, startNanos};
  }

  /** Closes the HTTP step with the response status or the error of the call. */
  public static void end(TraceContext ctx, long[] call, Object httpResponse, Throwable error) {
    HttpResponse response = (HttpResponse) httpResponse;
    int status = response == null ? 0 : response.getCode();
    Argos.tracer().httpEnd(ctx, (int) call[0], call[1], status, error, call[2], System.nanoTime());
  }

  private static String value(Header header) {
    return header == null ? null : header.getValue();
  }
}
