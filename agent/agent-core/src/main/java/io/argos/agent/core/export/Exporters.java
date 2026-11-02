/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigKeys;
import io.argos.agent.core.log.AgentLogger;
import java.io.File;
import java.util.Locale;

/**
 * Exporter registry ({@code exporter} setting, and the test-only {@code test.exporter} of 18 §5.4):
 * {@code noop}, {@code file} and the {@code grpc} slot. The gRPC transport is found by class name
 * in the agent class loader, so the core does not depend on it at compile time; when it is missing
 * (builds without a transport, until WO-11) the agent logs one WARN and drops transactions like
 * {@code noop}. An unknown name also logs one WARN and falls back to {@code noop}.
 */
public final class Exporters {
  public static final String NOOP = "noop";
  public static final String FILE = "file";
  public static final String GRPC = "grpc";

  /** Implementation of the {@code grpc} slot (P-3 / WO-11). */
  public static final String GRPC_FACTORY_CLASS =
      "io.argos.agent.core.transport.GrpcExporterFactory";

  static final String DEFAULT_FILE_NAME = "argos-transactions.jsonl";

  private Exporters() {}

  /**
   * The configured exporter name, lower case ({@code grpc} by default): {@code test.exporter} (18
   * §5.4, development builds only) wins over {@code exporter}; the part after its first {@code :}
   * is the argument ({@code file:<dir>}).
   */
  public static String configuredName(AgentConfig config) {
    String test = config.get(ConfigKeys.TEST_EXPORTER, null);
    if (test != null) {
      int colon = test.indexOf(':');
      return (colon < 0 ? test : test.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
    }
    return config.get(ConfigKeys.EXPORTER, GRPC).toLowerCase(Locale.ROOT);
  }

  /** The argument of {@code test.exporter=<name>:<argument>}, or null. */
  static String testArgument(AgentConfig config) {
    String test = config.get(ConfigKeys.TEST_EXPORTER, null);
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
   *   <li>{@code test.exporter=file:<dir>}: JSONL in {@code <dir>/}{@value #DEFAULT_FILE_NAME};
   *       {@code exporter=file}: JSONL in {@code exporter.file.path} (default: the log directory);
   *   <li>{@code test.exporter=noop}: encode, then drop; {@code exporter=noop}: drop;
   *   <li>{@code grpc} (default): the transport, when this build has one.
   * </ul>
   */
  public static Exporter create(ExporterContext context, File logDirectory) {
    String name = configuredName(context.config());
    boolean testExporter = context.config().isSet(ConfigKeys.TEST_EXPORTER);
    try {
      if (name.equals(NOOP)) {
        return testExporter ? new NoopExporter(context.dictionary()) : new NoopExporter();
      }
      if (name.equals(FILE)) {
        String path = context.config().get(ConfigKeys.EXPORTER_FILE_PATH, null);
        File file = path != null ? new File(path) : new File(logDirectory, DEFAULT_FILE_NAME);
        if (testExporter) {
          String directory = testArgument(context.config());
          file =
              new File(directory != null ? new File(directory) : logDirectory, DEFAULT_FILE_NAME);
        }
        long maxBytes =
            context.config().getInt(ConfigKeys.EXPORTER_FILE_MAX_MB, 256, 1, 100_000)
                * 1024L
                * 1024L;
        return new JsonlFileExporter(file, maxBytes, context.dictionary());
      }
      if (name.equals(GRPC)) {
        return createByClassName(GRPC_FACTORY_CLASS, context);
      }
      AgentLogger.warn("unknown exporter '{}' (noop, file, grpc); using noop", name);
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
