/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.smoke;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One started app process without an agent: a Spring Boot jar ({@code java -jar}) or a standalone
 * Tomcat with the WAR deployed under each context path. Measures startup the way 18 §7.4 defines it
 * (process creation to port binding) and until every context answers its health check.
 */
final class AppProcess implements AutoCloseable {

  /** Spring Boot: "Started X in 2.345 seconds (process running for 2.9)" (2.x: "JVM running"). */
  private static final Pattern BOOT_STARTED =
      Pattern.compile(
          "Started \\S+ in ([0-9.]+) seconds \\((?:process|JVM) running for ([0-9.]+)\\)");

  /** Tomcat: "Server startup in [1234] milliseconds". */
  private static final Pattern TOMCAT_STARTED =
      Pattern.compile("Server startup in \\[?([0-9,]+)\\]? ?(?:ms|milliseconds)");

  private final Process process;
  private final int port;
  private final Path log;
  private final Map<String, Object> startup = new LinkedHashMap<>();

  private AppProcess(Process process, int port, Path log) {
    this.process = process;
    this.port = port;
    this.log = log;
  }

  int port() {
    return port;
  }

  Path log() {
    return log;
  }

  Map<String, Object> startup() {
    return startup;
  }

  boolean isAlive() {
    return process.isAlive();
  }

  /** Starts the app and waits until the port is bound and every context is healthy. */
  static AppProcess start(SmokeOptions options, Map<String, String> env, Path runDir, String label)
      throws IOException, InterruptedException {
    Files.createDirectories(runDir);
    Path tmp = Files.createDirectories(runDir.resolve("tmp"));
    int port = freePort();
    List<String> command = new ArrayList<>();
    command.add(options.appJava);
    command.addAll(options.jvmArgs);
    command.add("-Djava.io.tmpdir=" + tmp.toAbsolutePath());
    Map<String, String> processEnv = new LinkedHashMap<>(env);
    switch (options.kind) {
      case "boot-jar", "jar" -> {
        processEnv.put("SERVER_PORT", Integer.toString(port));
        // Loopback only (server.address through relaxed binding), like the Tomcat connector of the
        // WAR apps (TomcatBase): the app, with seed values in its environment, stays off the LAN.
        processEnv.put("SERVER_ADDRESS", "127.0.0.1");
        command.add("-jar");
        command.add(options.artifact.toAbsolutePath().toString());
      }
      case "war" -> command.addAll(TomcatBase.command(options, runDir, port));
      default -> throw new IllegalArgumentException("Cannot start kind " + options.kind);
    }
    Path log = runDir.resolve(label + ".log");
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(runDir.toFile());
    builder.environment().putAll(processEnv);
    builder.redirectErrorStream(true);
    builder.redirectOutput(log.toFile());
    long spawn = System.nanoTime();
    Process process = builder.start();
    AppProcess app = new AppProcess(process, port, log);
    try {
      app.awaitReady(options, spawn);
    } catch (IOException | InterruptedException | RuntimeException e) {
      app.close();
      throw e;
    }
    return app;
  }

  private void awaitReady(SmokeOptions options, long spawn)
      throws IOException, InterruptedException {
    long deadline = spawn + TimeUnit.SECONDS.toNanos(options.readyTimeoutSeconds);
    Instant processStart = process.info().startInstant().orElse(null);
    while (!portOpen()) {
      checkAlive(deadline);
      Thread.sleep(10);
    }
    long portNanos = System.nanoTime();
    Instant portBound = Instant.now();
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    for (String context : options.contexts) {
      URI health = URI.create(baseUrl(context) + healthPath(options));
      while (!healthy(client, health)) {
        checkAlive(deadline);
        Thread.sleep(50);
      }
    }
    long readyNanos = System.nanoTime();
    startup.put("spawnToPortMs", TimeUnit.NANOSECONDS.toMillis(portNanos - spawn));
    startup.put("spawnToReadyMs", TimeUnit.NANOSECONDS.toMillis(readyNanos - spawn));
    if (processStart != null) {
      startup.put("processStartToPortMs", Duration.between(processStart, portBound).toMillis());
    }
    Thread.sleep(200); // let the "Started ..." line reach the log file
    String text = readLog();
    Matcher boot = BOOT_STARTED.matcher(text);
    if (boot.find()) {
      startup.put("springStartedSeconds", Double.parseDouble(boot.group(1)));
      startup.put("springProcessRunningSeconds", Double.parseDouble(boot.group(2)));
    }
    Matcher tomcat = TOMCAT_STARTED.matcher(text);
    if (tomcat.find()) {
      startup.put("tomcatStartupMs", Long.parseLong(tomcat.group(1).replace(",", "")));
    }
  }

  private static String healthPath(SmokeOptions options) {
    return options.catalog.equals("none") ? "/" : "/actuator/health";
  }

  String baseUrl(String context) {
    String path = context.isEmpty() || context.equals("/") ? "" : "/" + stripSlashes(context);
    return "http://127.0.0.1:" + port + path;
  }

  private static String stripSlashes(String context) {
    String c = context;
    while (c.startsWith("/")) {
      c = c.substring(1);
    }
    while (c.endsWith("/")) {
      c = c.substring(0, c.length() - 1);
    }
    return c;
  }

  private static boolean healthy(HttpClient client, URI uri) throws InterruptedException {
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200;
    } catch (IOException e) {
      return false;
    }
  }

  private boolean portOpen() {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 200);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private void checkAlive(long deadline) throws IOException {
    if (!process.isAlive()) {
      throw new IllegalStateException(
          "The app exited with code " + process.exitValue() + " before it was ready:\n" + tail());
    }
    if (System.nanoTime() > deadline) {
      throw new IllegalStateException("The app was not ready in time:\n" + tail());
    }
  }

  String readLog() throws IOException {
    return Files.exists(log) ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8) : "";
  }

  String tail() throws IOException {
    String text = readLog();
    return text.length() > 6000 ? text.substring(text.length() - 6000) : text;
  }

  /** Stops the app: destroy, then destroy forcibly after 15 s (Windows: both terminate). */
  @Override
  public void close() {
    if (!process.isAlive()) {
      return;
    }
    process.destroy();
    try {
      if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
          System.err.println("smoke: app process " + process.pid() + " did not stop");
        }
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }

  static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  /** Deletes a directory tree (app temp files); failures are ignored. */
  static void deleteTree(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths
          .sorted((a, b) -> b.getNameCount() - a.getNameCount())
          .map(Path::toFile)
          .forEach(File::delete);
    } catch (IOException e) {
      // Leftovers stay below build/, which `clean` removes.
    }
  }
}
