/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import tools.jackson.databind.JsonNode;

/**
 * T-CH-007 "TLS 호스트명 불일치" (18 §11.1, "잘못된 인증서"): two applications of their own dial the collector
 * (through toxiproxy) as {@link ChaosStack#MISMATCH_NAME}, a name their hosts file maps to
 * 127.0.0.1 but the run's collector certificate does not carry, one after the other:
 *
 * <ol>
 *   <li>verifying (the default): no connection, a WARN naming the TLS failure, no session and no
 *       instance on the server, and the application unaffected under load for the fault length
 *       (every request answered; agent ERROR 0);
 *   <li>{@code server.tls.insecure-skip-verify: true}: it connects; the proxy is cut twice so that
 *       it reconnects, and every connection logs the WARN "TLS verification disabled" (16 §3.4,
 *       SEC-08 "매 접속 WARN").
 * </ol>
 *
 * The fault exists from each application's start, so the scenario runs standalone ({@link
 * StandaloneFault}): no baseline or recovery window.
 */
public final class TlsMismatchFault implements StandaloneFault {

  /** The scenario. */
  public static final String ID = "T-CH-007";

  /** The WARN of a failed connect (agent-core {@code SessionClient.warnConnect}). */
  static final String CONNECT_FAILED = "collector connection failed";

  /** The WARN of every skip-verify connect (agent-core {@code SessionClient.warnSecurity}). */
  static final String SKIP_VERIFY_WARN = "TLS verification disabled";

  /** Forced reconnects of the skip-verify agent. */
  static final int RECONNECTS = 2;

  private ChaosApp verifying;
  private ChaosApp skipping;

  @Override
  public String describe() {
    return "agents dialing "
        + ChaosStack.MISMATCH_NAME
        + " (not in the collector certificate): one verifying, one with"
        + " server.tls.insecure-skip-verify reconnected "
        + RECONNECTS
        + " times";
  }

  /** Whether a WARN message reports a failed TLS handshake or certificate check. */
  static boolean tlsFailure(String message) {
    String m = message.toLowerCase(Locale.ROOT);
    return m.contains(CONNECT_FAILED)
        && (m.contains("ssl") || m.contains("certificate") || m.contains("handshake"));
  }

