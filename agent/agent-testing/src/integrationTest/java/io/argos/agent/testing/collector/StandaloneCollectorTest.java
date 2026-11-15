/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.ScriptedAgent.Session;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ServerHello;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The standalone collector jar ({@code :agent:agent-testing:fakeCollectorJar}, {@code
 * build/fake-collector/fake-collector.jar}) as its own process, the way the bench starts it: TLS,
 * token file, ready file, periodic report and JSON summary.
 */
@Tag("req:DSN-18-04")
class StandaloneCollectorTest {
  @TempDir Path dir;

  @Test
  @DisplayName(
      "fake-collector.jar: listens with TLS, writes the ready file and an owner-only token file,"
          + " accepts only that token, prints totals per kind without the token, and writes the"
          + " summary at the end of --duration")
  void standaloneJar() throws Exception {
    Path jar = Path.of(System.getProperty("argos.test.fakeCollectorJar"));
    assertThat(jar).isRegularFile();
    assertThat(jar.resolveSibling("lib")).isDirectory();
    Path ready = dir.resolve("ready");
    Path token = dir.resolve("token");
    Path summary = dir.resolve("summary.json");
    Path stdout = dir.resolve("stdout.log");
    String java =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                    ? "java.exe"
                    : "java")
            .toString();
    List<String> command =
        new ArrayList<>(
            List.of(
                java,
                "-Djava.io.tmpdir=" + dir,
                "-jar",
                jar.toString(),
                "--tls",
                "--tls-dir",
                dir.resolve("tls").toString(),
                "--token-file",
                token.toString(),
                "--ready-file",
                ready.toString(),
                "--summary",
                summary.toString(),
                "--report-every",
                "1",
                "--duration",
                "12"));
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(stdout.toFile())
            .start();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (!Files.exists(ready) && System.nanoTime() < deadline && process.isAlive()) {
        Thread.sleep(50);
      }
      assertThat(ready).as("ready file; output:%n%s", read(stdout)).exists();
      List<String> lines = Files.readAllLines(ready, StandardCharsets.UTF_8);
      String address = lines.get(0);
      Path pem = Path.of(lines.get(1));
      int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
      String secret = Files.readString(token, StandardCharsets.UTF_8).strip();
      assertThat(secret).startsWith("argos_");
      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(token)))
            .isEqualTo("rw-------");
      }

      ManagedChannel channel =
          NettyChannelBuilder.forAddress("127.0.0.1", port)
              .sslContext(GrpcSslContexts.forClient().trustManager(pem.toFile()).build())
              .build();
      try {
        Session session = new Session(channel, secret);
        ServerHello hello = session.accepted("test");
        for (long seq = 1; seq <= 3; seq++) {
          assertThat(
                  ScriptedAgent.send(
                          channel,
                          ScriptedAgent.headers(secret, hello),
                          BatchKind.TRANSACTIONS,
                          ScriptedAgent.batch(
                              BatchKind.TRANSACTIONS, hello.getInstanceId(), 3, seq, 2))
                      .getStatus())
              .isEqualTo(AckStatus.ACK_OK);
        }
        assertThat(ScriptedAgent.send(channel, secret, hello, 3, 1).getStatus())
            .isEqualTo(AckStatus.ACK_DUPLICATE);
        try {
          ScriptedAgent.send(channel, "argos_bm90LXRoaXMtb25l", hello, 3, 4);
          throw new AssertionError("another token was accepted");
        } catch (StatusRuntimeException e) {
          assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        }
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
      assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("exits after --duration").isTrue();
      assertThat(process.exitValue()).isZero();
    } finally {
      process.destroyForcibly();
    }
    String output = read(stdout);
    assertThat(output)
        .contains("fake-collector listening on 127.0.0.1:")
        .contains("tls=true")
        .contains("| transactions batches=5 items=8 ")
        .contains(" final")
        .doesNotContain("SLF4J");
    ScriptedAgent.assertNoToken(
        "the collector's output", output, Files.readString(token, StandardCharsets.UTF_8).strip());
    Map<String, Object> json =
        ContractVectors.map(
            ContractVectors.parse(Files.readString(summary, StandardCharsets.UTF_8)));
    assertThat(ContractVectors.number(json, "sessions")).isEqualTo(1);
    Map<String, Object> transactions =
        ContractVectors.map(ContractVectors.map(json.get("kinds")).get("TRANSACTIONS"));
    assertThat(transactions)
        .containsEntry("batches", 5L)
        .containsEntry("items", 8L)
        .containsEntry("acked", 3L)
        .containsEntry("acked_items", 6L)
        .containsEntry("duplicates", 1L)
        .containsEntry("failed", 1L);
    assertThat(dir.resolve("tls")).isEmptyDirectory();
  }

  @Test
  @DisplayName(
      "--report-every 0 prints only the final totals and sleeps meanwhile: a collector that spins"
          + " would distort the overhead bench it serves")
  void quietCollectorSleeps() throws Exception {
    FakeCollectorMain.Options options =
        FakeCollectorMain.parse(new String[] {"--report-every", "0", "--duration", "5"});
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    long before = threads.getCurrentThreadCpuTime();
    try (PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      FakeCollectorMain.run(options, out);
    }
    long cpuMillis = TimeUnit.NANOSECONDS.toMillis(threads.getCurrentThreadCpuTime() - before);
    assertThat(cpuMillis).as("CPU time of a 5 s run, start included").isLessThan(2_000);
    String output = captured.toString(StandardCharsets.UTF_8);
    assertThat(output.lines().filter(line -> line.startsWith("fake-collector t="))).hasSize(1);
    assertThat(output).contains(" final");
  }

  private static String read(Path file) throws java.io.IOException {
    return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }
}
