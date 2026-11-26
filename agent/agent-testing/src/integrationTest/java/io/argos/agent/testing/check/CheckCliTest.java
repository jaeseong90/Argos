/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.check;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.SessionInfo;
import io.argos.proto.v1.AgentHello;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-AG-068 (F-A27, 10 §13.3): {@code java -jar argos-agent.jar --check} of the release agent jar
 * against the FakeCollector. The five verdicts of the acceptance criterion: connection (a closed
 * port is FAIL), TLS (a CA the agent does not trust is FAIL), token (a token the collector refuses
 * is FAIL), clock (2 s off is WARN with exit code 0) and JDK (8u242, when {@code
 * -Pagent.jdk8u242=<JRE home>} is given, is FAIL); plus a clean run (exit 0, one check-only Hello
 * ({@code AgentHello.check_only}, ADR-0010 C-240 (1), T-AG-068b) for the {@code -check} instance
 * answered without a session, nothing on stderr, the token never printed), the same check against a
 * collector older than {@code check_only} (the instance is registered and the session ended by
 * {@code AgentBye}), a usage error (exit 2), {@code --json} and {@code --classpath} on shop-boot3.
 * Each case prints one {@code [wo19-check]} line.
 */
@Tag("req:F-A27")
class CheckCliTest {
  @TempDir Path work;

  /** One {@code java -jar ... --check} run. */
  record Cli(int exit, String stdout, String stderr, long millis) {}

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  static Path agentJar() {
    return Path.of(required("argos.test.releaseAgentJar"));
  }

  static Path java21() {
    String java = System.getProperty("argos.test.java21");
    if (java == null || java.isBlank()) {
      List<String> jdks = List.of(System.getProperty("argos.test.jdks", "8,21").split(",", -1));
      java = required("argos.test.java" + jdks.get(jdks.size() - 1).trim());
    }
    return Path.of(java);
  }

  /** Writes the agent settings of {@code collector} plus {@code extra} as an argos-agent.yml. */
  Path config(String name, FakeCollector collector, Map<String, String> extra) throws IOException {
    Path dir = Files.createDirectories(work.resolve(name));
    Path token = dir.resolve("token");
    Files.writeString(token, collector.token(), StandardCharsets.UTF_8);
    Map<String, String> settings = new LinkedHashMap<>(collector.agentSettings());
    settings.put("server.token-file", token.toString());
    settings.put("app.name", "check-app");
    settings.put("app.env", "test");
    settings.put("log.dir", dir.resolve("logs").toString());
    settings.putAll(extra);
    StringBuilder yml = new StringBuilder();
    for (Map.Entry<String, String> e : settings.entrySet()) {
      yml.append(e.getKey()).append(": '").append(e.getValue().replace("'", "''")).append("'\n");
    }
    Path file = dir.resolve("argos-agent.yml");
    Files.writeString(file, yml.toString(), StandardCharsets.UTF_8);
    return file;
  }

  /** A TLS collector whose token has the 16 section 2.1 form (argos_ and 43 characters). */
  FakeCollector collector() {
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    String token = "argos_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    return FakeCollector.builder().tls(true).tlsDirectory(work).token(token).start();
  }

