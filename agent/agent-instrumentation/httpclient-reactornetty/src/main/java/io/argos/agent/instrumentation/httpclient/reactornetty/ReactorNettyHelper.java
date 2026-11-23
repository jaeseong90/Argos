/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.reactornetty;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import java.net.SocketAddress;
import java.util.Map;
import reactor.netty.http.client.HttpClientInfos;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.http.client.HttpClientState;

/**
 * The HTTP step of one Reactor Netty {@code HttpClient} request (10 §6.5, F-A33), injected into the
 * class loader of the library (10 §5.4). The package-private {@code HttpClientHandler} of a request
 * is the key of two {@link ContextStore}s (typed by its public super class {@code SocketAddress}):
 * the transaction of the subscribing thread and the call state {@code long[] {seq, startNanos,
 * hostId, phase}}. Every method passes the transaction to the {@link Tracer} directly and never
 * binds it to the event-loop thread. No static state.
 *
 * <p>No Netty type appears in this class (10 §2 B-4): {@code agent-dist} relocates every {@code
 * io.netty} reference of the agent jar, method descriptors included, to the agent's own shaded
 * Netty, so a call such as {@code requestHeaders()} (it returns Netty's {@code HttpHeaders}) would
 * not link against the application's Reactor Netty. Headers are therefore set with {@code
 * HttpClientRequest.header(CharSequence, CharSequence)}, read as the {@code Iterable<Map.Entry>}
 * that Netty's {@code HttpHeaders} is (obtained through the public {@code HttpClientInfos}
 * interface), and the status is read from {@code HttpResponseStatus.toString()} ({@code "200 OK"}),
 * the last two through {@code java.lang.reflect} on public reactor-netty interfaces.
 *
 * <p>Phases: {@link #NEW} (attached at subscribe), {@link #STARTED} (step opened at the first
 * request), {@link #ENDED} (closed by the final response or the final error; both store entries are
 * removed). The signals of one request are ordered by the reactive chain (subscribe, request,
 * response or error), so the state needs no locking.
 */
public final class ReactorNettyHelper {
  static final String TRACEPARENT = "traceparent";
  static final String TRACESTATE = "tracestate";

  static final int SEQ = 0;
  static final int START = 1;
  static final int HOST = 2;
  static final int PHASE = 3;

  static final long NEW = 0;
  static final long STARTED = 1;
  static final long ENDED = 2;

  private ReactorNettyHelper() {}

  /** Handler created on a thread with a transaction: attaches it and the start time. */
  public static void attach(SocketAddress handler) {
    TraceContext context = Argos.tracer().current();
    if (context == null) {
      return;
    }
    contexts().put(handler, context);
    calls().put(handler, new long[] {-1L, System.nanoTime(), 0L, NEW});
  }

  /**
   * A request is about to be written: propagation headers for its target, and the step at the first
   * request.
   */
  public static void request(SocketAddress handler, Object operations, Object uri, Object method) {
    TraceContext context = contexts().get(handler);
    long[] call = context == null ? null : calls().get(handler);
    if (call == null || call[PHASE] == ENDED || !(operations instanceof HttpClientRequest)) {
      return;
    }
    Tracer tracer = Argos.tracer();
    String[] target = split(String.valueOf(uri));
    HttpClientRequest request = (HttpClientRequest) operations;
    String[] existing = existingHeaders(request);
    String[] values =
        tracer.outgoingHeaders(
            context, target == null ? null : target[1], existing[0], existing[1]);
    String preserved = null;
    if (values != null) {
      if (values[0] != null) {
        request.header(TRACEPARENT, values[0]);
      } else {
        preserved = existing[0];
      }
      if (values[1] != null) {
        request.header(TRACESTATE, values[1]);
      }
    }
    if (call[PHASE] == NEW) {
      open(tracer, context, call, target, method, preserved);
    }
  }

  /** Upper bound of request headers read when looking for the propagation headers. */
  static final int MAX_HEADERS = 256;

  /**
   * {@code {traceparent, tracestate}} already on the request (default headers of the client and
   * those the application's send function set), null when absent.
   */
  static String[] existingHeaders(HttpClientRequest request) {
    String traceparent = null;
    String tracestate = null;
    Object headers;
    try {
      headers = HttpClientInfos.class.getMethod("requestHeaders").invoke(request);
    } catch (ReflectiveOperationException | RuntimeException e) {
      headers = null;
    }
    if (headers instanceof Iterable) {
      int seen = 0;
      for (Object item : (Iterable<?>) headers) {
        if (++seen > MAX_HEADERS) {
          break;
        }
        if (!(item instanceof Map.Entry)) {
          continue;
        }
        Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
        String name = String.valueOf(entry.getKey());
        if (traceparent == null && TRACEPARENT.equalsIgnoreCase(name)) {
          traceparent = String.valueOf(entry.getValue());
        } else if (tracestate == null && TRACESTATE.equalsIgnoreCase(name)) {
          tracestate = String.valueOf(entry.getValue());
        }
      }
    }
    return new String[] {traceparent, tracestate};
  }

