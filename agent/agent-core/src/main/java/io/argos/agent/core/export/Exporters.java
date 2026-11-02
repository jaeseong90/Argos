/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigKeys;
import io.argos.agent.core.log.AgentLogger;
import java.io.File;
import java.util.Locale;

/**
 * Exporter registry ({@code exporter} setting): {@code noop}, {@code file} and the {@code grpc}
 * slot. The gRPC transport is found by class name in the agent class loader, so the core does not
 * depend on it at compile time; when it is missing (WO-02 builds without a transport) the agent
 * logs one WARN and drops transactions like {@code noop}.
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

  /** The configured exporter name, lower case ({@code grpc} by default). */
  public static String configuredName(AgentConfig config) {
    return config.get(ConfigKeys.EXPORTER, GRPC).toLowerCase(Locale.ROOT);
  }

  /** Creates the configured exporter; never throws (falls back to {@code noop}). */
  public static Exporter create(ExporterContext context, File logDirectory) {
    String name = configuredName(context.config());
    try {
      if (name.equals(NOOP)) {
        return new NoopExporter();
      }
      if (name.equals(FILE)) {
        String path = context.config().get(ConfigKeys.EXPORTER_FILE_PATH, null);
        File file = path != null ? new File(path) : new File(logDirectory, DEFAULT_FILE_NAME);
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
