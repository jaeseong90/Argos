/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.apache4;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import java.net.URI;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.RequestLine;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpUriRequest;

/**
 * The HTTP step of one HttpClient 4 call (10 §6.5), injected into the class loader of the library
 * (10 §5.4): reads the target and the request through the library's public API, sets the
 * propagation headers with {@code HttpRequest.setHeader} and hands everything else to the bootstrap
 * {@link Tracer}. No state: the call's step is the {@code long[]} {@code {seq, hostId, startNanos}}
 * kept in an advice local.
 */
public final class Apache4Helper {
  static final String TRACEPARENT = "traceparent";
  static final String TRACESTATE = "tracestate";

  private Apache4Helper() {}

  /**
   * Sets the propagation headers and opens the HTTP step; returns {@code {seq, hostId,
   * startNanos}}, or null when no step is recorded. The library types are {@code Object} in the
   * signature: the advice that calls this is described from the agent jar, where they do not exist.
   */
  public static long[] start(TraceContext ctx, Object targetHost, Object httpRequest) {
    HttpHost target = (HttpHost) targetHost;
    HttpRequest request = (HttpRequest) httpRequest;
    long startNanos = System.nanoTime();
    Tracer tracer = Argos.tracer();
    String scheme = null;
    String host = null;
    int port = -1;
    String path = null;
    URI uri = request instanceof HttpUriRequest ? ((HttpUriRequest) request).getURI() : null;
    if (target != null) {
      scheme = target.getSchemeName();
      host = target.getHostName();
      port = target.getPort();
    } else if (uri != null) {
      scheme = uri.getScheme();
      host = uri.getHost();
      port = uri.getPort();
    }
    String method = null;
    if (uri != null) {
      path = uri.getRawPath();
      method = ((HttpUriRequest) request).getMethod();
    }
    if (path == null || method == null) {
      RequestLine line = request.getRequestLine();
      if (line != null) {
        method = method == null ? line.getMethod() : method;
        path = path == null ? pathOf(line.getUri()) : path;
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
            method,
            host == null ? null : port > 0 ? host + ":" + port : host,
            path,
            preserved,
            startNanos);
    return seq < 0 ? null : new long[] {seq, hostId, startNanos};
  }

  /** Closes the HTTP step with the response status or the error of the call. */
  public static void end(TraceContext ctx, long[] call, Object httpResponse, Throwable error) {
    HttpResponse response = (HttpResponse) httpResponse;
    int status = 0;
    if (response != null) {
      StatusLine line = response.getStatusLine();
      if (line != null) {
        status = line.getStatusCode();
      }
    }
    Argos.tracer().httpEnd(ctx, (int) call[0], call[1], status, error, call[2], System.nanoTime());
  }

  private static String value(Header header) {
    return header == null ? null : header.getValue();
  }

  /** The path (and query) of a request line URI, which may be absolute ({@code http://h:1/p}). */
  static String pathOf(String requestUri) {
    if (requestUri == null) {
      return null;
    }
    int scheme = requestUri.indexOf("://");
    if (scheme < 0) {
      return requestUri;
    }
    int slash = requestUri.indexOf('/', scheme + 3);
    return slash < 0 ? "/" : requestUri.substring(slash);
  }
}
