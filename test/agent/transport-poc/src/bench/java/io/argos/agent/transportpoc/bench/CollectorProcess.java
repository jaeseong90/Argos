/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fake collector process (JDK 21) and the TLS material it serves: a self-signed RSA 2048
 * certificate for {@code localhost}/{@code 127.0.0.1} made by {@code keytool} (PKCS12 for the
 * collector, PEM for the agent's {@code server.tls.ca-file}).
 */
final class CollectorProcess implements AutoCloseable {
  static final String PASSWORD = "argos-poc-only";
  private static final Pattern EVENT =
      Pattern.compile(
          "\\{\"ms\":(\\d+),\"transport\":\"([^\"]*)\",\"instance\":(\\d+),\"seq\":(\\d+),"
              + "\"records\":(\\d+),\"bytes\":(\\d+),\"tls\":\"([^\"]*)\",\"cipher\":\"([^\"]*)\"\\}");

  /** One received batch. */
  record Event(
      long ms,
      String transport,
      int instance,
      long seq,
      int records,
      int bytes,
      String tls,
      String cipher) {}

  private final Process process;
  private final Path dir;
  final Path caFile;
  final int grpcPort;
  final int httpPort;

  private CollectorProcess(Process process, Path dir, Path caFile, int grpcPort, int httpPort) {
    this.process = process;
    this.dir = dir;
    this.caFile = caFile;
    this.grpcPort = grpcPort;
    this.httpPort = httpPort;
  }

  static CollectorProcess start(Path dir, Path jdk21, String classpath) throws Exception {
    Files.createDirectories(dir);
    Path keystore = dir.resolve("server.p12");
    Path ca = dir.resolve("ca.pem");
    Files.deleteIfExists(keystore);
    Files.deleteIfExists(ca);
    Path keytool = Jdks.tool(jdk21, "keytool");
    exec(
        dir,
        keytool.toString(),
        "-genkeypair",
        "-alias",
        "argos-poc",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "7",
        "-dname",
        "CN=localhost",
        "-ext",
        "SAN=dns:localhost,ip:127.0.0.1",
        "-storetype",
        "PKCS12",
        "-keystore",
        keystore.toString(),
        "-storepass",
        PASSWORD,
        "-keypass",
        PASSWORD);
    exec(
        dir,
        keytool.toString(),
        "-exportcert",
        "-rfc",
        "-alias",
        "argos-poc",
        "-keystore",
        keystore.toString(),
        "-storepass",
        PASSWORD,
        "-file",
        ca.toString());
    Path events = dir.resolve("events.jsonl");
    Files.deleteIfExists(events);
    Files.deleteIfExists(dir.resolve("ready.properties"));
    Files.deleteIfExists(dir.resolve("stop"));
    Process process =
        new ProcessBuilder(
                Jdks.tool(jdk21, "java").toString(),
                "-Xmx256m",
                "-cp",
                classpath,
                "io.argos.agent.transportpoc.collector.FakeCollector",
                dir.toString(),
                keystore.toString(),
                PASSWORD)
            .redirectOutput(dir.resolve("collector.out").toFile())
            .redirectError(dir.resolve("collector.err").toFile())
            .start();
    Path ready = dir.resolve("ready.properties");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (!Files.exists(ready)) {
      if (!process.isAlive() || System.nanoTime() - deadline > 0) {
        process.destroyForcibly();
        throw new IllegalStateException("fake collector did not start; see " + dir);
      }
      Thread.sleep(100);
    }
    Properties ports = new Properties();
    try (InputStream in = Files.newInputStream(ready)) {
      ports.load(in);
    }
    return new CollectorProcess(
        process,
        dir,
        ca,
        Integer.parseInt(ports.getProperty("grpc.port")),
        Integer.parseInt(ports.getProperty("http.port")));
  }

  private static void exec(Path dir, String... command) throws Exception {
    Process process =
        new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("keytool.log").toFile())
            .start();
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw new IllegalStateException("keytool failed: see " + dir.resolve("keytool.log"));
    }
  }

  /** Events of one run (BatchHeader.instance_id == {@code instance}). */
  List<Event> events(int instance) throws IOException {
    List<Event> result = new ArrayList<>();
    Path file = dir.resolve("events.jsonl");
    if (!Files.exists(file)) {
      return result;
    }
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      Matcher m = EVENT.matcher(line.trim());
      if (m.matches() && Integer.parseInt(m.group(3)) == instance) {
        result.add(
            new Event(
                Long.parseLong(m.group(1)),
                m.group(2),
                Integer.parseInt(m.group(3)),
                Long.parseLong(m.group(4)),
                Integer.parseInt(m.group(5)),
                Integer.parseInt(m.group(6)),
                m.group(7),
                m.group(8)));
      }
    }
    return result;
  }

  @Override
  public void close() throws IOException {
    Files.writeString(dir.resolve("stop"), "stop\n");
    try {
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    } finally {
      // The throwaway private key is only needed while the collector runs; removing it keeps
      // build outputs free of key material (the repository secret scan reads the work tree).
      Files.deleteIfExists(dir.resolve("server.p12"));
    }
  }
}