  @Override
  public List<Check> run(ScenarioContext c) throws Exception {
    ChaosStack stack = c.stack();
    List<Check> rows = new ArrayList<>();
    HostsFile hosts = new HostsFile(stack.work().resolve(ID).resolve("hosts"));
    hosts.put(ChaosStack.MISMATCH_NAME, "127.0.0.1");
    String address = ChaosStack.MISMATCH_NAME + ":" + stack.proxyPort();

    // 1. Verifying agent under load.
    verifying =
        stack.startApp(
            "argos-chaos-tls",
            "chaos-tls-verify",
            address,
            Map.of(),
            launcher -> launcher.withJvmOptions(hosts.jvmOptions().toArray(String[]::new)),
            stack.work().resolve(ID).resolve("app-verify"),
            false);
    stack.use(verifying);
    c.faultStart = Instant.now();
    Duration length = c.faultLength();
    LoadSummary load;
    try (LoadRun run =
        LoadRun.start(
            Paths.get(System.getProperty("java.home"), "bin", "java"),
            c.options().loadJar(),
            verifying.baseUrl(),
            c.options().rate(),
            length,
            stack.work().resolve(ID).resolve("load"))) {
      load = run.await(length.plus(Duration.ofMinutes(3)));
      c.intervals = run.intervals();
    }
    c.load = load;
    c.faultEnd = Instant.now();
    AgentLog log = verifying.agentLog();
    List<AgentLog.Line> failures =
        log.between(
            c.start(), c.faultEnd(), l -> l.level().equals("WARN") && tlsFailure(l.message()));
    rows.add(
        new Check(
            c.id(),
            "verifying agent: connection refused by the host name check, with a WARN",
            ">= 1 WARN naming the TLS failure",
            failures.size() + (failures.isEmpty() ? "" : ": " + failures.get(0).message()),
            Check.Kind.INVARIANT,
            Judge.invariant(!failures.isEmpty()),
            "agent log"));
    OptionalLong sessions = new AgentCounts(verifying.agentCounters()).get(AgentCounts.SESSIONS);
    JsonNode instances = stack.instances(verifying.appId());
    boolean none =
        (sessions.isEmpty() || sessions.getAsLong() == 0)
            && instances.isArray()
            && instances.isEmpty();
    rows.add(
        new Check(
            c.id(),
            "verifying agent: no session, no instance on the server",
            "sessions 0, instances []",
            "sessions "
                + (sessions.isPresent() ? sessions.getAsLong() : "-")
                + ", instances "
                + instances.size(),
            Check.Kind.INVARIANT,
            Judge.invariant(none),
            "argos_agent_session_established_total; GET /api/v1/apps/{id}/instances"));
    rows.add(
        new Check(
            c.id(),
            "application unaffected while its agent cannot connect",
            "every request answered",
            String.format(
                Locale.ROOT,
                "%d sent, %d answered, %d without answer, p99 %.1f ms",
                load.sent(),
                load.answered(),
                load.failedWithoutAnswer(),
                load.p99()),
            Check.Kind.INVARIANT,
            Judge.invariant(load.failedWithoutAnswer() == 0 && verifying.app().isAlive()),
            "sample-apps/load summary over the fault length"));
    rows.add(errorRow(c, "verifying agent", log));
    verifying.close();
    verifying = null;

    // 2. skip-verify agent: connects, WARN on every connection.
    skipping =
        stack.startApp(
            "argos-chaos-tls-skip",
            "chaos-tls-skip",
            address,
            Map.of("server.tls.insecure-skip-verify", "true"),
            launcher -> launcher.withJvmOptions(hosts.jvmOptions().toArray(String[]::new)),
            stack.work().resolve(ID).resolve("app-skip"),
            false);
    stack.use(skipping);
    String connected = "connected";
    try {
      stack.awaitInstanceUp(Duration.ofMinutes(2));
    } catch (IllegalStateException e) {
      connected = "not connected: " + e.getMessage();
    }
    int reconnects = 0;
    if (connected.equals("connected")) {
      ToxiproxyClient t = stack.toxiproxy().client();
      for (int i = 0; i < RECONNECTS; i++) {
        long before = sessions(skipping);
        t.setEnabled(Toxiproxy.PROXY, false);
        Thread.sleep(3_000);
        t.setEnabled(Toxiproxy.PROXY, true);
        if (awaitSessions(skipping, before + 1, Duration.ofSeconds(90))) {
          reconnects++;
        }
      }
    }
    long established = sessions(skipping);
    Thread.sleep(2_000);
    AgentLog skipLog = skipping.agentLog();
    List<AgentLog.Line> skipWarns =
        skipLog.between(
            c.start(),
            Instant.now(),
            l -> l.level().equals("WARN") && l.message().contains(SKIP_VERIFY_WARN));
    rows.add(
        new Check(
            c.id(),
            "skip-verify agent: connects",
            "instance UP, " + RECONNECTS + " reconnects after the proxy was cut",
            connected + ", " + reconnects + " reconnects, " + established + " sessions",
            Check.Kind.INVARIANT,
            Judge.invariant(connected.equals("connected") && reconnects == RECONNECTS),
            "argos_agent_session_established_total"));
    rows.add(
        new Check(
            c.id(),
            "skip-verify agent: WARN on every connection",
            "WARN '" + SKIP_VERIFY_WARN + "' >= sessions established (" + established + ")",
            skipWarns.size() + " WARN",
            Check.Kind.INVARIANT,
            Judge.invariant(established >= 1 + RECONNECTS && skipWarns.size() >= established),
            "16 §3.4, SEC-08"));
    rows.add(errorRow(c, "skip-verify agent", skipLog));
    List<AgentLog.Line> retransform = skipLog.retransformFailures(c.start(), Instant.now());
    rows.add(
        new Check(
            c.id(),
            "agent log retransformation failure lines (C-257 (2))",
            "reported, not silent",
            retransform.size() + (retransform.isEmpty() ? "" : ": " + retransform.get(0).message()),
            Check.Kind.INFO,
            Verdict.INFO,
            "agent-core RetransformFailureListener WARN"));
    return rows;
  }

  private static Check errorRow(ScenarioContext c, String who, AgentLog log) {
    List<AgentLog.Line> errors = log.errors(c.start(), Instant.now());
    return new Check(
        c.id(),
        who + ": agent log ERROR lines",
        "0",
        errors.size() + (errors.isEmpty() ? "" : ": " + errors.get(0).message()),
        Check.Kind.INVARIANT,
        Judge.invariant(errors.isEmpty()),
        "");
  }

  private static long sessions(ChaosApp app) {
    return new AgentCounts(app.agentCounters()).get(AgentCounts.SESSIONS).orElse(0);
  }

  private static boolean awaitSessions(ChaosApp app, long atLeast, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (sessions(app) >= atLeast) {
        return true;
      }
      Thread.sleep(1_000);
    }
    return false;
  }

  @Override
  public void cleanup(ScenarioContext c) {
    try {
      c.stack().toxiproxy().client().setEnabled(Toxiproxy.PROXY, true);
    } catch (RuntimeException e) {
      // already enabled
    }
    if (verifying != null) {
      verifying.close();
    }
    if (skipping != null) {
      skipping.close();
    }
  }
}