  Cli run(String name, Path java, String... args) throws Exception {
    Path dir = Files.createDirectories(work.resolve(name + "-run"));
    List<String> command = new ArrayList<>();
    command.add(java.toString());
    command.add("-Djava.io.tmpdir=" + Files.createDirectories(dir.resolve("tmp")));
    command.add("-jar");
    command.add(agentJar().toString());
    command.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
    // The check reads ARGOS_* like the agent: none of the test host's own may leak in.
    builder.environment().keySet().removeIf(k -> k.toUpperCase(Locale.ROOT).startsWith("ARGOS_"));
    Path out = dir.resolve("stdout.txt");
    Path err = dir.resolve("stderr.txt");
    builder.redirectOutput(out.toFile()).redirectError(err.toFile());
    long start = System.nanoTime();
    Process process = builder.start();
    if (!process.waitFor(120, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("--check did not end within 120 s: " + read(out));
    }
    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    Cli cli = new Cli(process.exitValue(), read(out), read(err), millis);
    System.out.println(
        "[wo19-check] case="
            + name
            + " exit="
            + cli.exit()
            + " ms="
            + millis
            + " "
            + summary(cli)
            + nonAscii(cli.stdout(), "stdout")
            + nonAscii(cli.stderr(), "stderr"));
    return cli;
  }

  /**
   * The child's output as it wrote it: {@code System.out} of the child JVM uses the platform
   * encoding ({@code native.encoding}, for example MS949 on a Korean Windows), not UTF-8.
   */
  static String read(Path file) throws IOException {
    Charset charset;
    try {
      charset = Charset.forName(System.getProperty("native.encoding", "UTF-8"));
    } catch (RuntimeException e) {
      charset = StandardCharsets.UTF_8;
    }
    return new String(Files.readAllBytes(file), charset).replace("\r\n", "\n");
  }

  /** The non-ASCII characters of {@code text} for the result line (diagnosis), or "". */
  static String nonAscii(String text, String what) {
    StringBuilder found = new StringBuilder();
    for (int i = 0; i < text.length() && found.length() < 40; i++) {
      char c = text.charAt(i);
      if (c > 126 || (c < 32 && c != '\n' && c != '\t')) {
        found.append(String.format(" U+%04X@%d", (int) c, i));
      }
    }
    return found.length() == 0 ? "" : " " + what + "-non-ascii:" + found;
  }

  static String summary(Cli cli) {
    for (String line : cli.stdout().split("\n", -1)) {
      if (line.startsWith("Result: ")) {
        return line;
      }
    }
    return "(no result line)";
  }

  static String row(Cli cli, String check) {
    for (String line : cli.stdout().split("\n", -1)) {
      if (line.matches("^\\s*\\d+\\s+" + check + "\\s+.*")) {
        return line;
      }
    }
    throw new AssertionError("no row " + check + " in\n" + cli.stdout() + cli.stderr());
  }

  static void assertNoToken(Cli cli, String token) {
    String secret = token.substring("argos_".length());
    assertThat(cli.stdout()).doesNotContain(secret);
    assertThat(cli.stderr()).doesNotContain(secret);
  }

  @Test
  @Tag("req:C-240")
  @DisplayName(
      "T-AG-068 T-AG-068b clean run: every check PASS (or INFO/SKIP), exit 0, one check-only Hello"
          + " for '-check' answered without a session (no instance, no AgentBye), nothing on"
          + " stderr, no token in the output")
  void cleanRun() throws Exception {
    try (FakeCollector collector = collector()) {
      Path config = config("ok", collector, Map.of());
      Cli cli = run("ok", java21(), "--check", "--config", config.toString());
      assertThat(cli.exit()).as(cli.stdout()).isZero();
      assertThat(cli.stderr()).isEmpty();
      assertThat(cli.stdout()).startsWith("Argos agent ");
      assertThat(row(cli, "config")).contains(" PASS ");
      assertThat(row(cli, "token")).contains(" PASS ").contains("argos_****");
      assertThat(row(cli, "dns")).contains(" PASS ");
      assertThat(row(cli, "tcp")).contains(" PASS ").contains(collector.address());
      // The FakeCollector's test certificate lives two days: WARN "expires on" is the 30-day rule.
      assertThat(row(cli, "tls"))
          .containsPattern(" (PASS|WARN) ")
          .containsPattern("TLSv1\\.[23], chain \\(server.tls.ca-file\\) and host name verified")
          .containsPattern("certificate (valid until|expires on)");
      assertThat(row(cli, "hello"))
          .contains(" PASS ")
          .contains("accepted by server " + FakeCollector.SERVER_VERSION)
          .contains("check-only (no instance registered)");
      assertThat(row(cli, "clock")).contains(" PASS ");
      assertThat(row(cli, "jdk")).doesNotContain(" FAIL ");
      assertThat(row(cli, "log-dir")).contains(" PASS ");
      assertThat(cli.stdout())
          .contains("Note: the hello check was a check-only session as instance '")
          .contains("the server registered no instance for it")
          .containsPattern("exit code 0");
      assertNoToken(cli, collector.token());

      List<SessionInfo> sessions = collector.sessions();
      assertThat(sessions).hasSize(1);
      AgentHello hello = (AgentHello) sessions.get(0).hello();
      assertThat(hello.getInstanceName()).endsWith("-check");
      assertThat(hello.getAppName()).isEqualTo("check-app");
      assertThat(hello.getEnv()).isEqualTo("test");
      assertThat(hello.hasCheckOnly()).as("T-AG-068b the check's Hello is check-only").isTrue();
      assertThat(hello.getCheckOnly()).isTrue();
      assertThat(sessions.get(0).checkOnly()).isTrue();
      assertThat(sessions.get(0).instanceId()).as("no instance id assigned").isZero();
      assertThat(sessions.get(0).sessionId()).as("no session registered").isNull();
      // The collector ended the stream after its answer: the check sends no AgentBye then.
      Thread.sleep(500);
      assertThat(collector.byes()).isEmpty();
      assertThat(collector.batches()).isEmpty();
    }
  }

  @Test
  @Tag("req:C-240")
  @DisplayName(
      "T-AG-068b older collector: a server that ignores check_only registers '-check'; the check"
          + " ends that session with AgentBye SHUTDOWN, says so, exit 0")
  void olderServer() throws Exception {
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    String token = "argos_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    try (FakeCollector collector =
        FakeCollector.builder()
            .tls(true)
            .tlsDirectory(work)
            .token(token)
            .ignoreCheckOnly(true)
            .start()) {
      Path config = config("older", collector, Map.of());
      Cli cli = run("older", java21(), "--check", "--config", config.toString());
      assertThat(cli.exit()).as(cli.stdout()).isZero();
      assertThat(cli.stderr()).isEmpty();
      assertThat(row(cli, "hello")).contains(" PASS ").contains("instance id 1");
      assertThat(cli.stdout())
          .contains("Note: the hello check opened a session as instance '")
          .contains("ended it with AgentBye")
          .contains("does not know check-only sessions");
      List<SessionInfo> sessions = collector.sessions();
      assertThat(sessions).hasSize(1);
      assertThat(((AgentHello) sessions.get(0).hello()).getCheckOnly()).isTrue();
      assertThat(sessions.get(0).instanceId()).isEqualTo(1);
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (collector.byes().isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertThat(collector.byes()).hasSize(1);
      assertThat(collector.byes().get(0).reason()).isEqualTo("SHUTDOWN");
      assertThat(collector.batches()).isEmpty();
      assertNoToken(cli, collector.token());
    }
  }

  @Test
  @DisplayName("T-AG-068 token: a token the collector refuses is hello FAIL, exit 1")
  void wrongToken() throws Exception {
    try (FakeCollector collector = collector()) {
      collector.faults().requireToken("argos_" + "Q".repeat(43));
      Path config = config("token", collector, Map.of());
      Cli cli = run("token", java21(), "--check", "--config", config.toString());
      assertThat(cli.exit()).as(cli.stdout()).isEqualTo(1);
      assertThat(row(cli, "tls")).containsPattern(" (PASS|WARN) ");
      assertThat(row(cli, "hello")).contains(" FAIL ").contains("TOKEN_INVALID");
      assertThat(cli.stderr()).isEmpty();
      assertNoToken(cli, collector.token());
    }
  }

  @Test
  @DisplayName("T-AG-068 TLS: a CA the agent does not trust is tls FAIL, exit 1")
  void untrustedCa() throws Exception {
    try (FakeCollector collector = collector();
        FakeCollector other =
            FakeCollector.builder().tls(true).tlsDirectory(work.resolve("other")).start()) {
      Path config =
          config("untrusted", collector, Map.of("server.tls.ca-file", other.trustPem().toString()));
      Cli cli = run("untrusted", java21(), "--check", "--config", config.toString());
      assertThat(cli.exit()).as(cli.stdout()).isEqualTo(1);
      assertThat(row(cli, "tcp")).contains(" PASS ");
      assertThat(row(cli, "tls")).contains(" FAIL ").contains("not trusted");
      assertThat(row(cli, "hello")).contains(" SKIP ");
      assertThat(collector.sessions()).isEmpty();
    }
  }

  @Test
  @DisplayName("T-AG-068 clock: 2 s between the collector and this host is WARN, exit 0")
  void clockSkew() throws Exception {
    try (FakeCollector collector = collector()) {
      collector.faults().clockOffset(Duration.ofSeconds(2));
      Path config = config("clock", collector, Map.of());
      Cli cli = run("clock", java21(), "--check", "--config", config.toString());
      assertThat(row(cli, "clock"))
          .contains(" WARN ")
          .containsPattern("offset \\+(1[89]|2[01])\\d\\d ms");
      assertThat(cli.exit()).as(cli.stdout()).isZero();
    }
  }

  @Test
  @DisplayName("T-AG-068 connection: nothing listening on the port is tcp FAIL, exit 1")
  void unreachable() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    try (FakeCollector collector = collector()) {
      Path config = config("unreachable", collector, Map.of("server.address", "127.0.0.1:" + port));
      Cli cli = run("unreachable", java21(), "--check", "--config", config.toString());
      assertThat(cli.exit()).as(cli.stdout()).isEqualTo(1);
      assertThat(row(cli, "tcp")).contains(" FAIL ").contains("refused");
      assertThat(row(cli, "hello")).contains(" SKIP ");
      assertThat(cli.stderr()).isEmpty();
    }
  }

