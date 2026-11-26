/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * T-CH-006 "DNS 실패" (18 §11.1, "서버 호스트명 해석 실패(/etc/hosts 제거)"): the scenario's own shop-boot3 (JDK
 * 21) resolves names from its own hosts file only ({@link HostsFile}, {@code -Djdk.net.hosts.file},
 * DNS cache 1 s) and its agent dials {@link ChaosStack#COLLECTOR_NAME} (a name of the run's
 * collector certificate) at toxiproxy's port.
 *
 * <ol>
 *   <li>Before the scenario ({@link #prepare}) the name is absent: the application must start as
 *       fast as the main app did ("premain 비블로킹": ready time within the main app's + {@link
 *       #READY_MARGIN}), its agent must report the failed lookup with a WARN and hold no session;
 *       then the name is added and the agent must connect on its own ("재시도").
 *   <li>The fault removes the name again and cuts the open connection (toxiproxy proxy disabled for
 *       2 s), so that the agent's reconnects meet the failing lookup; removing the fault adds the
 *       name back, and the common rows judge the recovery.
 * </ol>
 */
public final class DnsFailureFault implements Fault {

  /** The scenario. */
  public static final String ID = "T-CH-006";

  /** Allowance on the ready time against the main app's (JIT, a shared machine). */
  static final Duration READY_MARGIN = Duration.ofSeconds(10);

  /** How long the agent gets to report the failed lookup before the name is added. */
  static final Duration UNRESOLVED_WINDOW = Duration.ofSeconds(20);

  /** How long the first connect after adding the name may take (back-off cap 30 s + jitter). */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(90);

  /** The WARN of a failed connect (agent-core {@code SessionClient.warnConnect}). */
  static final String CONNECT_FAILED = "collector connection failed";

  private HostsFile hosts;
  private ChaosApp app;

  @Override
  public String describe() {
    return "own app resolving names from a hosts file (jdk.net.hosts.file): started with the"
        + " collector name absent, then the name removed again and the connection cut";
  }

  @Override
  public ChaosApp prepare(ScenarioContext c) throws Exception {
    ChaosStack stack = c.stack();
    hosts = new HostsFile(stack.work().resolve(ID).resolve("hosts"));
    String address = ChaosStack.COLLECTOR_NAME + ":" + stack.proxyPort();
    Instant spawn = Instant.now();
    ChaosApp started =
        stack.startApp(
            "argos-chaos-dns",
            "chaos-dns",
            address,
            Map.of(),
            launcher -> launcher.withJvmOptions(hosts.jvmOptions().toArray(String[]::new)),
            stack.work().resolve(ID).resolve("app"),
            false);
    app = started;
    stack.use(app);
    c.put("readyUnresolved", app.readyTime());
    c.put("readyMain", stack.mainApp().readyTime());
    c.put("spawn", spawn);
    Thread.sleep(UNRESOLVED_WINDOW.toMillis());
    AgentLog log = app.agentLog();
    List<AgentLog.Line> warns =
        log.between(spawn, Instant.now(), l -> l.message().contains(CONNECT_FAILED));
    c.put("unresolvedWarns", warns);
    c.put("sessionsUnresolved", new AgentCounts(app.agentCounters()).get(AgentCounts.SESSIONS));
    Instant added = Instant.now();
    hosts.put(ChaosStack.COLLECTOR_NAME, "127.0.0.1");
    Duration connect = null;
    try {
      stack.awaitInstanceUp(CONNECT_TIMEOUT);
      connect = Duration.between(added, Instant.now());
    } catch (IllegalStateException e) {
      c.note("no connection after the name was added: " + e.getMessage());
    }
    c.put("connectAfterAdd", connect);
    ChaosSession.log(
        ID
            + ": ready in "
            + app.readyTime().toMillis()
            + " ms with the name absent; connected "
            + (connect == null ? "never" : connect.toSeconds() + " s")
            + " after it was added");
    return app;
  }

  @Override
  public void inject(ScenarioContext c) throws Exception {
    hosts.remove(ChaosStack.COLLECTOR_NAME);
    // The open connection does not look the name up again: cut it, so that the reconnects do.
    ToxiproxyClient t = c.stack().toxiproxy().client();
    t.setEnabled(Toxiproxy.PROXY, false);
    Thread.sleep(2_000);
    t.setEnabled(Toxiproxy.PROXY, true);
  }

  @Override
  public void remove(ScenarioContext c) {
    hosts.put(ChaosStack.COLLECTOR_NAME, "127.0.0.1");
  }

  @Override
  public List<Check> checks(ScenarioContext c) {
    List<Check> rows = new ArrayList<>();
    String invalid = Judge.invalidNote(c.options());
    boolean valid = c.options().valid();
    Duration ready = (Duration) c.get("readyUnresolved");
    Duration main = (Duration) c.get("readyMain");
    @SuppressWarnings("unchecked")
    List<AgentLog.Line> warns = (List<AgentLog.Line>) c.get("unresolvedWarns");
    OptionalLong sessions = (OptionalLong) c.get("sessionsUnresolved");
    boolean noSession = sessions == null || sessions.isEmpty() || sessions.getAsLong() == 0;
    rows.add(
        new Check(
            c.id(),
            "premain did not wait for the unresolvable collector name",
            "app ready, a connect WARN, no session while the name is absent",
            String.format(
                Locale.ROOT,
                "ready in %d ms; %d WARN (%s); sessions %s",
                ready.toMillis(),
                warns.size(),
                warns.isEmpty() ? "-" : warns.get(0).message(),
                sessions == null || sessions.isEmpty() ? "-" : sessions.getAsLong()),
            Check.Kind.INVARIANT,
            Judge.invariant(!warns.isEmpty() && noSession),
            "the first " + UNRESOLVED_WINDOW.toSeconds() + " s after the start"));
    rows.add(
        new Check(
            c.id(),
            "ready time with the name unresolvable",
            "<= main app's ready time + " + READY_MARGIN.toSeconds() + " s",
            String.format(Locale.ROOT, "%d ms (main app %d ms)", ready.toMillis(), main.toMillis()),
            Check.Kind.MEASUREMENT,
            Judge.measurement(valid, true, ready.compareTo(main.plus(READY_MARGIN)) <= 0),
            valid ? "" : invalid));
    Duration connect = (Duration) c.get("connectAfterAdd");
    rows.add(
        new Check(
            c.id(),
            "agent connected on its own after the name was added",
            "instance UP within " + CONNECT_TIMEOUT.toSeconds() + " s",
            connect == null ? "not connected" : connect.toMillis() / 1000.0 + " s",
            Check.Kind.INVARIANT,
            Judge.invariant(connect != null),
            "retries spaced by the back-off (<= 30 s)"));
    AgentLog log = c.stack().agentLog();
    List<AgentLog.Line> during =
        log.between(c.faultStart(), c.faultEnd(), l -> l.message().contains(CONNECT_FAILED));
    long minutes = Math.max(1, (c.faultLength().toSeconds() + 59) / 60);
    rows.add(
        new Check(
            c.id(),
            "connect WARN while the name is removed",
            ">= 1, at most one per minute (" + (minutes + 1) + " in the fault)",
            during.size() + (during.isEmpty() ? "" : ": " + during.get(0).message()),
            Check.Kind.INVARIANT,
            Judge.invariant(!during.isEmpty() && during.size() <= minutes + 1),
            "agent log records of the fault window"));
    rows.add(
        new Check(
            c.id(),
            "the failed lookup named in the WARN",
            "java.net.UnknownHostException",
            Boolean.toString(
                during.stream().anyMatch(l -> l.message().contains("UnknownHostException"))
                    || warns.stream().anyMatch(l -> l.message().contains("UnknownHostException"))),
            Check.Kind.INFO,
            Verdict.INFO,
            "root cause class of the gRPC status"));
    return rows;
  }

  @Override
  public void cleanup(ScenarioContext c) {
    if (app != null) {
      app.close();
    }
  }
}
