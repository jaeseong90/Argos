/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * A client of the toxiproxy HTTP API (toxiproxy 2.x README "HTTP API") on the JDK's HTTP client; no
 * client library. Only what the chaos scenarios use: create a proxy, change its upstream, add and
 * remove toxics, reset.
 */
public final class ToxiproxyClient {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Set<String> STREAMS = Set.of("upstream", "downstream");

  /**
   * One toxic (toxiproxy "Toxics"): the stream is "upstream" (client to server) or "downstream"
   * (server to client); the toxicity is the probability that the toxic applies to a connection
   * (0..1).
   *
   * @param name unique name within the proxy
   * @param type {@code latency}, {@code timeout}, {@code limit_data}, {@code reset_peer}, ...
   * @param stream "upstream" or "downstream"
   * @param toxicity 0..1
   * @param attributes the type's attributes ({@code latency}, {@code timeout}, {@code bytes})
   */
  public record Toxic(
      String name, String type, String stream, double toxicity, Map<String, Long> attributes) {

    /** Validates and copies. */
    public Toxic {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(type, "type");
      if (!STREAMS.contains(stream)) {
        throw new IllegalArgumentException("stream must be upstream or downstream: " + stream);
      }
      if (!(toxicity >= 0 && toxicity <= 1)) {
        throw new IllegalArgumentException("toxicity must be 0..1: " + toxicity);
      }
      attributes = Map.copyOf(attributes);
    }

    /** Adds {@code ms} of delay to the data of {@code stream} (no jitter). */
    public static Toxic latency(String name, String stream, long ms) {
      return new Toxic(name, "latency", stream, 1.0, Map.of("latency", ms, "jitter", 0L));
    }

    /** Stops the data and closes the connection after {@code ms} (0: never closes). */
    public static Toxic timeout(String name, String stream, long ms, double toxicity) {
      return new Toxic(name, "timeout", stream, toxicity, Map.of("timeout", ms));
    }

    /** Closes the connection after {@code bytes} of data. */
    public static Toxic limitData(String name, String stream, long bytes, double toxicity) {
      return new Toxic(name, "limit_data", stream, toxicity, Map.of("bytes", bytes));
    }

    /** Resets the connection (TCP RST) after {@code ms}. */
    public static Toxic resetPeer(String name, String stream, long ms, double toxicity) {
      return new Toxic(name, "reset_peer", stream, toxicity, Map.of("timeout", ms));
    }

    /** The request body of {@code POST /proxies/{proxy}/toxics}. */
    public String json() {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("name", name);
      body.put("type", type);
      body.put("stream", stream);
      body.put("toxicity", toxicity);
      body.put("attributes", new java.util.TreeMap<>(attributes));
      return JSON.writeValueAsString(body);
    }
  }

  private final URI base;
  private final HttpClient http;

  /** A client of the API at {@code base} ({@code http://127.0.0.1:<port>}). */
  public ToxiproxyClient(URI base) {
    this.base = base;
    this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  /** The body of {@code POST /proxies}. */
  static String proxyJson(String name, String listen, String upstream) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("listen", listen);
    body.put("upstream", upstream);
    body.put("enabled", true);
    return JSON.writeValueAsString(body);
  }

  /** The body of {@code POST /proxies/{name}} that changes the upstream. */
  static String upstreamJson(String upstream) {
    return JSON.writeValueAsString(Map.of("upstream", upstream));
  }

  /** Creates proxy {@code name}: {@code listen} (inside toxiproxy) to {@code upstream}. */
  public void createProxy(String name, String listen, String upstream) {
    send("POST", "/proxies", proxyJson(name, listen, upstream), 201);
  }

  /** The body of {@code POST /proxies/{name}} that enables or disables the proxy. */
  static String enabledJson(boolean enabled) {
    return JSON.writeValueAsString(Map.of("enabled", enabled));
  }

  /**
   * Enables or disables proxy {@code name}; disabling closes its listener and every open connection
   * (the agent sees its stream fail and reconnects).
   */
  public void setEnabled(String name, boolean enabled) {
    send("POST", "/proxies/" + name, enabledJson(enabled), 200);
  }

  /** Points proxy {@code name} at another upstream (open connections keep the old one). */
  public void updateUpstream(String name, String upstream) {
    send("POST", "/proxies/" + name, upstreamJson(upstream), 200);
  }

  /** Adds {@code toxic} to proxy {@code proxy}. */
  public void addToxic(String proxy, Toxic toxic) {
    send("POST", "/proxies/" + proxy + "/toxics", toxic.json(), 200);
  }

  /** Removes toxic {@code name} of proxy {@code proxy}. */
  public void removeToxic(String proxy, String name) {
    send("DELETE", "/proxies/" + proxy + "/toxics/" + name, null, 204);
  }

  /** The names of the toxics of proxy {@code proxy}. */
  public List<String> toxics(String proxy) {
    String body = send("GET", "/proxies/" + proxy + "/toxics", null, 200);
    return JSON.readTree(body).valueStream().map(n -> n.path("name").asString()).toList();
  }

  /** Removes every toxic and enables every proxy ({@code POST /reset}). */
  public void reset() {
    send("POST", "/reset", "", 204);
  }

  /** The server version ({@code GET /version}). */
  public String version() {
    return send("GET", "/version", null, 200).strip();
  }

  private String send(String method, String path, String body, int expected) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(base.resolve(path)).timeout(TIMEOUT).header("Accept", "*/*");
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request.header("Content-Type", "application/json");
      request.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    HttpResponse<String> response;
    try {
      response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
    // Some toxiproxy versions answer 200 where others answer 201 or 204.
    if (response.statusCode() != expected && response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          String.format(
              Locale.ROOT,
              "toxiproxy %s %s: %d %s",
              method,
              path,
              response.statusCode(),
              response.body()));
    }
    return response.body();
  }
}