  /** The status code of a response ({@code HttpResponseStatus.toString()} is "200 OK"), or 0. */
  static int status(Object connection) {
    if (!(connection instanceof HttpClientResponse)) {
      return 0;
    }
    Object status;
    try {
      status = HttpClientResponse.class.getMethod("status").invoke(connection);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return 0;
    }
    return leadingCode(String.valueOf(status));
  }

  /** The three-digit code at the start of {@code text}, or 0. */
  static int leadingCode(String text) {
    if (text == null || text.length() < 3) {
      return 0;
    }
    int code = 0;
    for (int i = 0; i < 3; i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        return 0;
      }
      code = code * 10 + (c - '0');
    }
    return text.length() == 3 || text.charAt(3) == ' ' ? code : 0;
  }

  /** {@code onStateChange}: the final response closes the step with its status. */
  public static void responseReceived(Object handler, Object connection, Object state) {
    if (state != HttpClientState.RESPONSE_RECEIVED || !(handler instanceof SocketAddress)) {
      return;
    }
    SocketAddress key = (SocketAddress) handler;
    long[] call = calls().get(key);
    if (call == null || call[PHASE] != STARTED) {
      return;
    }
    end(key, call, status(connection), null);
  }

  /**
   * The final error of the request: closes the step with it, opening it first when the request was
   * never written (no connection).
   */
  public static void failed(SocketAddress handler, Throwable error, Object uri, Object method) {
    long[] call = calls().get(handler);
    if (call == null || call[PHASE] == ENDED) {
      return;
    }
    if (call[PHASE] == NEW) {
      TraceContext context = contexts().get(handler);
      if (context == null) {
        return;
      }
      open(Argos.tracer(), context, call, split(String.valueOf(uri)), method, null);
    }
    end(handler, call, 0, error);
  }

  private static void open(
      Tracer tracer,
      TraceContext context,
      long[] call,
      String[] target,
      Object method,
      String preserved) {
    String scheme = target == null ? null : target[0];
    String host = target == null ? null : target[1];
    int port = target == null ? -1 : port(target[2]);
    long hostId = tracer.hostId(scheme, host, port);
    String hostPort = null;
    if (host != null) {
      String hostText = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
      hostPort = port > 0 ? hostText + ":" + port : hostText;
    }
    call[HOST] = hostId;
    call[SEQ] =
        tracer.httpStart(
            context,
            hostId,
            method == null ? null : method.toString(),
            hostPort,
            target == null ? null : target[3],
            preserved,
            call[START]);
    call[PHASE] = STARTED;
  }

  private static void end(SocketAddress handler, long[] call, int status, Throwable error) {
    call[PHASE] = ENDED;
    TraceContext context = contexts().remove(handler);
    calls().remove(handler);
    Argos.tracer()
        .httpEnd(
            context, (int) call[SEQ], call[HOST], status, error, call[START], System.nanoTime());
  }

  private static ContextStore<SocketAddress, TraceContext> contexts() {
    return ContextStore.of(SocketAddress.class, TraceContext.class);
  }

  private static ContextStore<SocketAddress, long[]> calls() {
    return ContextStore.of(SocketAddress.class, long[].class);
  }

  /**
   * {@code {scheme, host, port, pathAndQuery}} of an absolute URL ({@code UriEndpoint.toString()}
   * is its external form {@code scheme://host[:port]/path?query}); the host without brackets and
   * user info, the port text empty when absent. Null when {@code url} is not absolute.
   */
  static String[] split(String url) {
    int schemeEnd = url.indexOf("://");
    if (schemeEnd <= 0) {
      return null;
    }
    int start = schemeEnd + 3;
    int end = url.length();
    for (int i = start; i < url.length(); i++) {
      char c = url.charAt(i);
      if (c == '/' || c == '?' || c == '#') {
        end = i;
        break;
      }
    }
    String authority = url.substring(start, end);
    int at = authority.lastIndexOf('@');
    if (at >= 0) {
      authority = authority.substring(at + 1);
    }
    String host = authority;
    String port = "";
    if (authority.startsWith("[")) {
      int close = authority.indexOf(']');
      if (close > 0) {
        host = authority.substring(1, close);
        if (close + 1 < authority.length() && authority.charAt(close + 1) == ':') {
          port = authority.substring(close + 2);
        }
      }
    } else {
      int colon = authority.lastIndexOf(':');
      if (colon >= 0) {
        host = authority.substring(0, colon);
        port = authority.substring(colon + 1);
      }
    }
    String path = end < url.length() ? url.substring(end) : "/";
    return new String[] {url.substring(0, schemeEnd), host, port, path};
  }

  private static int port(String text) {
    if (text == null || text.isEmpty() || text.length() > 5) {
      return -1;
    }
    int value = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        return -1;
      }
      value = value * 10 + (c - '0');
    }
    return value;
  }
}
