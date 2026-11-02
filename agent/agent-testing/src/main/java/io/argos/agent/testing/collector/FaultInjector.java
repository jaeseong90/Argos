/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import io.grpc.Status;
import java.time.Duration;

/**
 * Faults of a {@link FakeCollector} (18 §5.3). Settings apply to calls that arrive after they are
 * set; {@link #clear()} removes the call faults (the TLS mode set by {@link #plaintextOnly()} or
 * {@link #wrongHostCertificate()} stays until the next such call).
 *
 * <ul>
 *   <li>{@link #latency(Duration)}: every answer (unary ack or error, {@code ServerHello}) is sent
 *       that much later;
 *   <li>{@link #failNext(int, Status)}: the next {@code n} unary calls end with {@code status};
 *   <li>{@link #hang()}: unary calls and hellos are never answered (until {@link #clear()}, a
 *       restart or {@link FakeCollector#close()}, which end them with {@code UNAVAILABLE});
 *   <li>{@link #rejectAuth()}: unary calls end with {@code UNAUTHENTICATED}, hellos are rejected
 *       with {@code TOKEN_INVALID};
 *   <li>{@link #closeStream()}: every open {@code Session} stream ends now with {@code
 *       UNAVAILABLE};
 *   <li>{@link #serverRestart(long)}: the server stops (open calls and streams end) and starts
 *       again on the same port; the next hello gets {@code newInstanceId};
 *   <li>{@link #plaintextOnly()}: restart without TLS (a TLS client can no longer connect);
 *   <li>{@link #wrongHostCertificate()}: restart with TLS and a certificate for {@code
 *       wrong.example} only (host name verification must fail, T-SEC-08).
 * </ul>
 *
 * Every call is recorded before a fault decides its answer ({@link ReceivedBatch#outcome()}).
 */
public final class FaultInjector {
  /** What a unary call gets. */
  enum Action {
    ACK,
    FAIL,
    HANG,
    REJECT_AUTH
  }

  /** The decision of one unary call. */
  record Decision(Action action, Status status, Duration latency) {
    String outcome() {
      return switch (action) {
        case ACK -> ReceivedBatch.ACKED;
        case FAIL -> "FAILED " + status.getCode();
        case HANG -> ReceivedBatch.HUNG;
        case REJECT_AUTH -> ReceivedBatch.REJECTED_AUTH;
      };
    }
  }

  private final FakeCollector collector;
  private Duration latency = Duration.ZERO;
  private int failNext;
  private Status failStatus = Status.UNAVAILABLE;
  private boolean hang;
  private boolean rejectAuth;

  FaultInjector(FakeCollector collector) {
    this.collector = collector;
  }

  /** Delays every answer by {@code delay}. */
  public synchronized FaultInjector latency(Duration delay) {
    if (delay.isNegative()) {
      throw new IllegalArgumentException("negative latency " + delay);
    }
    this.latency = delay;
    return this;
  }

  /** The next {@code n} unary calls end with {@code status}. */
  public synchronized FaultInjector failNext(int n, Status status) {
    if (n < 0 || status.isOk()) {
      throw new IllegalArgumentException("failNext needs n >= 0 and a non-OK status");
    }
    this.failNext = n;
    this.failStatus = status;
    return this;
  }

  /** Unary calls and hellos are not answered any more. */
  public synchronized FaultInjector hang() {
    this.hang = true;
    return this;
  }

  /** Unary calls end with {@code UNAUTHENTICATED}, hellos are rejected ({@code TOKEN_INVALID}). */
  public synchronized FaultInjector rejectAuth() {
    this.rejectAuth = true;
    return this;
  }

  /** Ends every open {@code Session} stream now with {@code UNAVAILABLE}. */
  public FaultInjector closeStream() {
    collector.closeSessions(Status.UNAVAILABLE.withDescription("FakeCollector fault: closeStream"));
    return this;
  }

  /** Restarts the server on the same port; the next hello gets {@code newInstanceId}. */
  public FaultInjector serverRestart(long newInstanceId) {
    collector.restart(newInstanceId, collector.tls(), false);
    return this;
  }

  /** Restarts the server without TLS. */
  public FaultInjector plaintextOnly() {
    collector.restart(-1, false, false);
    return this;
  }

  /** Restarts the server with TLS and a certificate for {@code wrong.example} only. */
  public FaultInjector wrongHostCertificate() {
    collector.restart(-1, true, true);
    return this;
  }

  /** Removes latency, failures, hang and auth rejection; hung calls end with UNAVAILABLE. */
  public FaultInjector clear() {
    synchronized (this) {
      latency = Duration.ZERO;
      failNext = 0;
      hang = false;
      rejectAuth = false;
    }
    collector.releaseHung(Status.UNAVAILABLE.withDescription("FakeCollector fault cleared"));
    return this;
  }

  synchronized Decision decideUnary() {
    if (rejectAuth) {
      return new Decision(
          Action.REJECT_AUTH,
          Status.UNAUTHENTICATED.withDescription("FakeCollector fault: rejectAuth"),
          latency);
    }
    if (hang) {
      return new Decision(Action.HANG, null, Duration.ZERO);
    }
    if (failNext > 0) {
      failNext--;
      return new Decision(
          Action.FAIL, failStatus.withDescription("FakeCollector fault: failNext"), latency);
    }
    return new Decision(Action.ACK, null, latency);
  }

  synchronized boolean hanging() {
    return hang;
  }

  synchronized boolean rejectingAuth() {
    return rejectAuth;
  }

  synchronized Duration currentLatency() {
    return latency;
  }
}
