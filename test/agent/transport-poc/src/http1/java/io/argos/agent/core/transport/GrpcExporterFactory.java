/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.agent.core.export.Exporter;
import io.argos.agent.core.export.ExporterContext;
import io.argos.agent.core.export.ExporterFactory;
import io.argos.agent.transportpoc.http1.Http1Transport;
import io.argos.agent.transportpoc.sender.PocExporter;
import io.argos.agent.transportpoc.sender.PocSettings;
import io.argos.agent.transportpoc.sender.PocTransport;

/**
 * The {@code grpc} exporter slot of the HTTP/1.1 comparison jar (P-3): the slot name is the one the
 * core knows; the transport behind it is the own HTTP/1.1 + protobuf client.
 */
public final class GrpcExporterFactory implements ExporterFactory {
  @Override
  public Exporter create(ExporterContext context) {
    return PocExporter.create(
        context,
        new PocExporter.TransportFactory() {
          @Override
          public PocTransport create(PocSettings settings) {
            return new Http1Transport(settings);
          }
        });
  }
}
