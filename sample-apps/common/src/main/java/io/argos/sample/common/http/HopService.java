/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.http;

import java.net.URI;
import java.util.Locale;

/**
 * GET /api/hop/{id}?depth=N&client=X[&fail=timeout|refuse|status500][&mode=M][&preset=1]: a chain
 * of instrumented apps for distributed traces (T-ACC-05, 10 §8, WO-14).
 *
 * <ul>
 *   <li>{@code depth} > 0: one call with client X to {@code <hop base>/api/hop/{id}?depth=N-1} with
 *       the same client, {@code fail} and {@code mode} ({@code preset} is not passed on: only the
 *       first call carries the app's own trace headers). The hop base is {@value #HOP_URL_ENV},
 *       else the app itself ({@code http://127.0.0.1:<local port><context path>}). The call uses
 *       the hop read timeout ({@link OutboundRequest#hopReadTimeoutMs}).
 *   <li>{@code depth} 0: the GET /api/pay/{id} call to ext-stub ({@link PayService}), {@code fail}
 *       selecting {@code timeout=1}, {@code refuse=1} or {@code status=500}.
 * </ul>
 *
 * The app answers like GET /api/pay/{id} ({@link OutboundOutcome}): 200 when the next hop answered
 * 2xx, 502 when it answered an error status (the error of a deeper hop arrives as that status), 503
 * or 504 when the call itself failed. At most {@value #MAX_DEPTH} hops.
 */
public final class HopService {

  /** Largest {@code depth}. */
  public static final int MAX_DEPTH = 5;

  /** Environment variable with the base URL of the next hop (default: the app itself). */
  public static final String HOP_URL_ENV = "SAMPLE_HOP_URL";

  private final PayService pay;
  private final String hopUrl;

  /**
   * @param pay the app's clients and ext-stub call
   * @param hopUrl base URL of the next hop (http or https, no query), or null/empty for the app
   *     itself
   */
  public HopService(PayService pay, String hopUrl) {
    this.pay = pay;
    this.hopUrl = normalize(hopUrl);
  }

  /** Reads {@value #HOP_URL_ENV} from the process environment. */
  public static HopService fromEnvironment(PayService pay) {
    return new HopService(pay, System.getenv(HOP_URL_ENV));
  }

  /** The configured next-hop base URL, or null when the app calls itself. */
  public String hopUrl() {
    return hopUrl;
  }

  /** Failure of the ext-stub call at the end of the chain. */
  public enum Fail {
    NONE,
    TIMEOUT,
    REFUSE,
    STATUS500;

    /** Parses {@code ?fail=} (null or empty: none); IllegalArgumentException (400) otherwise. */
    public static Fail parse(String value) {
      if (value == null || value.isEmpty()) {
        return NONE;
      }
      switch (value.toLowerCase(Locale.ROOT)) {
        case "timeout":
          return TIMEOUT;
        case "refuse":
          return REFUSE;
        case "status500":
          return STATUS500;
        default:
          throw new IllegalArgumentException("fail must be timeout, refuse or status500");
      }
    }

    String parameter() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Request parameters of GET /api/hop/{id}. */
  public static final class Options {
    String client = PayService.DEFAULT_CLIENT;
    int depth;
    Fail fail = Fail.NONE;
    String mode = OutboundRequest.SYNC;
    boolean preset;

    public Options client(String value) {
      if (value != null && !value.isEmpty()) {
        this.client = value;
      }
      return this;
    }

    public Options depth(int value) {
      if (value < 0 || value > MAX_DEPTH) {
        throw new IllegalArgumentException("depth must be between 0 and " + MAX_DEPTH);
      }
      this.depth = value;
      return this;
    }

    public Options fail(String value) {
      this.fail = Fail.parse(value);
      return this;
    }

    public Options mode(String value) {
      this.mode = value == null || value.isEmpty() ? OutboundRequest.SYNC : value;
      return this;
    }

    public Options preset(boolean value) {
      this.preset = value;
      return this;
    }
  }

  /**
   * GET /api/hop/{id}. {@code selfBaseUrl} is the app's own base URL ({@code
   * http://127.0.0.1:<local port><context path>}), used when {@value #HOP_URL_ENV} is not set.
   */
  public PayService.Result hop(long id, Options options, String selfBaseUrl) {
    PayService.Result result;
    if (options.depth == 0) {
      result =
          pay.pay(
              id,
              new PayService.Options()
                  .client(options.client)
                  .timeout(options.fail == Fail.TIMEOUT)
                  .refuse(options.fail == Fail.REFUSE)
                  .status(options.fail == Fail.STATUS500 ? Integer.valueOf(500) : null)
                  .mode(options.mode)
                  .preset(options.preset));
    } else {
      URI target = URI.create(nextHop(id, options, selfBaseUrl));
      result =
          pay.call(
              options.client,
              OutboundRequest.get(target).mode(options.mode).hop(true),
              options.preset);
      result.body().put("orderId", id);
    }
    result.body().put("hop", options.depth);
    return result;
  }

  /** The URL of the next hop of {@code options} (depth > 0). */
  String nextHop(long id, Options options, String selfBaseUrl) {
    String base = hopUrl != null ? hopUrl : normalize(selfBaseUrl);
    if (base == null) {
      throw new IllegalStateException("no hop base URL");
    }
    StringBuilder url =
        new StringBuilder(base)
            .append("/api/hop/")
            .append(id)
            .append("?depth=")
            .append(options.depth - 1)
            .append("&client=")
            .append(PayService.encodePathSegment(options.client));
    if (options.fail != Fail.NONE) {
      url.append("&fail=").append(options.fail.parameter());
    }
    if (!OutboundRequest.SYNC.equals(options.mode)) {
      url.append("&mode=").append(PayService.encodePathSegment(options.mode));
    }
    return url.toString();
  }

  private static String normalize(String url) {
    if (url == null || url.trim().isEmpty()) {
      return null;
    }
    String value = url.trim();
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(HOP_URL_ENV + " is not a URL: '" + value + "'", e);
    }
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalArgumentException(
          HOP_URL_ENV + " must be an http(s) base URL without query, got '" + value + "'");
    }
    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    return value;
  }
}
