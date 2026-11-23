/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The configuration the running agent uses (WO-17, 10 §12.2, ADR-0010 C-118 (3)): the local
 * configuration ({@code argos-agent.yml}, {@code -Dargos.*}, {@code ARGOS_*}) with the accepted
 * remote values on top. One immutable {@link State} published through a volatile field, so a reader
 * sees either the configuration before a change or the one after it, never a mix (DSN-11-08 partial
 * apply happens before the state is built).
 *
 * <ul>
 *   <li>{@link State#local()}: the local snapshot ({@code ConfigLoader}, or a {@code CONFIG_RELOAD}
 *       of the same inputs). Only it sets {@code remote-config.allow} ({@link State#allow()}), the
 *       hard cap of remote configuration.
 *   <li>{@link State#overlay()}: canonical key to the raw value of every remote value in force
 *       (accepted {@code ConfigPush} keys, the filtered {@code ServerHello.config}). Every key in
 *       it passed {@link RemoteConfigGate} against {@link State#allow()}.
 *   <li>{@link State#config()}: {@code local.withValues(overlay)}: a remote value wins over a local
 *       one for the allow-listed keys only (C-118 (3)); {@link State#effective()} shows such a key
 *       with source {@link EffectiveConfig.Source#REMOTE}; {@link State#configHash()} is {@link
 *       ConfigHash#of(AgentConfig)} of {@link State#config()}.
 * </ul>
 *
 * {@link #current()} is the instance of the running transport (null until the gRPC transport built
 * it, and in unit tests): {@code argos-metrics} reads its intervals from it.
 */
public final class RuntimeConfig {
  private static volatile RuntimeConfig current;

  private volatile State state;

  /** The running configuration of {@code loaded} without remote values. */
  public RuntimeConfig(LoadedConfig loaded) {
    Objects.requireNonNull(loaded, "loaded");
    this.state =
        State.of(
            loaded.config(),
            loaded.effective(),
            Collections.<String, String>emptyMap(),
            loaded.configHash());
  }

  /** The instance of the running transport, or null. */
  public static RuntimeConfig current() {
    return current;
  }

  /** Makes {@code value} the instance {@link #current()} returns. */
  public static void install(RuntimeConfig value) {
    current = value;
  }

  /** The configuration in force (never null). Any thread. */
  public State state() {
    return state;
  }

  /** Publishes {@code next} (the remote apply path, one thread at a time). */
  public void publish(State next) {
    state = Objects.requireNonNull(next, "next");
  }

  /** The configuration snapshot in force. Any thread. */
  public AgentConfig config() {
    return state.config();
  }

  /** The effective configuration with sources. Any thread. */
  public EffectiveConfig effective() {
    return state.effective();
  }

  /** The local {@code remote-config.allow}. Any thread. */
  public AllowList allow() {
    return state.allow();
  }

  /** The configuration hash of {@link #config()}. Any thread. */
  public String configHash() {
    return state.configHash();
  }

  /** One immutable configuration state (see the class comment). */
  public static final class State {
    private final AgentConfig local;
    private final EffectiveConfig localEffective;
    private final Map<String, String> overlay;
    private final AgentConfig config;
    private final EffectiveConfig effective;
    private final AllowList allow;
    private final String configHash;

    private State(
        AgentConfig local,
        EffectiveConfig localEffective,
        Map<String, String> overlay,
        AgentConfig config,
        EffectiveConfig effective,
        String configHash) {
      this.local = local;
      this.localEffective = localEffective;
      this.overlay = overlay;
      this.config = config;
      this.effective = effective;
      this.allow = AllowList.of(local.remoteConfigAllow());
      this.configHash = configHash;
    }

    /**
     * The state of {@code local} (with its effective values {@code localEffective}) and the remote
     * values {@code overlay} (canonical key to raw value; the caller gated them).
     */
    public static State of(
        AgentConfig local, EffectiveConfig localEffective, Map<String, String> overlay) {
      return of(local, localEffective, overlay, null);
    }

    private static State of(
        AgentConfig local,
        EffectiveConfig localEffective,
        Map<String, String> overlay,
        String knownHash) {
      Objects.requireNonNull(local, "local");
      Map<String, String> remote =
          Collections.unmodifiableMap(new LinkedHashMap<String, String>(overlay));
      EffectiveConfig baseEffective =
          localEffective == null
              ? new EffectiveConfig(Collections.<String, EffectiveConfig.Entry>emptyMap())
              : localEffective;
      AgentConfig merged = remote.isEmpty() ? local : local.withValues(remote);
      EffectiveConfig effective =
          remote.isEmpty() ? baseEffective : withRemote(baseEffective, remote);
      String hash = remote.isEmpty() && knownHash != null ? knownHash : ConfigHash.of(merged);
      return new State(local, baseEffective, remote, merged, effective, hash);
    }

    /** {@code base} with every key of {@code remote} as a {@code remote} entry. */
    static EffectiveConfig withRemote(EffectiveConfig base, Map<String, String> remote) {
      Map<String, EffectiveConfig.Entry> entries =
          new LinkedHashMap<String, EffectiveConfig.Entry>(base.entries());
      for (Map.Entry<String, String> entry : remote.entrySet()) {
        entries.put(
            entry.getKey(),
            new EffectiveConfig.Entry(entry.getValue(), EffectiveConfig.Source.REMOTE));
      }
      return new EffectiveConfig(entries);
    }

    /** The local snapshot (no remote value). */
    public AgentConfig local() {
      return local;
    }

    /** The effective values of {@link #local()} with their local sources. */
    public EffectiveConfig localEffective() {
      return localEffective;
    }

    /** The remote values in force: canonical key to raw value (unmodifiable). */
    public Map<String, String> overlay() {
      return overlay;
    }

    /** The snapshot in force: {@link #local()} with {@link #overlay()} on top. */
    public AgentConfig config() {
      return config;
    }

    /** The effective configuration of {@link #config()} with sources. */
    public EffectiveConfig effective() {
      return effective;
    }

    /** The local {@code remote-config.allow} (the hard cap). */
    public AllowList allow() {
      return allow;
    }

    /** {@link ConfigHash#of(AgentConfig)} of {@link #config()}. */
    public String configHash() {
      return configHash;
    }
  }
}
