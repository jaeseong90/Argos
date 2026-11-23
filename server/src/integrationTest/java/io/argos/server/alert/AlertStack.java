/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.alert.api.AlertNotification;
import io.argos.server.alert.api.NotificationSink;
import io.argos.server.alert.engine.AlertEngine;
import io.argos.server.alert.metric.AlertSampleIntake;
import io.argos.server.session.SessionMonitor;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The real server on real PostgreSQL and ClickHouse for the alert engine tests (WO-25), optionally
 * on a {@link MutableClock}, with a recording {@link NotificationSink} next to the notifier (the
 * engine hands every notification to every sink), fake agents and direct reads of the alert tables.
 */
final class AlertStack implements AutoCloseable {

  final ServerTestEnvironment env;
  final MutableClock clock;
  final RecordingSink sink = new RecordingSink();
  ArgosTestServer server;

  private AlertStack(ServerTestEnvironment env, MutableClock clock) {
    this.env = env;
    this.clock = clock;
    this.server = startServer();
  }

  /** A stack on {@code clock} (null: the system clock). */
  static AlertStack start(MutableClock clock) {
    return new AlertStack(ServerTestEnvironment.start(), clock);
  }

  private ArgosTestServer startServer() {
    Consumer<SpringApplication> recording =
        app ->
            app.addInitializers(
                (ConfigurableApplicationContext c) ->
                    c.getBeanFactory().registerSingleton("recordingNotificationSink", sink));
    Consumer<SpringApplication> all =
        clock == null ? recording : clock.installer().andThen(recording);
    return ArgosTestServer.start(env, Map.of(), all);
  }

  /** Stops the server and starts a new one on the same databases. */
  void restartServer() {
    server.close();
    server = startServer();
  }

  AlertEngine engine() {
    return server.bean(AlertEngine.class);
  }

  /** A new application ({@code name}, prod) and an agent token of it. */
  AgentTokens.IssuedToken app(String name) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP)) {
      return AgentTokens.issue(c, AgentTokens.ensureApp(c, name, "prod"));
    }
  }

  /** A fake agent whose clock is the server's. */
  FakeAgent agent(AgentTokens.IssuedToken token, String app, String instance) {
    AgentClock agentClock =
        clock == null ? AgentClock.skewed(Duration.ZERO) : AgentClock.of(clock::millis, 0);
    return FakeAgent.builder(
            AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate()))
        .ownsChannel(true)
        .token(token.token())
        .appName(app)
        .instanceName(instance)
        .clock(agentClock)
        .callDeadline(Duration.ofSeconds(30))
        .build();
  }

  /** One instance state evaluation at the server clock's now. */
  void tick() {
    server.bean(SessionMonitor.class).tick();
  }

  /** XLog points the alert windows received. */
  long xlogPoints() {
    return server.bean(AlertSampleIntake.class).xlogPointsReceived();
  }

  /** Rows of a query with one parameter per {@code ?}. */
  List<List<Object>> rows(String sql, Object... args) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      List<List<Object>> out = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        int n = rs.getMetaData().getColumnCount();
        while (rs.next()) {
          List<Object> row = new ArrayList<>();
          for (int i = 1; i <= n; i++) {
            row.add(rs.getObject(i));
          }
          out.add(row);
        }
      }
      return out;
    }
  }

  /** The rule of a template part of an application. */
  UUID templateRule(int appId, String templateKey) throws SQLException {
    List<List<Object>> r =
        rows(
            "SELECT id FROM argos.alert_rules WHERE app_id = ? AND template_key = ?"
                + " AND deleted_at IS NULL",
            appId,
            templateKey);
    assertThat(r).as(templateKey + " of app " + appId).hasSize(1);
    return (UUID) r.get(0).get(0);
  }

  /** The {@code to_state} of the events of a rule, oldest first. */
  List<String> events(UUID ruleId) throws SQLException {
    List<String> out = new ArrayList<>();
    for (List<Object> row :
        rows(
            "SELECT to_state FROM argos.alert_events WHERE rule_id = ? ORDER BY ts, to_state",
            ruleId)) {
      out.add((String) row.get(0));
    }
    return out;
  }

  @Override
  public void close() {
    try {
      if (server != null) {
        server.close();
      }
    } finally {
      env.close();
    }
  }

  /** A sink that keeps every notification (next to the notifier). */
  static final class RecordingSink implements NotificationSink {
    final List<AlertNotification> received = new CopyOnWriteArrayList<>();

    @Override
    public void submit(AlertNotification notification) {
      received.add(notification);
    }
  }
}
