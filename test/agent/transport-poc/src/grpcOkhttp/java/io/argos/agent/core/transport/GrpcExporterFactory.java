/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.agent.core.export.Exporter;
import io.argos.agent.core.export.ExporterContext;
import io.argos.agent.core.export.ExporterFactory;
import io.argos.agent.transportpoc.grpcokhttp.OkHttpGrpcTransport;
import io.argos.agent.transportpoc.sender.PocExporter;
import io.argos.agent.transportpoc.sender.PocSettings;
import io.argos.agent.transportpoc.sender.PocTransport;

/**
 * The {@code grpc} exporter slot of the grpc-okhttp comparison jar (P-3, not the WO-11 transport).
 */
public final class GrpcExporterFactory implements ExporterFactory {
  @Override
  public Exporter create(ExporterContext context) {
    return PocExporter.create(
        context,
        new PocExporter.TransportFactory() {
          @Override
          public PocTransport create(PocSettings settings) {
            return new OkHttpGrpcTransport(settings);
          }
        });
  }
}
