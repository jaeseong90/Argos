/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * The OTLP receiver of the OpenTelemetry reference variant (01 §7.3 item 11, 18 §7.5): a black hole
 * on 127.0.0.1 (a random port) that answers {@code POST /v1/traces}, {@code /v1/metrics} and {@code
 * /v1/logs} with 200 and an empty protobuf message ({@code application/x-protobuf}, no body bytes),
 * counts the requests and the body bytes per path and keeps nothing else (no payload is stored or
 * logged). The OpenTelemetry Java agent then pays its whole export path (batching, protobuf
 * encoding, HTTP) as a real installation would, without a collector on the machine. Only the JDK's
 * {@code com.sun.net.httpserver}; development and bench use only, never shipped.
 *
 * <p>The OpenTelemetry Java agent itself (Apache-2.0) is pinned here and in {@code
 * fetch-otel-agent.sh}: version {@value #OTEL_AGENT_VERSION}, SHA-256 {@value #OTEL_AGENT_SHA256}.
 * The script fetches it once into {@code test/bench/build/otel/}; {@code --otel-agent auto} only
 * resolves that file and never downloads during a run (and nothing is fetched at agent or server
 * runtime).
 */
final class OtlpBlackHole implements AutoCloseable {

  /** OTLP/HTTP signal paths (OTLP 1.x specification). */
  static final List<String> PATHS = List.of("/v1/traces", "/v1/metrics", "/v1/logs");

  static final String CONTENT_TYPE = "application/x-protobuf";

  static final String OTEL_AGENT_VERSION = "2.32.0";

  /** SHA-256 of {@code opentelemetry-javaagent-2.32.0.jar} (docs/poc/agent-overhead.md 9.1). */
  static final String OTEL_AGENT_SHA256 =
      "f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82";

  static final String OTEL_AGENT_FILE = "opentelemetry-javaagent-" + OTEL_AGENT_VERSION + ".jar";

  /** Where fetch-otel-agent.sh puts the jar, relative to the repository root. */
  static final String OTEL_AGENT_DIR = "test/bench/build/otel";

  /** Request and body byte counts (whole receiver or per path). */
  record Counts(long requests, long bytes) {
    Counts minus(Counts before) {
      return new Counts(requests - before.requests, bytes - before.bytes);
    }
  }

  private final HttpServer server;
  private final ExecutorService executor;
  private final Map<String, LongAdder> requests = new LinkedHashMap<>();
  private final Map<String, LongAdder> bytes = new LinkedHashMap<>();
  private final LongAdder rejected = new LongAdder();

  private OtlpBlackHole(HttpServer server, ExecutorService executor) {
    this.server = server;
    this.executor = executor;
    for (String path : PATHS) {
      requests.put(path, new LongAdder());
      bytes.put(path, new LongAdder());
    }
  }

  /** Starts a receiver on 127.0.0.1 and a free port. */
  static OtlpBlackHole start() throws IOException {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64);
    AtomicInteger threads = new AtomicInteger();
    ExecutorService executor =
        Executors.newFixedThreadPool(
            2,
            r -> {
              Thread t = new Thread(r, "bench-otlp-" + threads.incrementAndGet());
              t.setDaemon(true);
              return t;
            });
    OtlpBlackHole hole = new OtlpBlackHole(server, executor);
    server.createContext("/", hole::handle);
    server.setExecutor(executor);
    server.start();
    return hole;
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      long body = drain(exchange.getRequestBody());
      if (!requests.containsKey(path)) {
        rejected.increment();
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      if (!"POST".equals(exchange.getRequestMethod())) {
        rejected.increment();
        exchange.getResponseHeaders().set("Allow", "POST");
        exchange.sendResponseHeaders(405, -1);
        return;
      }
      requests.get(path).increment();
      bytes.get(path).add(body);
      // An empty Export*ServiceResponse: zero protobuf bytes (full success, no partial_success).
      exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
      exchange.sendResponseHeaders(200, -1);
    }
  }

  /** Reads and discards the body, returning its length (the payload is never kept). */
  private static long drain(InputStream in) throws IOException {
    byte[] buffer = new byte[16 * 1024];
    long total = 0;
    for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
      total += n;
    }
    return total;
  }

  int port() {
    return server.getAddress().getPort();
  }

  InetSocketAddress address() {
    return server.getAddress();
  }

  /** Base endpoint for {@code otel.exporter.otlp.endpoint} (the agent appends /v1/...). */
  String endpoint() {
    return "http://127.0.0.1:" + port();
  }

  /** Accepted requests and body bytes over all paths since start. */
  Counts total() {
    long r = 0;
    long b = 0;
    for (String path : PATHS) {
      r += requests.get(path).sum();
      b += bytes.get(path).sum();
    }
    return new Counts(r, b);
  }

  /** Accepted requests and body bytes of {@code path} since start. */
  Counts of(String path) {
    LongAdder r = requests.get(path);
    if (r == null) {
      throw new IllegalArgumentException("not an OTLP path: " + path);
    }
    return new Counts(r.sum(), bytes.get(path).sum());
  }

  /** Requests answered 404 or 405 (not counted in {@link #total()}). */
  long rejected() {
    return rejected.sum();
  }

  /** Per-path counts, for comparing a window's start and end. */
  Map<String, Counts> snapshot() {
    Map<String, Counts> out = new LinkedHashMap<>();
    for (String path : PATHS) {
      out.put(path, of(path));
    }
    return out;
  }

  /**
   * {@code measure.otlp} of a window: {@code requests} and {@code bytes} over all paths between two
   * {@link #snapshot()}s, and the requests per signal.
   */
  static Map<String, Object> window(Map<String, Counts> start, Map<String, Counts> end) {
    long r = 0;
    long b = 0;
    Map<String, Object> perSignal = new LinkedHashMap<>();
    for (String path : PATHS) {
      Counts c = end.get(path).minus(start.get(path));
      r += c.requests();
      b += c.bytes();
      perSignal.put(path.substring("/v1/".length()), c.requests());
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("requests", r);
    out.put("bytes", b);
    out.put("requestsBySignal", perSignal);
    out.put("receiver", "OTLP/HTTP black hole on 127.0.0.1 (bench harness, payload discarded)");
    return out;
  }

  /**
   * System properties of the OpenTelemetry reference variant: OTLP over HTTP/protobuf to {@code
   * endpoint}, traces and metrics exported, logs not (the Argos agent exports no application logs
   * either). Without an endpoint (a container cannot reach the host's loopback receiver) every
   * exporter is {@code none}, the WO-05 setting.
   */
  static List<String> otelOptions(String endpoint) {
    if (endpoint == null) {
      return List.of(
          "-Dotel.traces.exporter=none",
          "-Dotel.metrics.exporter=none",
          "-Dotel.logs.exporter=none");
    }
    return List.of(
        "-Dotel.exporter.otlp.protocol=http/protobuf",
        "-Dotel.exporter.otlp.endpoint=" + endpoint,
        "-Dotel.traces.exporter=otlp",
        "-Dotel.metrics.exporter=otlp",
        "-Dotel.logs.exporter=none");
  }

  /**
   * {@code --otel-agent}: {@code auto} is {@code <repo>/test/bench/build/otel/<pinned jar>} as
   * fetch-otel-agent.sh left it, checked against the pinned SHA-256; it is a usage error when the
   * file is missing or differs (the harness never downloads). Any other value is a path, used as
   * given (its SHA-256 is recorded with the set's inputs).
   */
  static Path resolveOtelAgent(String value, Path repo) throws IOException {
    if (!value.equals("auto")) {
      return Path.of(value).toAbsolutePath().normalize();
    }
    Path jar = repo.resolve(OTEL_AGENT_DIR).resolve(OTEL_AGENT_FILE).toAbsolutePath().normalize();
    if (!Files.isRegularFile(jar)) {
      throw new UsageException(
          "--otel-agent auto: "
              + OTEL_AGENT_DIR
              + "/"
              + OTEL_AGENT_FILE
              + " is missing; run test/bench/fetch-otel-agent.sh first (the harness never"
              + " downloads during a run)");
    }
    String sha = FileTools.sha256(jar);
    if (!sha.equals(OTEL_AGENT_SHA256)) {
      throw new UsageException(
          "--otel-agent auto: "
              + jar
              + " has SHA-256 "
              + sha
              + ", expected "
              + OTEL_AGENT_SHA256
              + "; delete it and run test/bench/fetch-otel-agent.sh again");
    }
    return jar;
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
