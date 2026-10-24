/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.extstub;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The packaged entry point: {@code java -jar ext-stub.jar} (ExtStubMain, jar manifest) started as a
 * process, the way run-bench.sh and the container image start it. Options, environment variables
 * and the failure on an unknown option.
 */
class ExtStubJarTest {

  private static final Duration START_TIMEOUT = Duration.ofSeconds(60);
  private static final String LISTENING = "ext-stub listening on ";

  private static Path jar;
  private static String java;
  private static Path scratch;
  private static HttpClient client;

  @BeforeAll
  static void paths() throws IOException {
    jar = Path.of(required("argos.extStub.jar"));
    java = required("argos.extStub.java");
    scratch = Files.createDirectories(Path.of(required("argos.extStub.scratch")));
    assertThat(jar).isRegularFile();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Test
  @DisplayName("--port <free port> --bind 127.0.0.1: health 200, /status/500 answers 500")
  void options() throws Exception {
    int port = freePort();
    Process process =
        start(
            "options", List.of("--port", Integer.toString(port), "--bind", "127.0.0.1"), Map.of());
    try {
      String line = awaitListening(process, "options");
      assertThat(line).contains("http://127.0.0.1:" + port);
      assertThat(status(port, "/_stub/health")).isEqualTo(200);
      assertThat(status(port, "/status/500")).isEqualTo(500);
    } finally {
      stop(process);
    }
  }

  @Test
  @DisplayName("EXT_STUB_PORT without options: listens on that port of 127.0.0.1 (default bind)")
  void environment() throws Exception {
    int port = freePort();
    Process process =
        start("environment", List.of(), Map.of("EXT_STUB_PORT", Integer.toString(port)));
    try {
      String line = awaitListening(process, "environment");
      assertThat(line).contains("http://127.0.0.1:" + port);
      assertThat(status(port, "/_stub/health")).isEqualTo(200);
    } finally {
      stop(process);
    }
  }

  @Test
  @DisplayName("An unknown option stops the process with a non-zero exit code and the option name")
  void unknownOption() throws Exception {
    Process process = start("unknown", List.of("--bogus", "1"), Map.of());
    try {
      assertThat(process.waitFor(START_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
          .as("process exits by itself")
          .isTrue();
      assertThat(process.exitValue()).isNotZero();
      assertThat(log("unknown")).contains("Unknown option --bogus").doesNotContain(LISTENING);
    } finally {
      stop(process);
    }
  }

  private static Process start(String name, List<String> args, Map<String, String> env)
      throws IOException {
    Path tmp = Files.createDirectories(scratch.resolve(name + "-tmp"));
    List<String> command = new ArrayList<>();
    command.add(java);
    command.add("-Djava.io.tmpdir=" + tmp.toAbsolutePath());
    command.add("-jar");
    command.add(jar.toAbsolutePath().toString());
    command.addAll(args);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(scratch.toFile());
    builder.environment().remove("EXT_STUB_PORT");
    builder.environment().remove("EXT_STUB_BIND");
    builder.environment().putAll(env);
    builder.redirectErrorStream(true);
    builder.redirectOutput(logFile(name).toFile());
    return builder.start();
  }

  /** Waits for the "ext-stub listening on" line in the process output and returns it. */
  private static String awaitListening(Process process, String name)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      String line = log(name).lines().filter(l -> l.startsWith(LISTENING)).findFirst().orElse(null);
      if (line != null) {
        return line;
      }
      if (!process.isAlive()) {
        throw new AssertionError(
            "ext-stub exited with " + process.exitValue() + " before listening:\n" + log(name));
      }
      Thread.sleep(50);
    }
    throw new AssertionError("ext-stub did not report listening in time:\n" + log(name));
  }

  private static int status(int port, String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  /** Always ends the process: destroy, then destroy forcibly. */
  private static void stop(Process process) throws InterruptedException {
    if (process.isAlive()) {
      process.destroy();
      if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    }
    assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("ext-stub process stopped").isTrue();
  }

  private static Path logFile(String name) {
    return scratch.resolve(name + ".log");
  }

  private static String log(String name) throws IOException {
    Path file = logFile(name);
    return Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static String required(String property) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          property + " is not set (run through ./gradlew :sample-apps:ext-stub:integrationTest)");
    }
    return value;
  }
}