  @Test
  @DisplayName("T-AG-068 usage error: exit 2 with the usage line")
  void usage() throws Exception {
    Cli cli = run("usage", java21(), "--check", "--bogus");
    assertThat(cli.exit()).isEqualTo(2);
    assertThat(cli.stdout()).contains("usage: java -jar argos-agent.jar --check");
    Cli version = run("version", java21(), "--version");
    assertThat(version.exit()).isZero();
    assertThat(version.stdout()).startsWith("Argos agent ");
  }

  @Test
  @DisplayName(
      "T-AG-068 --json and --classpath shop-boot3.jar: one JSON object, Boot/Tomcat/Hikari"
          + " detected against the compatibility table")
  void jsonAndClasspath() throws Exception {
    String app = System.getProperty("argos.test.sampleApp.shop-boot3");
    Assumptions.assumeTrue(
        app != null && !app.isBlank(), "shop-boot3 is not built for this run (-Pagent.e2e)");
    try (FakeCollector collector = collector()) {
      Path config = config("classpath", collector, Map.of());
      Cli cli =
          run("classpath", java21(), "--check", "--config", config.toString(), "--classpath", app);
      assertThat(cli.stdout()).containsPattern("classpath\\s+PASS\\s+Spring Boot 3\\.\\d+\\.\\d+ ");
      assertThat(cli.stdout()).containsPattern("classpath\\s+PASS\\s+Tomcat 10\\.1\\.\\d+ ");
      // Servlet API 6.0 from Tomcat 10.1, or 6.0.x from the jakarta.servlet-api jar that the
      // Jetty and Undertow variants of shop-boot3 bring (WO-50).
      assertThat(cli.stdout()).containsPattern("classpath\\s+PASS\\s+Servlet API 6\\.0(\\.\\d+)? ");
      assertThat(cli.stdout()).containsPattern("classpath\\s+PASS\\s+HikariCP \\d");
      assertThat(cli.exit()).as(cli.stdout()).isZero();
      Cli json = run("json", java21(), "--check", "--config", config.toString(), "--json");
      String text = json.stdout().strip();
      assertThat(text).startsWith("{\"agent\":{").endsWith("]}");
      assertThat(text)
          .contains("\"exit_code\":0")
          .contains("\"check\":\"hello\",\"status\":\"PASS\"");
      assertThat(text.lines().count()).isEqualTo(1);
      assertNoToken(json, collector.token());
    }
  }

  @Test
  @DisplayName("T-AG-068 JDK: Java 8u242 (no ALPN) is jdk FAIL and tls FAIL, exit 1")
  void oldJdk() throws Exception {
    String home = System.getProperty("argos.test.jdk8u242");
    if (home == null || home.isBlank()) {
      home = System.getenv("ARGOS_TEST_JDK8U242");
    }
    Assumptions.assumeTrue(
        home != null && !home.isBlank(), "-Pagent.jdk8u242=<JRE home> is not set");
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    Path java = Path.of(home).resolve("bin").resolve(windows ? "java.exe" : "java");
    Assumptions.assumeTrue(Files.isRegularFile(java), "no JRE at " + home);
    try (FakeCollector collector = collector()) {
      Path config = config("jdk8u242", collector, Map.of());
      Cli cli = run("jdk8u242", java, "--check", "--config", config.toString());
      assertThat(cli.exit()).as(cli.stdout()).isEqualTo(1);
      assertThat(row(cli, "jdk")).contains(" FAIL ").contains("1.8.0_242");
      assertThat(row(cli, "tls")).contains(" FAIL ").contains("ALPN");
    }
  }
}
