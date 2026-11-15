/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.export;

import java.nio.file.Path;
import java.util.Map;

/**
 * Agent settings of the test exporter (18 §5.4, 10 §19; keys without {@code argos.}), for {@code
 * AppLauncher.withAgent(jar, settings)}. Release builds ignore {@code argos.test.*} (T-BLD-010).
 */
public final class ExporterSettings {
  /** {@code argos.test.exporter}. */
  public static final String KEY = "test.exporter";

  private ExporterSettings() {}

  /** JSONL into {@code <directory>/argos-transactions.jsonl} ({@link FileExporterReader}). */
  public static Map<String, String> file(Path directory) {
    return Map.of(KEY, "file:" + directory.toAbsolutePath());
  }

  /** Encode every transaction, then drop it (instrumentation cost without transport, P-2). */
  public static Map<String, String> noop() {
    return Map.of(KEY, "noop");
  }

  /** The default exporter: the gRPC transport to {@code server.address} (WO-11). */
  public static Map<String, String> grpc() {
    return Map.of(KEY, "grpc");
  }
}
