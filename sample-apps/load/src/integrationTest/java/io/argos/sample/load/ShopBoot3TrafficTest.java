/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.sample.common.catalog.CatalogEntry;
import io.argos.sample.common.catalog.ShopCatalog;
import io.argos.sample.common.seed.Seeds;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Traffic against the real reference app (shop-boot3 jar on its build JDK 17, ext-stub in process,
 * the seed environment of 18 §4.3 set): every seed endpoint answers with the ARGOS_SEED sentinel,
 * test/seed/seed-traffic.sh calls them N times, and the packaged demo load generator runs both of
 * its mixes without an unexpected answer.
 *
 * <p>Inputs from Gradle (sample-apps/load/build.gradle.kts): argos.seed.appJar, argos.seed.appJava,
 * argos.seed.smokeJar, argos.seed.repositoryRoot, argos.seed.scratch, argos.load.jar.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShopBoot3TrafficTest {

  /** Seed ids of 18 §4.3 (ShopCatalog): 12 single seeds, 8 XSS sinks x 4 payloads, 4 CSV. */
  private static final List<String> SEED_IDS = seedIds();

  private static final Pattern SEEDED = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"seeded\"");

  /**
   * Warm-up of the load.jar runs before the measured window: a cold app (JIT, pools) is excluded.
   */
  private static final String DEMO_LOAD_WARMUP = "3s";

  /**
   * Request timeout of the load.jar runs (the generator's default, stated for the in-flight cap).
   */
  private static final int DEMO_LOAD_REQUEST_TIMEOUT_SECONDS = 10;

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private Path scratch;
  private Path java;
  private ExtStub stub;
  private ShopBoot3App app;

  private static List<String> seedIds() {
    List<String> ids = new ArrayList<>();
    Stream.of(
            "email",
            "email.error",
            "phone",
            "phone.error",
            "rrn",
            "card",
            "card.error",
            "user",
            "xff",
            "token",
            "literals",
            "env")
        .forEach(s -> ids.add("seed." + s));
    for (int i = 0; i < Seeds.XSS.size(); i++) {
      for (String sink :
          List.of("path", "query", "header", "user", "sql", "error", "thread", "ext")) {
        ids.add("seed.xss." + sink + "." + i);
      }
    }
    for (int i = 0; i < Seeds.CSV.size(); i++) {
      ids.add("seed.csv." + i);
    }
    return ids;
  }

  private static Path property(String name) {
    String value = System.getProperty(name);
    assertThat(value).as("system property %s (set by Gradle)", name).isNotBlank();
    return Path.of(value);
  }

  @BeforeAll
  void start() throws IOException, InterruptedException {
    scratch = property("argos.seed.scratch").resolve("shop-boot3-traffic");
    Files.createDirectories(scratch);
    java = property("argos.seed.appJava");
    stub = ExtStub.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64);
    Map<String, String> environment = new LinkedHashMap<>(Seeds.environment());
    environment.put("SAMPLE_EXT_URL", stub.baseUri().toString());
    app =
        ShopBoot3App.start(
            java, property("argos.seed.appJar"), scratch.resolve("app"), environment);
  }

  @AfterAll
  void stop() {
    if (app != null) {
      app.close();
    }
    if (stub != null) {
      stub.close();
    }
  }

  private HttpResponse<String> call(CatalogEntry entry) throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(app.baseUrl() + entry.path()))
            .timeout(Duration.ofSeconds(60))
            .method(entry.method(), HttpRequest.BodyPublishers.noBody());
    entry.headers().forEach(request::header);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @Order(1)
  @DisplayName("Every PII/XSS seed endpoint answers with the ARGOS_SEED sentinel (18 §4.3)")
  void everySeedEndpointCarriesTheSentinel() throws IOException, InterruptedException {
    List<CatalogEntry> seeds =
        ShopCatalog.entries().stream().filter(e -> e.id().startsWith("seed.")).toList();
    assertThat(seeds).extracting(CatalogEntry::id).containsAll(SEED_IDS);

    SoftAssertions soft = new SoftAssertions();
    for (CatalogEntry entry : seeds) {
      HttpResponse<String> response = call(entry);
      String body = response.body();
      soft.assertThat(response.statusCode())
          .as("%s %s status (body: %s)", entry.id(), entry.path(), body)
          .isEqualTo(entry.expectedStatus());
      if (entry.id().equals("seed.env")) {
        // The secret-looking variables carry the sentinel in the app's environment; the endpoint
        // reports that, and must never echo the values themselves.
        List<String> seeded = new ArrayList<>();
        Matcher m = SEEDED.matcher(body);
        while (m.find()) {
          seeded.add(m.group(1));
        }
        soft.assertThat(seeded)
            .as("seed.env: variables seeded with the sentinel (body: %s)", body)
            .containsExactlyInAnyOrderElementsOf(Seeds.environment().keySet());
        soft.assertThat(body)
            .as("seed.env never echoes a secret value")
            .doesNotContain(Seeds.SENTINEL);
      } else {
        soft.assertThat(body).as("%s %s body", entry.id(), entry.path()).contains(Seeds.SENTINEL);
      }
    }
    soft.assertAll();
  }

  @Test
  @Order(2)
  @DisplayName("The XSS seeds of external calls reach ext-stub with the sentinel in the path")
  void seedsReachTheExternalCallTarget() throws IOException, InterruptedException {
    HttpResponse<String> log =
        client.send(
            HttpRequest.newBuilder(stub.baseUri().resolve("/_stub/requests")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(log.statusCode()).isEqualTo(200);
    Matcher m = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]*)\"").matcher(log.body());
    long withSentinel = 0;
    while (m.find()) {
      if (m.group(1).contains(Seeds.SENTINEL)) {
        withSentinel++;
      }
    }
    assertThat(withSentinel)
        .as("ext-stub request paths with the sentinel")
        .isGreaterThanOrEqualTo(Seeds.XSS.size());
  }

  @Test
  @Order(3)
  @DisplayName("test/seed/seed-traffic.sh calls every seed endpoint N times, all with the sentinel")
  void seedTrafficScript() throws IOException, InterruptedException {
    Path root = property("argos.seed.repositoryRoot");
    Path reports = scratch.resolve("seed-traffic");
    Path tmp = scratch.resolve("seed-traffic-tmp");
    Files.createDirectories(tmp);
    Shell.Result result =
        Shell.run(
            root.resolve("test/seed/seed-traffic.sh"),
            List.of(
                "--url",
                app.baseUrl(),
                "--repeat",
                "2",
                "--report-dir",
                Shell.path(reports),
                "--smoke-jar",
                Shell.path(property("argos.seed.smokeJar")),
                "--java",
                Shell.path(java),
                "--no-build"),
            Map.of("TMPDIR", Shell.path(tmp)),
            scratch.resolve("seed-traffic.out"),
            Duration.ofMinutes(5));

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(result.output())
        .contains("seed environment of the app: 4 of 4 secret-looking variables seeded")
        .contains("SMOKE PASS");
    String report = Files.readString(reports.resolve("smoke.json"), StandardCharsets.UTF_8);
    int calls = 2 * SEED_IDS.size();
    assertThat(number(report, "passed")).as("passed seed calls").isEqualTo(calls);
    assertThat(number(report, "failed")).isZero();
    assertThat(number(report, "skipped")).isZero();
  }

  @Test
  @Order(4)
  @DisplayName(
      "Packaged demo generator, MIX=bench at 100/s for 5 s after a 3 s warm-up: no unexpected"
          + " answer")
  void demoLoadBenchMix() throws IOException, InterruptedException {
    DemoLoadRun run = runDemoLoad("bench", 100, "5s");
    // Every count below is of the measured window (the warm-up is reported apart).
    assertThat(number(run.summary(), "sent")).as(run.conditions()).isEqualTo(500);
    assertThat(number(run.summary(), "unexpected")).as(run.conditions()).isZero();
    assertThat(number(run.summary(), "dropped")).as(run.conditions()).isZero();
    assertThat(run.summary()).contains("\"mix\": \"bench\"");
  }

  @Test
  @Order(5)
  @DisplayName(
      "Packaged demo generator, MIX=default (17 §3.9) at 50/s for 6 s after a 3 s warm-up: no"
          + " unexpected answer")
  void demoLoadDefaultMix() throws IOException, InterruptedException {
    DemoLoadRun run = runDemoLoad("default", 50, "6s");
    assertThat(number(run.summary(), "sent")).as(run.conditions()).isEqualTo(300);
    assertThat(number(run.summary(), "unexpected")).as(run.conditions()).isZero();
    assertThat(run.summary()).contains("\"mix\": \"default\"", "\"route\": \"send-error\"");
  }

  /** The JSON summary of a load.jar run and what explains a failure of it. */
  private record DemoLoadRun(String summary, String conditions) {}

  /**
   * java -jar load.jar against the app, after a warm-up at the same rate and mix; returns the JSON
   * summary with the machine CPU load sampled just before the run and the outcome counts.
   */
  private DemoLoadRun runDemoLoad(String mix, int rate, String duration)
      throws IOException, InterruptedException {
    Path out = scratch.resolve("demo-load-" + mix);
    Files.createDirectories(out.resolve("tmp"));
    Path summary = out.resolve("summary.json");
    String cpuBefore = machineCpuNow();
    // In-flight cap = rate x request timeout: a request is dropped only when the app is so slow
    // that it would time out anyway, never because the slow (300 ms) and pay (ext-stub) routes of
    // the bench mix pile up for a moment on a busy machine. The gate stays at 0 unexpected.
    int maxInFlight = rate * DEMO_LOAD_REQUEST_TIMEOUT_SECONDS;
    Process process =
        new ProcessBuilder(
                java.toString(),
                "-Djava.io.tmpdir=" + out.resolve("tmp"),
                "-jar",
                property("argos.load.jar").toString(),
                "--target-url",
                app.baseUrl(),
                "--mix",
                mix,
                "--rate",
                Integer.toString(rate),
                "--warmup",
                DEMO_LOAD_WARMUP,
                "--duration",
                duration,
                "--max-in-flight",
                Integer.toString(maxInFlight),
                "--request-timeout",
                DEMO_LOAD_REQUEST_TIMEOUT_SECONDS + "s",
                "--report-interval",
                "2s",
                "--summary",
                summary.toString(),
                "--max-unexpected-rate",
                "0")
            .redirectErrorStream(true)
            .redirectOutput(out.resolve("stdout.log").toFile())
            .start();
    boolean finished = process.waitFor(2, TimeUnit.MINUTES);
    if (!finished) {
      process.destroyForcibly();
    }
    String stdout = Files.readString(out.resolve("stdout.log"), StandardCharsets.UTF_8);
    String json =
        Files.isRegularFile(summary) ? Files.readString(summary, StandardCharsets.UTF_8) : "";
    String conditions =
        String.format(
            Locale.ROOT,
            "mix=%s rate=%d/s warmup=%s duration=%s maxInFlight=%d; machine CPU before the run: %s;"
                + " %s",
            mix,
            rate,
            DEMO_LOAD_WARMUP,
            duration,
            maxInFlight,
            cpuBefore,
            outcomes(json));
    System.out.println("demo-load " + conditions);
    assertThat(finished).as("load.jar finished (%s): %s", conditions, stdout).isTrue();
    assertThat(process.exitValue())
        .as("load.jar exit code (%s): %s\napp:\n%s", conditions, stdout, app.tail())
        .isZero();
    assertThat(stdout).contains("[demo-load] done:");
    assertThat(json).as("summary %s", summary).isNotEmpty();
    return new DemoLoadRun(json, conditions);
  }

  /** System CPU load over one second (min/mean/max %), sampled with the generator's own sampler. */
  private static String machineCpuNow() throws InterruptedException {
    Map<String, Object> machine;
    try (MachineSampler sampler =
        MachineSampler.start(Duration.ofMillis(200), OptionalLong.empty())) {
      Thread.sleep(1000);
      machine = sampler.result();
    }
    Map<?, ?> cpu = (Map<?, ?>) machine.get("systemCpuLoadPercent");
    return String.format(
        Locale.ROOT,
        "%s%% mean (min %s, max %s, %s samples, %s processors)",
        percent(cpu.get("mean")),
        percent(cpu.get("min")),
        percent(cpu.get("max")),
        cpu.get("samples"),
        machine.get("availableProcessors"));
  }

  private static String percent(Object value) {
    return value instanceof Double d && !d.isNaN() ? String.format(Locale.ROOT, "%.1f", d) : "n/a";
  }

  /**
   * The outcome counts of a summary: the measured window (sent, dropped, unexpected, no answer,
   * still in flight), the outcomes summed over the routes (unexpected status, TIMEOUT, connect
   * failure, I/O error), the warm-up and the machine CPU during the run.
   */
  private static String outcomes(String json) {
    if (json.isEmpty()) {
      return "no summary written";
    }
    StringBuilder text = new StringBuilder("window:");
    int routes = json.indexOf("\"routes\"");
    int warmup = json.indexOf("\"warmupExcluded\"");
    int machine = json.indexOf("\"machine\"");
    String requests = json.substring(0, warmup >= 0 ? warmup : Math.max(routes, 0));
    for (String key :
        List.of(
            "due",
            "sent",
            "dropped",
            "unexpected",
            "failedWithoutAnswer",
            "status5xx",
            "inFlightAtEnd",
            "unexpectedRate")) {
      text.append(' ').append(key).append('=').append(value(requests, key));
    }
    if (routes >= 0) {
      String section = json.substring(routes, machine > routes ? machine : json.length());
      text.append("; outcomes over the routes:");
      for (String key : List.of("unexpectedStatus", "timeouts", "connectFailures", "ioErrors")) {
        long total = 0;
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(section);
        while (m.find()) {
          total += Long.parseLong(m.group(1));
        }
        text.append(' ').append(key).append('=').append(total);
      }
    }
    if (warmup >= 0) {
      String section = json.substring(warmup, routes > warmup ? routes : json.length());
      text.append("; warm-up (excluded):");
      for (String key : List.of("sent", "dropped", "unexpectedAnswersOrFailures")) {
        text.append(' ').append(key).append('=').append(value(section, key));
      }
    }
    if (machine >= 0) {
      String section = json.substring(machine);
      text.append("; machine CPU during the run: mean=")
          .append(value(section, "mean"))
          .append("% max=")
          .append(value(section, "max"))
          .append('%');
    }
    return text.toString();
  }

  /** The first scalar value of {@code "name"} in a JSON text, "?" when absent. */
  private static String value(String json, String name) {
    Matcher m =
        Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*([^,\\s}\\]]+)").matcher(json);
    return m.find() ? m.group(1) : "?";
  }

  /** The first {@code "name": <integer>} of a JSON text. */
  private static long number(String json, String name) {
    Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(\\d+)").matcher(json);
    assertThat(m.find()).as("\"%s\" in %s", name, json).isTrue();
    return Long.parseLong(m.group(1));
  }
}
