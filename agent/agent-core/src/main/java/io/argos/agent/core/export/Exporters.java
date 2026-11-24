/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.AppVersion;
import io.argos.agent.core.log.AgentLogger;
import java.io.File;
import java.util.Locale;

/**
 * Exporter registry: the test-only {@code test.exporter} of 18 §5.4 ({@code noop}, {@code file})
 * and the {@code grpc} slot, the only exporter of a configuration without test keys. The WO-02 PoC
 * keys {@code exporter} and {@code exporter.file.*} are gone (ADR-0009 C-88): {@code
 * argos.test.exporter} is ignored in release builds (T-BLD-010), so a release agent never writes
 * collected data to a local file. The gRPC transport is found by class name in the agent class
 * loader, on {@code argos-sender}: no transport class loads on the premain thread (C-79), and the
 * test exporters load none at all. A jar without it (a stripped test jar) logs one WARN and drops
 * transactions like {@code noop}. An unknown name also logs one WARN and falls back to {@code
 * noop}.
 */
public final class Exporters {
  public static final String NOOP = "noop";

  /**
   * {@code test.exporter=drop}: records and drops without encoding, the WO-02 P-2 overhead baseline
   * that the PoC key {@code exporter=noop} selected before ADR-0009 C-88 (benchmarks, C-80).
   */
  public static final String DROP = "drop";

  public static final String FILE = "file";
  public static final String GRPC = "grpc";

  /** Implementation of the {@code grpc} slot (P-3 / WO-11). */
  public static final String GRPC_FACTORY_CLASS =
      "io.argos.agent.core.transport.GrpcExporterFactory";

  static final String DEFAULT_FILE_NAME = "argos-transactions.jsonl";

  /** Size cap of the JSONL test file (the former {@code exporter.file.max-mb} default). */
  static final int FILE_MAX_MB = 256;

  private Exporters() {}

  /**
   * The configured exporter name, lower case: {@code test.exporter} (18 §5.4, development builds
   * only; the part after its first {@code :} is the argument, {@code file:<dir>}), else {@code
   * grpc}.
   */
  public static String configuredName(AgentConfig config) {
    String test = config.testExporter();
    if (test != null && !test.isEmpty()) {
      int colon = test.indexOf(':');
      return (colon < 0 ? test : test.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
    }
    return GRPC;
  }

  /** The argument of {@code test.exporter=<name>:<argument>}, or null. */
  static String testArgument(AgentConfig config) {
    String test = config.testExporter();
    if (test == null || test.indexOf(':') < 0) {
      return null;
    }
    String argument = test.substring(test.indexOf(':') + 1).trim();
    return argument.isEmpty() ? null : argument;
  }

  /**
   * Creates the configured exporter; never throws (falls back to {@code noop}).
   *
   * <ul>
   *   <li>{@code test.exporter=file:<dir>}: JSONL in {@code <dir>/}{@value #DEFAULT_FILE_NAME}
   *       ({@code file} alone: the log directory), at most {@value #FILE_MAX_MB} MB;
   *   <li>{@code test.exporter=noop}: encode, then drop; {@code test.exporter=drop}: drop without
   *       encoding;
   *   <li>{@code grpc} (default): the transport, when this build has one.
   * </ul>
   */
  public static Exporter create(ExporterContext context, File logDirectory) {
    String name = configuredName(context.config());
    try {
      if (name.equals(NOOP)) {
        return new NoopExporter(context.dictionary());
      }
      if (name.equals(DROP)) {
        return new NoopExporter();
      }
      if (name.equals(FILE)) {
        String directory = testArgument(context.config());
        File file =
            new File(directory != null ? new File(directory) : logDirectory, DEFAULT_FILE_NAME);
        return new JsonlFileExporter(
            file,
            FILE_MAX_MB * 1024L * 1024L,
            context.dictionary(),
            context.config().appEnv(),
            // app.version, else the main jar's Implementation-Version (T-AG-066); left out when
            // there is none. Created on argos-sender, so the manifest read is off premain.
            AppVersion.of(context.config()).valueOrNull());
      }
      if (name.equals(GRPC)) {
        return createByClassName(GRPC_FACTORY_CLASS, context);
      }
      AgentLogger.warn("unknown exporter '{}' (noop, drop, file, grpc); using noop", name);
    } catch (ClassNotFoundException e) {
      AgentLogger.warn(
          "exporter '{}' is not available in this agent build ({} missing); transactions are"
              + " recorded and dropped (noop)",
          name,
          GRPC_FACTORY_CLASS);
    } catch (Throwable t) {
      AgentLogger.error("exporter '{}' could not be created; using noop", name, t);
    }
    return new NoopExporter();
  }

  static Exporter createByClassName(String className, ExporterContext context) throws Exception {
    Class<?> type = Class.forName(className, true, Exporters.class.getClassLoader());
    ExporterFactory factory =
        (ExporterFactory) type.asSubclass(ExporterFactory.class).getConstructor().newInstance();
    return factory.create(context);
  }
}
