/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.sender;

import io.argos.agent.transportpoc.proto.Ack;
import io.argos.agent.transportpoc.proto.TransactionBatch;

/**
 * One transport of the P-3 comparison (grpc-netty, grpc-okhttp, HTTP/1.1). Only the {@code
 * argos-sender} thread calls it; every call is bounded by the connect timeout or the deadline.
 */
public interface PocTransport {
  /** Creates the client (channel, socket factory); may fail, e.g. without ALPN on Java 8. */
  void start() throws Exception;

  /** Sends one batch and waits for the ack (deadline {@code server.deadline-ms}). */
  Ack send(TransactionBatch batch) throws Exception;

  /** What was negotiated on the current connection, for the agent log ("TLSv1.3 h2"). */
  String connectionInfo();

  /** Releases the connection and threads (shutdown hook, at most about one second). */
  void close();

  /** Short name for the banner ({@code grpc-netty}). */
  String name();
}
