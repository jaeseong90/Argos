/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.AgentEvent;
import io.argos.proto.v1.AgentEventType;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.InstanceUpdate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The running transport's session as other parts of the agent use it (WO-11 API for WO-12..WO-17):
 * whether a session is established, its {@code instance_id}, the app's {@code user_id} salt of
 * {@code capture.user-id.mode: hashed} (16 §6.3, memory only, never logged), and {@code
 * InstanceUpdate} (11 §4.2): the connector port once Tomcat knows it (WO-13), the use of virtual
 * threads (WO-15), a new {@code app.version} or configuration hash (WO-17), and {@code AgentEvent}
 * (kill switch, configuration reload; WO-17). {@link #current()} is null until the gRPC transport
 * started (test exporters, disabled transport).
 *
 * <p>What reaches the server, and when (11 §4.2):
 *
 * <ul>
 *   <li>While a session is established, a changed value goes out at once as one {@code
 *       InstanceUpdate} with that field only. Without a session that update is not sent and not
 *       kept.
 *   <li>Four of the values are also fields of {@code AgentHello}: {@code instance_name}, {@code
 *       virtual_threads}, {@code app_version} and {@code config_hash}. Each update method stores
 *       its value in the {@link AgentIdentity} first, so every later hello carries the latest
 *       value.
 *   <li>The connectors are not in the hello: they travel only as {@code InstanceUpdate} and are
 *       sent again each time a session is established (reconnect, new session after {@code
 *       SESSION_REQUIRED}).
 *   <li>A hello field that changes after a session's hello was written but before its {@code
 *       ServerHello} arrived is corrected when that session is established.
 * </ul>
 *
 * The last two points are one message: on every accepted {@code ServerHello} the session sends at
 * most one {@code InstanceUpdate} with the connectors (when any were reported) and the hello fields
 * whose current value differs from the hello that session sent ({@link #pendingOnEstablished});
 * with nothing to send it sends nothing.
 *
 * <p>Threads: every method may be called from any thread, application threads included (N-05): no
 * method blocks or touches the network. An update method compares its value with the last one it
 * sent and returns at once when it is the same; only a changed value posts one {@code
 * InstanceUpdate} to the session's event loop. So an advice that reports the same port or the same
 * virtual-thread use on every request adds nothing to the event loop's task queue. The correction
 * on establishment runs on the event loop and keeps no list of its own. Two threads racing with the
 * same new value, or a change racing with an establishment, may send a value twice, which the
 * server treats as one update.
 */
public final class AgentSession {
  /** Connector names one {@code InstanceUpdate} carries. */
  static final int MAX_CONNECTORS = 32;

  /** {@code AgentEvent.detail} cap (11 §4.2: at most 1 KiB). */
  static final int MAX_EVENT_DETAIL_BYTES = 1024;

  private static volatile AgentSession current;

  private final AgentIdentity identity;
  private final SessionClient session;
  private volatile byte[] userIdSalt = new byte[0];

  // The last value each update method sent (null: nothing sent yet).
  private volatile String lastInstanceName;
  private volatile Boolean lastVirtualThreads;
  private volatile String lastAppVersion;
  private volatile String lastConfigHash;
  private volatile List<String> lastConnectors;

  /**
   * A session API over {@code identity} (the {@code session}'s hello source) and {@code session};
   * registers {@link #pendingOnEstablished} as the session's {@link SessionClient.UpdateSource}.
   */
  AgentSession(AgentIdentity identity, SessionClient session) {
    this.identity = identity;
    this.session = session;
    session.updateSource(
        new SessionClient.UpdateSource() {
          @Override
          public InstanceUpdate pendingOnEstablished(AgentHello sent) {
            return AgentSession.this.pendingOnEstablished(sent);
          }
        });
  }

  /** The session of the running transport, or null. */
  public static AgentSession current() {
    return current;
  }

  static void install(AgentSession value) {
    current = value;
  }

  /** True while a session is established. Any thread, no blocking. */
  public boolean established() {
    return session.established();
  }

  /** {@code ServerHello.instance_id} (0 before the first session). Any thread, no blocking. */
  public long instanceId() {
    return session.instanceId();
  }

  /**
   * The host name of the hello ({@code AgentHello.host}, from the environment, never a name
   * lookup): the host part of the {@code host:port} instance name once Tomcat knows its port
   * (WO-13). Any thread, no blocking.
   */
  public String host() {
    return identity.host();
  }

  /** A copy of the app's {@code user_id} salt (empty unless the server sent one). Any thread. */
  public byte[] userIdSalt() {
    return userIdSalt.clone();
  }

  void userIdSalt(byte[] value) {
    userIdSalt = value == null ? new byte[0] : value.clone();
  }

  /**
   * The instance name becomes {@code host:port} once the connector port is known. Any thread, no
   * blocking or network; one {@code InstanceUpdate} only when the name changed.
   */
  public void instanceName(String name) {
    if (name == null || name.trim().isEmpty()) {
      return;
    }
    String value = Utf8.cut(name.trim(), ProtocolLimits.DEFAULT_MAX_STRING_BYTES);
    if (value.equals(lastInstanceName)) {
      return;
    }
    lastInstanceName = value;
    identity.instanceName(value);
    session.update(InstanceUpdate.newBuilder().setInstanceName(value).build());
  }

  /**
   * The application runs (some) transactions on virtual threads. Any thread, no blocking or
   * network; one {@code InstanceUpdate} only when the value changed.
   */
  public void virtualThreads(boolean used) {
    Boolean value = Boolean.valueOf(used);
    if (value.equals(lastVirtualThreads)) {
      return;
    }
    lastVirtualThreads = value;
    identity.virtualThreads(used);
    session.update(InstanceUpdate.newBuilder().setVirtualThreads(used).build());
  }

  /**
   * A new {@code app.version}. Any thread, no blocking or network; one {@code InstanceUpdate} only
   * when the version changed.
   */
  public void appVersion(String version) {
    String value = Utf8.cut(version, ProtocolLimits.DEFAULT_MAX_STRING_BYTES);
    if (value.equals(lastAppVersion)) {
      return;
    }
    lastAppVersion = value;
    identity.appVersion(value);
    session.update(InstanceUpdate.newBuilder().setAppVersion(value).build());
  }

  /**
   * A new effective configuration hash (token excluded). Any thread, no blocking or network; one
   * {@code InstanceUpdate} only when the hash changed.
   */
  public void configHash(String hash) {
    String value = Utf8.cut(hash, ProtocolLimits.DEFAULT_MAX_STRING_BYTES);
    if (value.equals(lastConfigHash)) {
      return;
    }
    lastConfigHash = value;
    identity.configHash(value);
    session.update(InstanceUpdate.newBuilder().setConfigHash(value).build());
  }

  /**
   * One {@code AgentEvent} (11 §4.2: kill switch on or off, configuration file reloaded, ...): sent
   * at once while a session is established, otherwise neither sent nor kept. {@code module} and
   * {@code detail} are constants or identifiers chosen by the caller (no collected data, no token);
   * {@code detail} is cut to 1 KiB. Any thread, no blocking or network.
   */
  public void event(AgentEventType type, String module, String detail) {
    session.event(
        AgentEvent.newBuilder()
            .setType(type)
            .setModule(
                module == null ? "" : Utf8.cut(module, ProtocolLimits.DEFAULT_MAX_STRING_BYTES))
            .setDetail(detail == null ? "" : Utf8.cut(detail, MAX_EVENT_DETAIL_BYTES))
            .setAtMs(System.currentTimeMillis())
            .build());
  }

  /**
   * The application's connectors ({@code http-nio-8080}, ...), at most {@value #MAX_CONNECTORS}.
   * Any thread, no blocking or network; one {@code InstanceUpdate} only when the list changed. The
   * hello has no connectors, so a non-empty list is also sent again on every session established
   * later ({@link #pendingOnEstablished}).
   */
  public void connectors(List<String> names) {
    if (names == null) {
      return;
    }
    List<String> value = new ArrayList<String>(Math.min(names.size(), MAX_CONNECTORS));
    for (String name : names) {
      if (value.size() >= MAX_CONNECTORS) {
        break;
      }
      if (name != null) {
        value.add(Utf8.cut(name, ProtocolLimits.DEFAULT_MAX_STRING_BYTES));
      }
    }
    if (value.equals(lastConnectors)) {
      return;
    }
    lastConnectors = Collections.unmodifiableList(value);
    session.update(InstanceUpdate.newBuilder().addAllConnectors(value).build());
  }

  /**
   * The {@code InstanceUpdate} a session needs once its {@code ServerHello} accepted it (11 §4.2;
   * {@code argos-io}, once per established session), or null when there is nothing to send:
   *
   * <ul>
   *   <li>the connectors last reported, unless none (null or empty): the hello never carries them;
   *   <li>each of {@code instance_name}, {@code virtual_threads}, {@code app_version} and {@code
   *       config_hash} whose current value ({@link AgentIdentity}) differs from {@code sent}, the
   *       hello this session actually sent: a value that changed after that hello was written,
   *       whose own update found no session.
   * </ul>
   *
   * Fields that did not change stay absent (C-4). Reads volatile fields only; never blocks.
   */
  InstanceUpdate pendingOnEstablished(AgentHello sent) {
    InstanceUpdate.Builder update = InstanceUpdate.newBuilder();
    boolean any = false;
    List<String> connectors = lastConnectors;
    if (connectors != null && !connectors.isEmpty()) {
      update.addAllConnectors(connectors);
      any = true;
    }
    String instanceName = identity.instanceName();
    if (!instanceName.equals(sent.getInstanceName())) {
      update.setInstanceName(instanceName);
      any = true;
    }
    boolean virtualThreads = identity.virtualThreads();
    if (virtualThreads != sent.getVirtualThreads()) {
      update.setVirtualThreads(virtualThreads);
      any = true;
    }
    String appVersion = identity.appVersion();
    if (!appVersion.equals(sent.getAppVersion())) {
      update.setAppVersion(appVersion);
      any = true;
    }
    String configHash = identity.configHash();
    if (!configHash.equals(sent.getConfigHash())) {
      update.setConfigHash(configHash);
      any = true;
    }
    return any ? update.build() : null;
  }
}
