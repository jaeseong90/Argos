/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent settings of the measured variants (18 §7.3 {@code --exporter}, 18 §7.5 {@code --profile},
 * 18 §5.4): which exporter the agent uses and which profile policy it applies. The result is a map
 * of agent keys (without the {@code argos.} prefix) that the harness passes as system properties,
 * identical in every agent variant of a set.
 */
final class AgentSettings {
  private AgentSettings() {}

  /**
   * Class of the gRPC exporter slot ({@code Exporters.GRPC_FACTORY_CLASS}) inside the agent jar.
   */
  static final String GRPC_FACTORY_ENTRY =
      "inst/io/argos/agent/core/transport/GrpcExporterFactory.classdata";

  private static final Pattern BANNER_EXPORTER =
      Pattern.compile("Argos agent .* started in [0-9.]+ ms:.* exporter=(\\S+)");

  /**
   * The exporter of a measured agent variant.
   *
   * @param name {@code noop}, {@code drop} or {@code grpc}
   * @param agentConfig agent keys that select it
   * @param bannerExporter the {@code exporter=} value the agent's start banner must show (a
   *     mismatch makes the run invalid: the agent measured something else)
   * @param description what is measured
   */
  record Exporter(
      String name, Map<String, String> agentConfig, String bannerExporter, String description) {}

  /**
   * Resolves {@code --exporter}.
   *
   * <ul>
   *   <li>{@code noop}: 18 §5.4, {@code argos.test.exporter=noop}: transactions are encoded and
   *       dropped (the instrumentation cost). A test-only key, so a release build of the agent
   *       ignores it (T-BLD-010) and is refused.
   *   <li>{@code drop}: the WO-02 P-2 setting {@code argos.exporter=noop}: transactions are
   *       recorded and dropped without encoding. Use it to reproduce the WO-02 numbers.
   *   <li>{@code grpc}: the transport cost (P-3). Refused until the agent jar has the gRPC exporter
   *       (WO-11), and needs {@code --collector host:port}.
   * </ul>
   */
  static Exporter exporter(String name, Path agentJar, String collector) throws IOException {
    return switch (name) {
      case "noop" -> noop(agentJar);
      case "drop" ->
          new Exporter(
              "drop",
              Map.of("exporter", "noop"),
              "noop",
              "WO-02 P-2 setting: every transaction is recorded and dropped without encoding");
      case "grpc" -> grpc(agentJar, collector);
      default ->
          throw new UsageException("--exporter must be noop, drop or grpc (got '" + name + "')");
    };
  }

  private static Exporter noop(Path agentJar) throws IOException {
    String version = implementationVersion(agentJar);
    if (isReleaseVersion(version)) {
      throw new UsageException(
          "--exporter noop selects the test-only no-op exporter (argos.test.exporter=noop, 18"
              + " §5.4), which a release build ignores (T-BLD-010); "
              + agentJar.getFileName()
              + " is version "
              + version
              + ". Measure a development build, or use --exporter drop (argos.exporter=noop).");
    }
    return new Exporter(
        "noop",
        Map.of("test.exporter", "noop"),
        "noop",
        "no-op exporter of 18 §5.4: every transaction is encoded and dropped (instrumentation"
            + " and encoding cost, no transport)");
  }

  private static Exporter grpc(Path agentJar, String collector) throws IOException {
    if (!hasEntry(agentJar, GRPC_FACTORY_ENTRY)) {
      throw new UsageException(
          "--exporter grpc: "
              + agentJar.getFileName()
              + " has no gRPC transport ("
              + GRPC_FACTORY_ENTRY
              + " is missing). WO-11 delivers the transport and its exporter; until then only"
              + " --exporter noop (or drop) can be measured.");
    }
    if (collector == null || collector.isBlank()) {
      throw new UsageException(
          "--exporter grpc needs --collector host:port (a collector server or FakeCollector"
              + " that accepts the agent's batches during the whole set)");
    }
    Map<String, String> config = new LinkedHashMap<>();
    config.put("exporter", "grpc");
    config.put("server.address", collector);
    return new Exporter(
        "grpc", config, "grpc", "gRPC exporter to " + collector + " (transport cost)");
  }

  /**
   * Resolves {@code --profile} (01 §7.3 item 3, 18 §7.5): {@code default} keeps the agent's profile
   * policy (normal requests sampled at {@code sampling.profile.normal-rate}, 0.1 by default, 10
   * §15); {@code worst} profiles every normal request (rate 1.0).
   */
  static Map<String, String> profile(String profile) {
    return switch (profile) {
      case "default" -> Map.of();
      case "worst" -> Map.of("sampling.profile.normal-rate", "1.0");
      default ->
          throw new UsageException("--profile must be default or worst (got '" + profile + "')");
    };
  }

  /** Exporter, then profile, then the explicit {@code --agent-config} (which wins). */
  static Map<String, String> merge(
      Exporter exporter, Map<String, String> profile, Map<String, String> explicit) {
    Map<String, String> config = new LinkedHashMap<>(exporter.agentConfig());
    config.putAll(profile);
    config.putAll(explicit);
    return config;
  }

  /** The {@code exporter=} value of the agent's start banner, or null. */
  static String bannerExporter(String agentLog) {
    Matcher m = BANNER_EXPORTER.matcher(agentLog);
    return m.find() ? m.group(1) : null;
  }

  static boolean hasEntry(Path jar, String entry) throws IOException {
    if (!Files.isRegularFile(jar)) {
      throw new UsageException("missing agent jar " + jar + " (build it first, see README.md)");
    }
    try (JarFile file = new JarFile(jar.toFile())) {
      return file.getEntry(entry) != null;
    }
  }

  /** {@code Implementation-Version} of the jar's manifest, or "unknown". */
  static String implementationVersion(Path jar) throws IOException {
    if (!Files.isRegularFile(jar)) {
      throw new UsageException("missing agent jar " + jar + " (build it first, see README.md)");
    }
    try (JarFile file = new JarFile(jar.toFile())) {
      Manifest manifest = file.getManifest();
      String version =
          manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
      return version == null || version.isBlank() ? "unknown" : version.trim();
    }
  }

  /**
   * Same rule as the agent's {@code AgentInfo.isReleaseVersion} (T-BLD-010, fails closed): only a
   * version ending with {@code -SNAPSHOT} is a development build; a missing ("unknown") or blank
   * version counts as a release build, which ignores {@code argos.test.*}.
   */
  static boolean isReleaseVersion(String version) {
    return version == null || !version.trim().endsWith("-SNAPSHOT");
  }
}
