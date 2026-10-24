/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.config;

import io.argos.sample.common.http.OutboundClient;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/** The outgoing-call clients of the app; closes the pooled ones on shutdown. */
public final class OutboundClients implements AutoCloseable {

  private final List<OutboundClient> clients;
  private final List<Closeable> closeables;

  public OutboundClients(List<OutboundClient> clients, Closeable... closeables) {
    this.clients = List.copyOf(clients);
    this.closeables = List.of(closeables);
  }

  public List<OutboundClient> all() {
    return clients;
  }

  @Override
  public void close() throws IOException {
    for (Closeable closeable : closeables) {
      closeable.close();
    }
  }
}
