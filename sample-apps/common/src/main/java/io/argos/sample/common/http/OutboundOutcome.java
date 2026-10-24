/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.http;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Result class of an outgoing call and the status GET /api/pay/{id} answers with. The classes match
 * the agent's external-call failure classes (TIMEOUT / CONNECT_FAIL / 5xx, 18 §4.2).
 */
public enum OutboundOutcome {
  /** 2xx (after redirects): the app answers 200. */
  OK(200),
  /** The upstream answered 4xx or 5xx: 502 Bad Gateway. */
  UPSTREAM_ERROR(502),
  /** Connection refused, unknown host, no route: 503 Service Unavailable. */
  CONNECT_FAIL(503),
  /** Read, response or connect timeout: 504 Gateway Timeout. */
  TIMEOUT(504),
  /** Any other transport failure (connection closed without a response, ...): 502. */
  IO_ERROR(502);

  private final int appStatus;

  OutboundOutcome(int appStatus) {
    this.appStatus = appStatus;
  }

  /** HTTP status of the app's own response. */
  public int appStatus() {
    return appStatus;
  }

  public static OutboundOutcome ofStatus(int upstreamStatus) {
    return upstreamStatus >= 200 && upstreamStatus < 400 ? OK : UPSTREAM_ERROR;
  }

  /**
   * Classifies a client exception by walking its cause chain. Timeouts are recognised by type
   * (SocketTimeoutException) or by class name (java.net.http, Netty and Reactor timeouts), refused
   * connections by {@link ConnectException} (HC4/HC5 and Netty subclass it).
   */
  public static OutboundOutcome classify(Throwable failure) {
    for (Throwable t : causes(failure)) {
      if (t instanceof SocketTimeoutException || t.getClass().getSimpleName().contains("Timeout")) {
        return TIMEOUT;
      }
    }
    for (Throwable t : causes(failure)) {
      if (t instanceof ConnectException
          || t instanceof NoRouteToHostException
          || t instanceof UnknownHostException) {
        return CONNECT_FAIL;
      }
    }
    return IO_ERROR;
  }

  /** The failure and its causes, outermost first (at most 32, cycles cut). */
  private static List<Throwable> causes(Throwable failure) {
    List<Throwable> chain = new ArrayList<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (Throwable t = failure; t != null && chain.size() < 32 && seen.add(t); t = t.getCause()) {
      chain.add(t);
    }
    return chain;
  }
}
