/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.http;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One outgoing GET of a sample app (GET /api/pay/{id}, GET /api/hop/{id}): target, request headers
 * the app sets itself ({@code preset=1}: its own {@code traceparent} and {@code tracestate}), the
 * client call style ({@code mode}) and whether the call goes to the next hop of a chain (then the
 * client uses {@link #hopReadTimeoutMs(int)} as read timeout, so that a hop outlives the read
 * timeout of the ext-stub call at the end of the chain).
 */
public final class OutboundRequest {

  /** The client's default synchronous call (every client). */
  public static final String SYNC = "sync";

  /** OkHttp {@code enqueue} and java.net.http {@code sendAsync} (the app waits for the result). */
  public static final String ASYNC = "async";

  /** WebClient {@code block()} (the same as {@link #SYNC} for WebClient). */
  public static final String BLOCK = "block";

  /** WebClient {@code toFuture().get()}. */
  public static final String FUTURE = "future";

  /** WebClient {@code subscribe(...)}; the app waits on a latch for the callback. */
  public static final String SUBSCRIBE = "subscribe";

  /** Upper bound of {@link #hopReadTimeoutMs(int)}. */
  static final int MAX_HOP_READ_TIMEOUT_MS = 70_000;

  private final URI uri;
  private final Map<String, String> headers = new LinkedHashMap<>();
  private String mode = SYNC;
  private boolean hop;

  private OutboundRequest(URI uri) {
    if (uri == null) {
      throw new IllegalArgumentException("uri is required");
    }
    this.uri = uri;
  }

  /** A plain synchronous GET of {@code uri} without extra headers. */
  public static OutboundRequest get(URI uri) {
    return new OutboundRequest(uri);
  }

  /** Adds a request header the app sets itself (replaces an earlier value of the same name). */
  public OutboundRequest header(String name, String value) {
    headers.put(name, value);
    return this;
  }

  /** The call style ({@link #SYNC} when null or empty). */
  public OutboundRequest mode(String value) {
    this.mode = value == null || value.isEmpty() ? SYNC : value;
    return this;
  }

  /** A call to the next hop of GET /api/hop/{id} (longer read timeout). */
  public OutboundRequest hop(boolean value) {
    this.hop = value;
    return this;
  }

  public URI uri() {
    return uri;
  }

  /** Headers in insertion order (unmodifiable). */
  public Map<String, String> headers() {
    return Collections.unmodifiableMap(headers);
  }

  public String mode() {
    return mode;
  }

  public boolean hop() {
    return hop;
  }

  /** The read timeout of this call: {@code readTimeoutMs}, or the hop timeout for a hop call. */
  public int readTimeoutMs(int readTimeoutMs) {
    return hop ? hopReadTimeoutMs(readTimeoutMs) : readTimeoutMs;
  }

  /**
   * Read timeout of a call to the next hop: the ext-stub read timeout plus 10 s (at most 70 s), so
   * that every hop of a chain of at most {@link HopService#MAX_DEPTH} waits for the one after it,
   * also when the last call runs into its own read timeout ({@code fail=timeout}).
   */
  public static int hopReadTimeoutMs(int readTimeoutMs) {
    return Math.min(readTimeoutMs + 10_000, MAX_HOP_READ_TIMEOUT_MS);
  }

  /**
   * How long the app thread waits for an asynchronous call ({@code async}, {@code future}, {@code
   * subscribe}): connect plus read timeout plus 2 s, so that the client's own timeout fires first.
   */
  public long asyncWaitMs(int connectTimeoutMs, int readTimeoutMs) {
    return (long) connectTimeoutMs + readTimeoutMs(readTimeoutMs) + 2_000L;
  }

  @Override
  public String toString() {
    return "GET " + uri + " mode=" + mode + (hop ? " hop" : "") + " headers=" + headers.keySet();
  }
}
