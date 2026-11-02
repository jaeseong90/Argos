/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

/**
 * Creates an {@link Exporter} (exporter SPI). Factories outside this package (the {@code grpc}
 * transport) need a public no-arg constructor; {@link Exporters} instantiates them by class name.
 */
public interface ExporterFactory {
  /** Called on the {@code argos-sender} thread at start, never on the premain thread. */
  Exporter create(ExporterContext context) throws Exception;
}
