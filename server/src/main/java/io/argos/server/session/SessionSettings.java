/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.proto.v1.Limits;
import io.argos.server.config.ArgosProperties;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The settings of the session start (11 §5, 12 §3.3, §4.4, §10), resolved from {@link
 * ArgosProperties} once.
 *
 * @param allowPlaintext {@code argos.collector.allow-plaintext} (11 §5 check 3)
 * @param autoRegister {@code argos.agent.auto-register.<env>}; {@code default} applies to other
 *     environments, and without it the default is {@code true} (12 §4.4)
 * @param maxSessionsPerToken {@code argos.collector.session.max-per-token}
 * @param newSessionsPerMinute {@code argos.collector.session.new-per-minute} (per token)
 * @param instancesPerApp {@code argos.collector.session.instances-per-app}
 * @param newInstancesPerHour {@code argos.collector.session.new-instances-per-hour} (per app)
 * @param heartbeatInterval {@code argos.collector.heartbeat-interval}, sent in {@code ServerHello}
 * @param limits {@code ServerHello.limits}
 * @param helloTimeout how long a stream may stay without {@code AgentHello} (11 §2: 10 s)
 */
public record SessionSettings(
    boolean allowPlaintext,
    Map<String, Boolean> autoRegister,
    int maxSessionsPerToken,
    int newSessionsPerMinute,
    int instancesPerApp,
    int newInstancesPerHour,
    Duration heartbeatInterval,
    Limits limits,
    Duration helloTimeout) {

  /** 11 §2, SEC-15: a connection must send its Hello within 10 s. */
  public static final Duration HELLO_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Heartbeat intervals without any agent message after which the server closes a session with
   * {@code IDLE_TIMEOUT} (11 open item 6, ADR-0002 C-19: 3 × 30 s = 90 s).
   */
  public static final int IDLE_HEARTBEATS = 3;

  /** The environment of a Hello with an empty {@code env} (11 §4.2). */
  public static final String DEFAULT_ENV = "default";

  /** Copies the map. */
  public SessionSettings {
    autoRegister = Map.copyOf(Objects.requireNonNull(autoRegister, "autoRegister"));
    Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
    Objects.requireNonNull(limits, "limits");
    Objects.requireNonNull(helloTimeout, "helloTimeout");
  }

  /** The settings of {@code properties} with {@code limits} as {@code ServerHello.limits}. */
  public static SessionSettings of(ArgosProperties properties, Limits limits) {
    ArgosProperties.Collector c = properties.collector();
    return new SessionSettings(
        c.allowPlaintext(),
        properties.agent().autoRegister(),
        c.session().maxPerToken(),
        c.session().newPerMinute(),
        c.session().instancesPerApp(),
        c.session().newInstancesPerHour(),
        c.heartbeatInterval(),
        limits,
        HELLO_TIMEOUT);
  }

  /**
   * Whether an unknown app of {@code env} is registered automatically by the environment default
   * (12 §4.4); an app's own policy wins over it (ADR-0010 C-231 (7), {@link HelloService}).
   */
  public boolean autoRegisters(String env) {
    return autoRegisters(autoRegister, env);
  }

  /**
   * {@link #autoRegisters(String)} of a map of {@code argos.agent.auto-register} ({@code default}
   * for other environments, true without it).
   */
  public static boolean autoRegisters(Map<String, Boolean> autoRegister, String env) {
    Boolean value = autoRegister.get(env);
    if (value == null) {
      value = autoRegister.get(env.toLowerCase(Locale.ROOT));
    }
    if (value == null) {
      value = autoRegister.get(DEFAULT_ENV);
    }
    return value == null || value;
  }
}
