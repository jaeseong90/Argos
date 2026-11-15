/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import io.argos.server.collector.limits.Violation;

/**
 * A received request as the collector's marshaller hands it over: the decoded message, or the
 * violation that stopped decoding (decompressed size above the limit, malformed protobuf). Parsing
 * runs inside grpc-java, where an exception becomes {@code UNKNOWN}; returning the violation lets
 * the handler answer with the right code, trailer and counter.
 *
 * @param message the decoded message, {@code null} when rejected
 * @param violation why decoding stopped, {@code null} when decoded
 * @param <T> the message type
 */
public record Inbound<T>(T message, Violation violation) {

  /** Exactly one of the two components is set. */
  public Inbound {
    if ((message == null) == (violation == null)) {
      throw new IllegalArgumentException("Either a message or a violation");
    }
  }

  /** A decoded message. */
  public static <T> Inbound<T> of(T message) {
    return new Inbound<>(message, null);
  }

  /** A rejected message. */
  public static <T> Inbound<T> rejected(Violation violation) {
    return new Inbound<>(null, violation);
  }

  /** Whether decoding stopped with a violation. */
  public boolean rejected() {
    return violation != null;
  }
}
