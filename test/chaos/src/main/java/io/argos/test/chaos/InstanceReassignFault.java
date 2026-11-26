/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * T-CH-008 "instance_id 재부여" (18 §11.1, "서버 DB 초기화 후 재기동"): the server is stopped, the PostgreSQL
 * database is dropped and created again empty ({@link ChaosStack#resetPostgres}; the roles of
 * {@code deploy/postgres/roles.sql} as the installer creates them, ClickHouse and the WAL kept),
 * and the server starts again (its start migrates the empty database). The application row and its
 * agent token are put back as an operator would (same app id: ClickHouse holds it; same token: the
 * running agent's {@code server.token-file}); instances are not, so the agent registers again.
 * toxiproxy keeps the agent away until that is done (a token unknown to the server would cost the
 * agent the fixed 5 minutes of C-102). Runs last: everything before it used the old database.
 *
 * <p>Extra rows: the agent opened a new session and its instance has a row again; the instance id
 * of that session is a new one (not the id of the earlier rows); the XLog API (what the UI draws)
 * names the earlier rows' instance as unknown (17 §12 "알 수 없는 인스턴스(id)").
 */
public final class InstanceReassignFault implements Fault {

  /** The scenario. */
  public static final String ID = "T-CH-008";

  @Override
  public String describe() {
    return "server stopped, PostgreSQL database recreated empty (ClickHouse and WAL kept), server"
        + " restarted and the app and token restored; instances not restored";
  }

  @Override
  public void inject(ScenarioContext c) throws Exception {
    ChaosStack stack = c.stack();
    c.put("oldInstance", stack.instanceId(stack.appId(), ChaosStack.MAIN_INSTANCE));
    stack.toxiproxy().client().setEnabled(Toxiproxy.PROXY, false);
    stack.stopServer();
    stack.resetPostgres();
  }

  @Override
  public void remove(ScenarioContext c) throws Exception {
    ChaosStack stack = c.stack();
    stack.startServer();
    stack.restoreMainRegistration();
    stack.toxiproxy().client().setEnabled(Toxiproxy.PROXY, true);
  }

  /** The restart takes about as long as the first start: the load runs twice that longer. */
  @Override
  public Duration removalAllowance(ChaosStack stack) {
    Duration twice = stack.lastServerStart().multipliedBy(2);
    return twice.compareTo(Duration.ofMinutes(1)) < 0 ? Duration.ofMinutes(1) : twice;
  }

  @Override
  public List<Check> checks(ScenarioContext c) {
    List<Check> rows = new ArrayList<>();
    ChaosStack stack = c.stack();
    int oldId = (Integer) c.get("oldInstance");
    int newId;
    try {
      newId = stack.instanceId(stack.appId(), ChaosStack.MAIN_INSTANCE);
    } catch (Exception e) {
      newId = -1;
      c.note("instance lookup failed: " + e);
    }
    OptionalLong sessions =
        AgentCounts.delta(c.countsBefore(), c.countsAfter(), AgentCounts.SESSIONS);
    rows.add(
        new Check(
            c.id(),
            "agent registered again after the reset (new session)",
            "sessions >= 1, an instances row for " + ChaosStack.MAIN_INSTANCE,
            "sessions "
                + (sessions.isPresent() ? sessions.getAsLong() : "-")
                + ", instance id "
                + (newId < 0 ? "none" : newId),
            Check.Kind.INVARIANT,
            Judge.invariant(sessions.isPresent() && sessions.getAsLong() >= 1 && newId > 0),
            "argos_agent_session_established_total; argos.instances"));
    rows.add(
        new Check(
            c.id(),
            "the new session's instance id is a new id",
            "!= the earlier id " + oldId,
            Integer.toString(newId),
            Check.Kind.INVARIANT,
            Judge.invariant(newId > 0 && newId != oldId),
            newId == oldId
                ? "instances.id is SERIAL: an empty database numbers from 1 again, so the earlier"
                    + " rows of id "
                    + oldId
                    + " are attributed to the new instance"
                : ""));
    InstanceView view = null;
    String error = null;
    Instant earlierEnd = c.faultStart().minusSeconds(5);
    try {
      view = InstanceView.of(stack.xlog(c.start(), earlierEnd));
    } catch (RuntimeException e) {
      error = e.getMessage();
    }
    rows.add(
        new Check(
            c.id(),
            "earlier rows shown as an unknown instance (XLog API)",
            "every earlier point's instance id absent from dictionary.instance",
            view == null
                ? "no answer: " + error
                : "point instances "
                    + view.pointInstances()
                    + ", named "
                    + view.named()
                    + ", unknown "
                    + view.unknown(),
            Check.Kind.INVARIANT,
            Judge.invariant(view != null && view.allUnknown()),
            "GET /api/v1/apps/{id}/xlog of the baseline window (before the reset)"));
    return rows;
  }
}
