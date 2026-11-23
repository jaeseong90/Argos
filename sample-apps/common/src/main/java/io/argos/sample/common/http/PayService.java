/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.http;

import io.argos.sample.common.SampleConfig;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * GET /api/pay/{id} (18 §4.2): one outgoing call to ext-stub with the chosen client. Targets:
 *
 * <ul>
 *   <li>default: {@code <SAMPLE_EXT_URL>/pay/{id}} answers 200 (app: 200)
 *   <li>{@code timeout=1}: {@code /timeout}, ext-stub never answers; the client read timeout
 *       (SAMPLE_EXT_TIMEOUT_MS) fires (app: 504)
 *   <li>{@code refuse=1}: port SAMPLE_EXT_REFUSED_PORT of the ext-stub host, where nothing listens
 *       (app: 503)
 *   <li>{@code status=500}: {@code /status/500} (app: 502)
 *   <li>{@code redirect=1}: {@code /redirect?to=/pay/{id}}, followed by the client (app: 200)
 * </ul>
 *
 * <p>{@code mode=} picks the client call style ({@link OutboundClient#modes()}: {@code sync}
 * always, {@code async} for OkHttp {@code enqueue} and java.net.http {@code sendAsync}, {@code
 * block|future|subscribe} for WebClient); {@code preset=1} makes the app set its own {@code
 * traceparent} and {@code tracestate} ({@value #PRESET_TRACESTATE}) on the request, reported as
 * {@code presetTraceparent} (the agent must keep it, 10 §8).
 */
public final class PayService {

  /** Default client of GET /api/pay/{id} (available in every app). */
  public static final String DEFAULT_CLIENT = "hurl";

  /** W3C trace context request header. */
  public static final String TRACEPARENT = "traceparent";

  /** W3C trace context request header. */
  public static final String TRACESTATE = "tracestate";

  /** The {@code tracestate} the app sets itself with {@code preset=1} (one vendor entry). */
  public static final String PRESET_TRACESTATE = "shop=preset1";

  private final SampleConfig config;
  private final Map<String, OutboundClient> clients = new LinkedHashMap<>();

  public PayService(SampleConfig config, List<OutboundClient> clients) {
    this.config = config;
    for (OutboundClient client : clients) {
      this.clients.put(client.name(), client);
    }
    if (!this.clients.containsKey(DEFAULT_CLIENT)) {
      throw new IllegalArgumentException("The '" + DEFAULT_CLIENT + "' client is required");
    }
  }

  /** Client names of this app, in registration order (reported by GET /api/runtime). */
  public List<String> clientNames() {
    return Collections.unmodifiableList(new ArrayList<>(clients.keySet()));
  }

  /** Request parameters of GET /api/pay/{id}. */
  public static final class Options {
    String client = DEFAULT_CLIENT;
    boolean timeout;
    boolean refuse;
    Integer status;
    boolean redirect;
    String mode = OutboundRequest.SYNC;
    boolean preset;

    /** {@code ?mode=}: the client call style (null or empty: {@code sync}). */
    public Options mode(String value) {
      this.mode = value == null || value.isEmpty() ? OutboundRequest.SYNC : value;
      return this;
    }

    /** {@code ?preset=1}: the app sets its own {@code traceparent} and {@code tracestate}. */
    public Options preset(boolean value) {
      this.preset = value;
      return this;
    }

    public Options client(String value) {
      if (value != null && !value.isEmpty()) {
        this.client = value;
      }
      return this;
    }

    public Options timeout(boolean value) {
      this.timeout = value;
      return this;
    }

    public Options refuse(boolean value) {
      this.refuse = value;
      return this;
    }

    public Options status(Integer value) {
      this.status = value;
      return this;
    }

    public Options redirect(boolean value) {
      this.redirect = value;
      return this;
    }
  }

  /** The app's answer: status and JSON body. */
  public static final class Result {
    private final int httpStatus;
    private final Map<String, Object> body;

    Result(int httpStatus, Map<String, Object> body) {
      this.httpStatus = httpStatus;
      this.body = body;
    }

    public int httpStatus() {
      return httpStatus;
    }

    public Map<String, Object> body() {
      return body;
    }
  }

  /** GET /api/pay/{id}. Throws IllegalArgumentException (400) for an unknown client or status. */
  public Result pay(long orderId, Options options) {
    String base = config.extUrl();
    String target;
    if (options.refuse) {
      URI ext = URI.create(base);
      target =
          ext.getScheme()
              + "://"
              + ext.getHost()
              + ":"
              + config.extRefusedPort()
              + "/pay/"
              + orderId;
    } else if (options.timeout) {
      target = base + "/timeout";
    } else if (options.status != null) {
      if (options.status < 400 || options.status > 599) {
        throw new IllegalArgumentException("status must be between 400 and 599");
      }
      target = base + "/status/" + options.status;
    } else if (options.redirect) {
      target = base + "/redirect?to=/pay/" + orderId;
    } else {
      target = base + "/pay/" + orderId;
    }
    OutboundRequest request = OutboundRequest.get(URI.create(target)).mode(options.mode);
    Result result = call(options.client, request, options.preset);
    result.body.put("orderId", orderId);
    return result;
  }

  /**
   * Calls {@code <SAMPLE_EXT_URL>/seed/<segment>} (XSS seed in the external call path, 18 §4.3).
   */
  public Result callSeedPath(String client, String rawSegment) {
    return call(
        client,
        OutboundRequest.get(URI.create(config.extUrl() + "/seed/" + encodePathSegment(rawSegment))),
        false);
  }

  /**
   * Percent-encodes one path segment or query value (RFC 3986: everything except unreserved
   * characters, UTF-8; '/' is encoded as well).
   */
  public static String encodePathSegment(String raw) {
    StringBuilder out = new StringBuilder();
    for (byte b : raw.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if ((c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9')
          || c == '-'
          || c == '.'
          || c == '_'
          || c == '~') {
        out.append((char) c);
      } else {
        out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xf]);
      }
    }
    return out.toString();
  }

  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private static final char[] LOWER_HEX = "0123456789abcdef".toCharArray();

  /**
   * A valid W3C {@code traceparent} the app sets itself ({@code preset=1}): version 00, a random
   * trace id and parent id (never all zero), sampled flag.
   */
  public static String newTraceparent() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return "00-" + hex(random, 16) + "-" + hex(random, 8) + "-01";
  }

  private static String hex(ThreadLocalRandom random, int bytes) {
    char[] out = new char[bytes * 2];
    boolean nonZero = false;
    for (int i = 0; i < bytes; i++) {
      int b = random.nextInt(256);
      nonZero |= b != 0;
      out[2 * i] = LOWER_HEX[b >> 4];
      out[2 * i + 1] = LOWER_HEX[b & 0xf];
    }
    if (!nonZero) {
      out[out.length - 1] = '1';
    }
    return new String(out);
  }

  /**
   * One outgoing call with {@code clientName}; {@code preset} adds the app's own {@code
   * traceparent} and {@code tracestate} (reported as {@code presetTraceparent} and {@code
   * presetTracestate}). Throws IllegalArgumentException (400) for an unknown client or a mode the
   * client does not support.
   */
  Result call(String clientName, OutboundRequest request, boolean preset) {
    OutboundClient client = clients.get(clientName);
    if (client == null) {
      throw new IllegalArgumentException(
          "Unknown client '" + clientName + "'; this app supports " + clients.keySet());
    }
    if (!client.modes().contains(request.mode())) {
      throw new IllegalArgumentException(
          "Client '"
              + clientName
              + "' does not support mode '"
              + request.mode()
              + "'; it supports "
              + client.modes());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("client", client.name());
    body.put("mode", request.mode());
    body.put("target", request.uri().toString());
    if (preset) {
      String traceparent = newTraceparent();
      request.header(TRACEPARENT, traceparent).header(TRACESTATE, PRESET_TRACESTATE);
      body.put("presetTraceparent", traceparent);
      body.put("presetTracestate", PRESET_TRACESTATE);
    }
    long start = System.nanoTime();
    OutboundOutcome outcome;
    try {
      OutboundResponse response = client.send(request);
      outcome = OutboundOutcome.ofStatus(response.status());
      body.put("upstreamStatus", response.status());
      body.put("upstreamBody", response.body());
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      outcome = OutboundOutcome.classify(e);
      body.put("error", e.getClass().getName() + ": " + e.getMessage());
    }
    body.put("outcome", outcome.name());
    body.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
    return new Result(outcome.appStatus(), body);
  }
}
