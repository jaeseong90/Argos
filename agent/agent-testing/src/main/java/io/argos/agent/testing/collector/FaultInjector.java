/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import io.grpc.Status;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Faults of a {@link FakeCollector} (18 §5.3). Settings apply to calls that arrive after they are
 * set; {@link #clear()} removes every setting (the TLS mode set by {@link #plaintextOnly()} or
 * {@link #wrongHostCertificate()} stays until the next such call). The answers follow the status
 * code table of 11 §10 and the hello checks of 11 §5; the trailer keys are {@value
 * #RETRY_AFTER_TRAILER} and {@value #REJECT_REASON_TRAILER}.
 *
 * <p>Data calls ({@code Send*}) are decided in this order, each one recorded first ({@link
 * ReceivedBatch#outcome()}):
 *
 * <ol>
 *   <li>{@link #hang()}: never answered;
 *   <li>token (11 §2, 12 §3.1, before the session check): missing, not {@code Bearer
 *       argos_<base64url>}, {@link #rejectAuth()} or not the {@link #requireToken(String)} value:
 *       {@code UNAUTHENTICATED}, trailer {@code argos-reject-reason: TOKEN_INVALID}; revoked by
 *       {@link #sessionClose} {@code TOKEN_REVOKED_CLOSE}: {@code UNAUTHENTICATED}, trailer {@code
 *       argos-reject-reason: TOKEN_REVOKED};
 *   <li>session (11 §3): no accepted session for {@code argos-session-id} and {@code
 *       argos-instance-id} (before a hello, after {@link #serverRestart}, after {@link
 *       #sessionClose}): {@code FAILED_PRECONDITION} with description {@code SESSION_REQUIRED};
 *       another token than the session's: {@code PERMISSION_DENIED};
 *   <li>{@link #failNext}: the given status (with {@code argos-retry-after-ms} when set);
 *   <li>acknowledgement: {@code ACK_DUPLICATE} for a key already written (11 §6), else {@code
 *       ACK_OK}, {@code ACK_PARTIAL_DROP} under {@link #partialDrop(int)}, or {@code ACK_OK} with
 *       every item dropped and nothing written under {@link #walFull()}; {@link #throttle} adds
 *       {@code Ack.throttle}.
 * </ol>
 *
 * {@link #latency(Duration)} delays every answer but a hang. Hellos: {@link #hang()} leaves them
 * unanswered; otherwise they run the checks of the 11 §5 table in its order, each failure sending
 * {@code ServerHello{accepted = false}} and ending the stream with the status of {@link
 * #rejectionOf(String)}: 1 token ({@code TOKEN_INVALID}, {@code TOKEN_REVOKED} for a token revoked
 * by {@link #sessionClose}, and {@link #rejectHello} {@code TOKEN_*}), 2 {@code APP_MISMATCH}, 3
 * {@link #plaintextRejected()} on a plaintext connection, 4 protocol version outside {@code [1,
 * current]} (and the version reasons), 5 {@code APP_NOT_REGISTERED}, 6 {@code INSTANCE_LIMIT} and
 * {@code RATE_LIMITED}. {@link #rejectHello} injects a reason at its own check, so a test can show
 * the order.
 */
public final class FaultInjector {
  /** Trailer: retry advice in milliseconds (11 §10). */
  public static final String RETRY_AFTER_TRAILER = "argos-retry-after-ms";

  /** Trailer: the {@code RejectReason} name (11 §10). */
  public static final String REJECT_REASON_TRAILER = "argos-reject-reason";

  /** {@code CloseReason} names of 11 §4.2. */
  static final Set<String> CLOSE_REASONS =
      Set.of(
          "TOKEN_REVOKED_CLOSE", "SERVER_SHUTDOWN", "REPLACED", "PROTOCOL_ERROR", "IDLE_TIMEOUT");

  /**
   * How the collector refuses a hello (11 §5 check table).
   *
   * @param reason {@code RejectReason} name
   * @param code status that ends the stream after the {@code ServerHello} ({@code OK}: the stream
   *     completes normally)
   * @param defaultRetryAfterS {@code retry_after_s} of 11 §5
   * @param check the row of the 11 §5 check table ({@value #AFTER_LAST_CHECK}: not in the table)
   */
  public record HelloRejection(
      String reason, Status.Code code, int defaultRetryAfterS, int check) {}

  /** The check of a rejection that is not in the 11 §5 table: after every check of the table. */
  private static final int AFTER_LAST_CHECK = 7;

  private static final Map<String, HelloRejection> REJECTIONS =
      Map.ofEntries(
          rejection("TOKEN_INVALID", Status.Code.UNAUTHENTICATED, 0, 1),
          rejection("TOKEN_REVOKED", Status.Code.UNAUTHENTICATED, 0, 1),
          rejection("TOKEN_EXPIRED", Status.Code.UNAUTHENTICATED, 3600, 1),
          rejection("APP_MISMATCH", Status.Code.PERMISSION_DENIED, 0, 2),
          rejection("PLAINTEXT_NOT_ALLOWED", Status.Code.FAILED_PRECONDITION, 0, 3),
          rejection("PROTOCOL_UNSUPPORTED", Status.Code.FAILED_PRECONDITION, 0, 4),
          rejection("AGENT_VERSION_TOO_NEW", Status.Code.FAILED_PRECONDITION, 0, 4),
          rejection("AGENT_VERSION_TOO_OLD", Status.Code.FAILED_PRECONDITION, 0, 4),
          // 11 §5 check 5 names no status: the stream completes after the rejected hello.
          rejection("APP_NOT_REGISTERED", Status.Code.OK, 300, 5),
          rejection("INSTANCE_LIMIT", Status.Code.RESOURCE_EXHAUSTED, 60, 6),
          rejection("RATE_LIMITED", Status.Code.RESOURCE_EXHAUSTED, 60, 6),
          // Not in the 11 §5 table; UNAVAILABLE is the 11 §10 code of a server shutting down.
          rejection("SERVER_SHUTTING_DOWN", Status.Code.UNAVAILABLE, 5, AFTER_LAST_CHECK));

  private static Map.Entry<String, HelloRejection> rejection(
      String reason, Status.Code code, int retryAfterS, int check) {
    return Map.entry(reason, new HelloRejection(reason, code, retryAfterS, check));
  }

  /** The 11 §5 status and default retry of a {@code RejectReason} name. */
  public static HelloRejection rejectionOf(String reason) {
    HelloRejection rejection = REJECTIONS.get(reason);
    if (rejection == null) {
      throw new IllegalArgumentException(
          "unknown RejectReason " + reason + " (known: " + REJECTIONS.keySet() + ")");
    }
    return rejection;
  }

  /**
   * The status that ends a stream after {@code SessionClose{reason}}: {@code UNAUTHENTICATED} for
   * {@code TOKEN_REVOKED_CLOSE} (12 §4.1), {@code UNAVAILABLE} for {@code SERVER_SHUTDOWN} (11
   * §10), otherwise a normal completion.
   */
  public static Status.Code closeStatusOf(String reason) {
    return switch (reason) {
      case "TOKEN_REVOKED_CLOSE" -> Status.Code.UNAUTHENTICATED;
      case "SERVER_SHUTDOWN" -> Status.Code.UNAVAILABLE;
      default -> Status.Code.OK;
    };
  }

  private final FakeCollector collector;
  private Duration latency = Duration.ZERO;
  private int failNext;
  private Status failStatus = Status.UNAVAILABLE;
  private Duration failRetryAfter;
  private boolean hang;
  private boolean rejectAuth;
  private String requiredToken;
  private boolean plaintextRejected;
  private String helloRejectReason;
  private Duration helloRetryAfter;
  private int partialDrop;
  private boolean walFull;
  private boolean sessionReadsPaused;
  private CollectorProtocol.ThrottleHint throttle;
  private Duration clockOffset = Duration.ZERO;

  /**
   * Tokens revoked by {@link #sessionClose} {@code TOKEN_REVOKED_CLOSE} (at most one per session).
   */
  private final Set<ReceivedToken> revokedTokens = new HashSet<>();

  FaultInjector(FakeCollector collector) {
    this.collector = collector;
  }

  /** Delays every answer (unary ack or error, {@code ServerHello}) by {@code delay}. */
  public synchronized FaultInjector latency(Duration delay) {
    if (delay.isNegative()) {
      throw new IllegalArgumentException("negative latency " + delay);
    }
    this.latency = delay;
    return this;
  }

  /**
   * The next {@code n} data calls (that pass the token and session checks) end with {@code status};
   * its description is kept when set (for example {@code SESSION_REQUIRED}, 11 §10).
   */
  public synchronized FaultInjector failNext(int n, Status status) {
    return failNext(n, status, null);
  }

  /**
   * As {@link #failNext(int, Status)}, with the trailer {@value #RETRY_AFTER_TRAILER} (11 §10:
   * {@code RESOURCE_EXHAUSTED} quotas and rate limits); null leaves it out.
   */
  public synchronized FaultInjector failNext(int n, Status status, Duration retryAfter) {
    if (n < 0 || status.isOk()) {
      throw new IllegalArgumentException("failNext needs n >= 0 and a non-OK status");
    }
    this.failNext = n;
    this.failStatus = status;
    this.failRetryAfter = retryAfter;
    return this;
  }

  /** Data calls, hellos and heartbeats are not answered any more. */
  public synchronized FaultInjector hang() {
    this.hang = true;
    return this;
  }

  /** Data calls end with {@code UNAUTHENTICATED}, hellos are rejected ({@code TOKEN_INVALID}). */
  public synchronized FaultInjector rejectAuth() {
    this.rejectAuth = true;
    return this;
  }

  /**
   * Only this token is accepted (11 §2; by default any well-formed {@code argos_<base64url>} token
   * is): others get {@code UNAUTHENTICATED} on data calls and {@code TOKEN_INVALID} on hellos.
   */
  public synchronized FaultInjector requireToken(String token) {
    if (token == null || token.isEmpty()) {
      throw new IllegalArgumentException("requireToken needs a token");
    }
    this.requiredToken = token;
    return this;
  }

  /**
   * Hellos over a plaintext connection are rejected with {@code PLAINTEXT_NOT_ALLOWED} and {@code
   * FAILED_PRECONDITION} (11 §5 check 3, server {@code allow-plaintext: false}).
   */
  public synchronized FaultInjector plaintextRejected() {
    this.plaintextRejected = true;
    return this;
  }

  /**
   * Hellos are rejected with {@code reason} ({@code RejectReason} name) and the 11 §5 default
   * {@code retry_after_s} (see {@link #rejectionOf(String)}).
   */
  public FaultInjector rejectHello(String reason) {
    return rejectHello(reason, Duration.ofSeconds(rejectionOf(reason).defaultRetryAfterS()));
  }

  /** Hellos are rejected with {@code reason} and {@code retry_after_s = retryAfter}. */
  public synchronized FaultInjector rejectHello(String reason, Duration retryAfter) {
    rejectionOf(reason);
    if (retryAfter.isNegative()) {
      throw new IllegalArgumentException("negative retry " + retryAfter);
    }
    this.helloRejectReason = reason;
    this.helloRetryAfter = retryAfter;
    return this;
  }

  /**
   * Data calls that would be {@code ACK_OK} are {@code ACK_PARTIAL_DROP} with {@code dropped_items
   * = min(droppedItems, items of the batch)} (11 §6 quota); the batch is still recorded whole.
   */
  public synchronized FaultInjector partialDrop(int droppedItems) {
    if (droppedItems < 0) {
      throw new IllegalArgumentException("negative partialDrop " + droppedItems);
    }
    this.partialDrop = droppedItems;
    return this;
  }

  /**
   * Data calls that would be written are acknowledged {@code ACK_OK} with {@code dropped_items} =
   * every item and {@code last_seq} unchanged, and nothing is written (11 §6 "서버 드롭", DSN-11-09, 12
   * §3.2 {@code WalFullException}): the batch is not in {@link Collected}, and a resend of the same
   * seq after {@link #clear()} is new. A key already written is still {@code ACK_DUPLICATE}.
   */
  public synchronized FaultInjector walFull() {
    this.walFull = true;
    return this;
  }

  /**
   * The collector stops reading the {@code Session} streams after the message it is handling (no
   * more HTTP/2 flow control credit), so the client's stream stops being ready once the window
   * ({@link FakeCollector.Builder#flowControlWindow}) and gRPC's buffer are full (T-AG-071 {@code
   * isReady} back pressure). Nothing is lost: {@link #resumeSessionReads()} reads the rest. Data
   * calls are not affected.
   */
  public synchronized FaultInjector pauseSessionReads() {
    this.sessionReadsPaused = true;
    return this;
  }

  /** Ends {@link #pauseSessionReads()}: the streams are read again from where they stopped. */
  public FaultInjector resumeSessionReads() {
    synchronized (this) {
      this.sessionReadsPaused = false;
    }
    collector.resumeSessionReads();
    return this;
  }

  /**
   * Every following {@code Ack} carries {@code Ack.throttle} (11 §6 back pressure); a null argument
   * leaves that field absent. {@link FakeCollector#sendThrottle} sends one on the session stream.
   */
  public synchronized FaultInjector throttle(Float profileNormalRate, Duration sendBackoff) {
    this.throttle =
        new CollectorProtocol.ThrottleHint(
            profileNormalRate,
            sendBackoff == null ? null : Math.toIntExact(sendBackoff.toMillis()));
    return this;
  }

  /**
   * The collector's clock runs {@code offset} ahead of the real clock (negative: behind): {@code
   * ServerHello.server_time_ms} and {@code HeartbeatAck.t2/t3} carry it, so the 11 §9 offset
   * estimate moves by {@code offset} (T-ACC-02).
   */
  public synchronized FaultInjector clockOffset(Duration offset) {
    this.clockOffset = offset;
    return this;
  }

  /** Ends every open {@code Session} stream now with {@code UNAVAILABLE} (connection loss). */
  public FaultInjector closeStream() {
    collector.closeSessions(Status.UNAVAILABLE.withDescription("FakeCollector fault: closeStream"));
    return this;
  }

  /**
   * Sends {@code SessionClose{reason, retry_after_s}} on every open session, ends the streams
   * ({@link #closeStatusOf(String)}) and forgets the sessions (12 §4.1 registry removal: data calls
   * of those sessions get {@code SESSION_REQUIRED} until a new hello).
   *
   * <p>{@code TOKEN_REVOKED_CLOSE} also revokes the token of each closed session, as the server's
   * revocation does (11 §10 {@code UNAUTHENTICATED}; 12 §4.1 {@code closeByToken} after the token
   * cache is invalidated at once; 12 §3.1 {@code TokenAuthInterceptor} runs before the session
   * check): a data call with that token then ends {@code UNAUTHENTICATED} with trailer {@code
   * argos-reject-reason: TOKEN_REVOKED}, and a hello gets {@code ServerHello{rejected,
   * TOKEN_REVOKED, retry_after_s 0}} and {@code UNAUTHENTICATED} (11 §5 check 1). Recovery: {@link
   * #clear()} lifts the revocation, or the test uses another token (the collector accepts any
   * well-formed token unless {@link #requireToken} narrows it), as an operator issues a new one.
   *
   * <p>{@code SERVER_SHUTDOWN} does not imitate the server's shutdown drain (12 §3.9: a server
   * shutting down answers new calls {@code UNAVAILABLE}): the collector keeps serving, so data
   * calls get {@code SESSION_REQUIRED} and a hello is accepted.
   */
  public FaultInjector sessionClose(String reason, Duration retryAfter) {
    if (!CLOSE_REASONS.contains(reason)) {
      throw new IllegalArgumentException("unknown CloseReason " + reason + " " + CLOSE_REASONS);
    }
    collector.sessionCloseAll(reason, Math.toIntExact(retryAfter.toSeconds()));
    return this;
  }

  /**
   * Restarts the server on the same port: open calls and streams end, sessions are forgotten
   * ({@code SESSION_REQUIRED}), and the next hello gets {@code newInstanceId}.
   */
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

  /**
   * Removes every call fault, the clock offset and the token revocations of {@link #sessionClose};
   * hung calls end with UNAVAILABLE.
   */
  public FaultInjector clear() {
    synchronized (this) {
      revokedTokens.clear();
      latency = Duration.ZERO;
      failNext = 0;
      failRetryAfter = null;
      hang = false;
      rejectAuth = false;
      requiredToken = null;
      plaintextRejected = false;
      helloRejectReason = null;
      helloRetryAfter = null;
      partialDrop = 0;
      walFull = false;
      throttle = null;
      clockOffset = Duration.ZERO;
    }
    collector.releaseHung(Status.UNAVAILABLE.withDescription("FakeCollector fault cleared"));
    return resumeSessionReads();
  }

  // --- read by the collector --------------------------------------------------------------------

  synchronized boolean hanging() {
    return hang;
  }

  synchronized Duration currentLatency() {
    return latency;
  }

  synchronized Duration currentClockOffset() {
    return clockOffset;
  }

  /** True when {@code token} passes the token check (11 §2, {@link #requireToken}). */
  synchronized boolean tokenAccepted(ReceivedToken token) {
    if (rejectAuth || token == null || !token.wellFormed()) {
      return false;
    }
    return requiredToken == null || token.matches(requiredToken);
  }

  /**
   * The token check of a call (11 §2, 12 §3.1, 11 §5 check 1): null when {@code token} passes, else
   * the {@code RejectReason} name ({@code TOKEN_INVALID}, or {@code TOKEN_REVOKED} for a token
   * {@link #sessionClose} revoked).
   */
  synchronized String tokenRejectReason(ReceivedToken token) {
    if (!tokenAccepted(token)) {
      return "TOKEN_INVALID";
    }
    return revokedTokens.contains(token) ? "TOKEN_REVOKED" : null;
  }

  /** {@link #sessionClose} {@code TOKEN_REVOKED_CLOSE}: {@code token} is refused from now on. */
  synchronized void revoke(ReceivedToken token) {
    if (token != null) {
      revokedTokens.add(token);
    }
  }

  /** True when {@code token} was revoked ({@link #revoke}) and not lifted by {@link #clear()}. */
  synchronized boolean revoked(ReceivedToken token) {
    return token != null && revokedTokens.contains(token);
  }

  /**
   * The configured failure of the next data call, or null (consumes one of {@link #failNext}). A
   * description given by the caller is kept, so that 11 §10 descriptions such as {@code
   * SESSION_REQUIRED} on {@code FAILED_PRECONDITION} reach the agent; otherwise the status carries
   * a fault marker.
   */
  synchronized Failure nextFailure() {
    if (failNext <= 0) {
      return null;
    }
    failNext--;
    Status status =
        failStatus.getDescription() == null
            ? failStatus.withDescription("FakeCollector fault: failNext")
            : failStatus;
    return new Failure(status, failRetryAfter);
  }

  /** One {@link #failNext} answer. */
  record Failure(Status status, Duration retryAfter) {}

  synchronized int partialDropItems() {
    return partialDrop;
  }

  synchronized boolean walFullActive() {
    return walFull;
  }

  synchronized boolean sessionReadsPaused() {
    return sessionReadsPaused;
  }

  synchronized CollectorProtocol.ThrottleHint ackThrottle() {
    return throttle;
  }

  /** The rejection of a hello, or null when it is accepted (the checks of 11 §5 in order). */
  synchronized Rejected helloRejection(
      CollectorProtocol.CallMetadata metadata, int protocolVersion, int currentVersion) {
    for (int check = 1; check <= AFTER_LAST_CHECK; check++) {
      Rejected rejected = builtInCheck(check, metadata, protocolVersion, currentVersion);
      if (rejected == null) {
        rejected = injectedAt(check);
      }
      if (rejected != null) {
        return rejected;
      }
    }
    return null;
  }

  /** The checks the collector decides itself; the others fail only through {@link #rejectHello}. */
  private Rejected builtInCheck(
      int check, CollectorProtocol.CallMetadata metadata, int protocolVersion, int currentVersion) {
    if (check == 1) {
      String reason = tokenRejectReason(metadata.token());
      if (reason != null) {
        return rejected(
            reason,
            "TOKEN_REVOKED".equals(reason)
                ? "token revoked"
                : "token missing, malformed or not accepted");
      }
    }
    if (check == 3 && plaintextRejected && metadata.plaintext()) {
      return rejected("PLAINTEXT_NOT_ALLOWED", "plaintext connections are not allowed");
    }
    if (check == 4 && (protocolVersion < 1 || protocolVersion > currentVersion)) {
      return rejected(
          "PROTOCOL_UNSUPPORTED",
          "protocol version " + protocolVersion + " outside [1, " + currentVersion + "]");
    }
    return null;
  }

  private Rejected injectedAt(int check) {
    if (helloRejectReason == null || rejectionOf(helloRejectReason).check() != check) {
      return null;
    }
    return new Rejected(
        rejectionOf(helloRejectReason),
        "FakeCollector fault: rejectHello",
        Math.toIntExact(helloRetryAfter.toSeconds()));
  }

  private static Rejected rejected(String reason, String message) {
    HelloRejection rejection = rejectionOf(reason);
    return new Rejected(rejection, message, rejection.defaultRetryAfterS());
  }

  /** A refused hello. */
  record Rejected(HelloRejection rejection, String message, int retryAfterS) {}
}
