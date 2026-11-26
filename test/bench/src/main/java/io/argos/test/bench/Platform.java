/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where the sample app, ext-stub and k6 run: {@link ProcessPlatform} (java processes on this
 * machine, 01 §7.3 items 1-8) or {@link ContainerPlatform} (Linux containers with a CPU and memory
 * limit, item 9).
 */
interface Platform extends AutoCloseable {

  /**
   * Fixed inputs of a set: files, JVM options and agent settings (identical for off and on). {@code
   * agentConfig} holds the keys of {@link AgentSettings} (exporter, profile) and {@code
   * --agent-config}. {@code selfDiag}: agent variants write the self-diagnostics snapshot ({@link
   * SelfDiagFile}, development builds only, C-95). {@code otlpEndpoint}: the {@link OtlpBlackHole}
   * the otel variant exports to, null for exporters {@code none}.
   */
  record Setup(
      Path repo,
      Path setDir,
      Path binDir,
      Path java,
      String image,
      String sidecarImage,
      String appName,
      Path appJar,
      Path extJar,
      Path agentJar,
      Path otelJar,
      List<String> jvmOptions,
      Map<String, String> appEnv,
      Map<String, String> agentConfig,
      Map<String, Map<String, String>> variantConfigs,
      String k6,
      String k6Ssh,
      String k6Image,
      Path benchJs,
      int cpus,
      String memory,
      String appAddress,
      boolean selfDiag,
      String otlpEndpoint) {

    /** A setup without self-diagnostics file and without OTLP receiver (the WO-05 settings). */
    Setup(
        Path repo,
        Path setDir,
        Path binDir,
        Path java,
        String image,
        String sidecarImage,
        String appName,
        Path appJar,
        Path extJar,
        Path agentJar,
        Path otelJar,
        List<String> jvmOptions,
        Map<String, String> appEnv,
        Map<String, String> agentConfig,
        Map<String, Map<String, String>> variantConfigs,
        String k6,
        String k6Ssh,
        String k6Image,
        Path benchJs,
        int cpus,
        String memory,
        String appAddress) {
      this(
          repo,
          setDir,
          binDir,
          java,
          image,
          sidecarImage,
          appName,
          appJar,
          extJar,
          agentJar,
          otelJar,
          jvmOptions,
          appEnv,
          agentConfig,
          variantConfigs,
          k6,
          k6Ssh,
          k6Image,
          benchJs,
          cpus,
          memory,
          appAddress,
          false,
          null);
    }
  }

  /** A started app. */
  interface App {
    /** Process creation to the open port (C-49), -1 when not measurable (containers). */
    long spawnToPortMillis();

    /** Process creation to the first 200 of /actuator/health. */
    long spawnToReadyMillis();

    /** Cumulative CPU time of the app (process times or cgroup usage), -1 when unknown. */
    long cpuNanos();

    /** Where {@link #cpuNanos()} comes from (18 §7.4), for the report. */
    String cpuSource();

    /** Threads of the app process from {@code /proc/<pid>/status}, -1 when unavailable. */
    long osThreads();

    /** {@code jcmd <app> args...}. */
    Cmd.Result jcmd(String... args) throws InterruptedException;

    /** The agent log ({@code argos-agent.log}) or "" (off run, not written yet). */
    String agentLog();

    /** Stops the app and keeps the head and tail of its log in the run directory. */
    void stop(Path runDir, boolean keepFullLog) throws InterruptedException;
  }

  /**
   * One k6 invocation. {@code appTimeline} and {@code loaderTimeline} are filled by platforms that
   * sample through a side channel (containers: rows of [seconds, app CPU seconds] and [seconds,
   * machine CPU %]); the process platform leaves them empty (the caller samples the process).
   */
  record K6Run(
      int exitCode,
      double wallSeconds,
      double k6CpuSeconds,
      List<List<Double>> appTimeline,
      List<List<Double>> loaderTimeline) {}

  String mode();

  /** Starts ext-stub (and a network for containers). */
  void startDependencies() throws Exception;

  /** Starts the app for {@code variant} and waits until it is ready. */
  App startApp(String variant, Path runDir) throws Exception;

  /**
   * Runs sample-apps/load/bench.js against {@code app}; the summary is {@code <label>.json}. With
   * {@code raw}, k6 also writes every sample to {@code runDir/}{@value K6Raw#FILE_NAME} ({@code
   * --out json}, gzip; 18 §7.2).
   */
  K6Run runK6(
      App app, Path runDir, String label, Map<String, String> env, Duration timeout, boolean raw)
      throws Exception;

  /** Facts about the machine, JDK and tools (01 §7.3 item 1). */
  Map<String, Object> environment() throws InterruptedException;

  @Override
  void close();

