/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.config;

import io.argos.sample.common.http.OutboundClient;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The outgoing-call clients of the app; closes the pooled ones on shutdown (WAR undeploy). */
public final class OutboundClients implements AutoCloseable {

  private final List<OutboundClient> clients;
  private final List<Closeable> closeables;

  public OutboundClients(List<OutboundClient> clients, Closeable... closeables) {
    this.clients = Collections.unmodifiableList(new ArrayList<>(clients));
    this.closeables = Collections.unmodifiableList(Arrays.asList(closeables));
  }

  public List<OutboundClient> all() {
    return clients;
  }

  /** Client names as {@code ?client=} accepts them. */
  public List<String> names() {
    List<String> names = new ArrayList<>();
    for (OutboundClient client : clients) {
      names.add(client.name());
    }
    return names;
  }

  /** Closes every pooled client; the first failure is thrown after all were tried. */
  @Override
  public void close() throws IOException {
    IOException first = null;
    for (Closeable closeable : closeables) {
      try {
        closeable.close();
      } catch (IOException e) {
        if (first == null) {
          first = e;
        } else {
          first.addSuppressed(e);
        }
      }
    }
    if (first != null) {
      throw first;
    }
  }
}