  /**
   * Agent keys of every measured agent variant: {@code log.dir} and the required keys {@code
   * app.name} (the sample app) and {@code app.env=bench}, so that the agent log has no missing-key
   * ERROR or WARN (10 §4.3 P4); {@code --agent-config} can override them.
   */
  static Map<String, String> requiredAgentKeys(String appName, String logDir) {
    Map<String, String> config = new LinkedHashMap<>();
    config.put("log.dir", logDir);
    if (appName != null && !appName.isBlank()) {
      config.put("app.name", appName);
    }
    config.put("app.env", "bench");
    return config;
  }

  /**
   * JVM options of a variant of the set's app ({@link Setup#appName()}). {@code agentJar}, {@code
   * otelJar} and {@code logDir} are paths as the app sees them.
   */
  static List<String> variantOptions(
      String variant, Setup setup, String agentJar, String otelJar, String logDir) {
    return variantOptions(variant, setup, setup.appName(), agentJar, otelJar, logDir);
  }

  /** JVM options of a variant of {@code appName} (the startup set starts several apps). */
  static List<String> variantOptions(
      String variant, Setup setup, String appName, String agentJar, String otelJar, String logDir) {
    return variantOptions(variant, setup, appName, agentJar, otelJar, logDir, null);
  }

  /**
   * JVM options of a variant of {@code appName}; {@code selfDiagFile} (the snapshot path as the app
   * sees it) is added to Argos agent variants when {@link Setup#selfDiag()} is set (C-95).
   */
  static List<String> variantOptions(
      String variant,
      Setup setup,
      String appName,
      String agentJar,
      String otelJar,
      String logDir,
      String selfDiagFile) {
    List<String> options = new ArrayList<>();
    switch (variant) {
      case "off" -> {}
      case "on" -> {
        options.add("-javaagent:" + agentJar);
        Map<String, String> config = new LinkedHashMap<>();
        config.putAll(requiredAgentKeys(appName, logDir));
        config.putAll(setup.agentConfig());
        config.forEach((k, v) -> options.add("-Dargos." + k + "=" + v));
        if (setup.selfDiag() && selfDiagFile != null) {
          options.add(SelfDiagFile.jvmOption(selfDiagFile));
        }
      }
      case "otel" -> {
        if (otelJar == null) {
          throw new UsageException("variant otel needs --otel-agent");
        }
        // Reference point only (01 §7.3 item 11): the OpenTelemetry Java agent exporting OTLP to
        // the harness's black-hole receiver (18 §7.5), or without exporters when there is none.
        options.add("-javaagent:" + otelJar);
        options.addAll(OtlpBlackHole.otelOptions(setup.otlpEndpoint()));
      }
      default -> {
        // Diagnostic agent variants (--variant-defs): the on settings plus their own.
        Map<String, String> extra = setup.variantConfigs().get(variant);
        if (extra == null) {
          throw new UsageException(
              "unknown variant " + variant + " (off, on, otel or one of --variant-defs)");
        }
        options.add("-javaagent:" + agentJar);
        Map<String, String> config = new LinkedHashMap<>();
        config.putAll(requiredAgentKeys(appName, logDir));
        config.putAll(setup.agentConfig());
        config.putAll(extra);
        config.forEach((k, v) -> options.add("-Dargos." + k + "=" + v));
        if (setup.selfDiag() && selfDiagFile != null) {
          options.add(SelfDiagFile.jvmOption(selfDiagFile));
        }
      }
    }
    return options;
  }

  /**
   * Parses {@code name:key=value;key=value|name2:...} (values may contain commas) into agent
   * settings per diagnostic variant.
   */
  static Map<String, Map<String, String>> parseVariantDefs(String text) {
    Map<String, Map<String, String>> defs = new LinkedHashMap<>();
    for (String def : text.split("\\|", -1)) {
      String d = def.trim();
      if (d.isEmpty()) {
        continue;
      }
      int colon = d.indexOf(':');
      if (colon <= 0) {
        throw new UsageException("--variant-defs: expected name:key=value;... in '" + d + "'");
      }
      String name = d.substring(0, colon).trim();
      if (List.of("off", "on", "otel").contains(name)) {
        throw new UsageException("--variant-defs: '" + name + "' is a built-in variant");
      }
      Map<String, String> config = new LinkedHashMap<>();
      for (String pair : d.substring(colon + 1).split(";", -1)) {
        String p = pair.trim();
        if (p.isEmpty()) {
          continue;
        }
        int eq = p.indexOf('=');
        if (eq <= 0) {
          throw new UsageException("--variant-defs: expected key=value in '" + p + "'");
        }
        config.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
      }
      defs.put(name, config);
    }
    return defs;
  }

  static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
  }

  static boolean isLinux() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
  }
}
