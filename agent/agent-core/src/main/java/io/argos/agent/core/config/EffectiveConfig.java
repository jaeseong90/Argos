/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The effective configuration with the source of every value (02 §7 "유효 설정과 출처를 조회 가능", 10 §12.1
 * SELF_DIAG, {@code --check}): key to {@link Entry}{value, {@link Source}}. Values are the raw
 * strings that {@link AgentConfig} accepted, or the documented defaults; the token appears only as
 * {@value TokenHolder#MASK}. Immutable; built by {@code ConfigLoader} and by the remote apply path
 * (WO-17), read by self-diagnostics, the start banner and {@code ConfigHash}.
 */
public final class EffectiveConfig {
  /**
   * Where a value came from. The local sources are listed in their precedence, a later one wins (10
   * §15, 02 §7): {@link #DEFAULT} &lt; {@link #FILE} &lt; {@link #SYSTEM_PROPERTY} &lt; {@link
   * #ENVIRONMENT}. {@link #REMOTE} wins over every local source, for the keys of the local {@code
   * remote-config.allow} only (ADR-0010 C-118 (3); the allow list is the operator's hard cap, and a
   * {@code CONFIG_RELOAD} that drops a key from it drops the remote value too). {@code
   * RuntimeConfig} builds the remote entries (WO-17).
   */
  public enum Source {
    /** The documented default of 10 §15. */
    DEFAULT,
    /** {@code argos-agent.yml}. */
    FILE,
    /** {@code -Dargos.<key>}. */
    SYSTEM_PROPERTY,
    /** {@code ARGOS_<KEY>} (and {@code ARGOS_TOKEN}). */
    ENVIRONMENT,
    /** A {@code ConfigPush} accepted by {@code RemoteConfigGate}. */
    REMOTE;

    /**
     * The short name used in self-diagnostics and {@code --check}: {@code default}, {@code file},
     * {@code sysprop}, {@code env}, {@code remote}.
     */
    public String label() {
      switch (this) {
        case SYSTEM_PROPERTY:
          return "sysprop";
        case ENVIRONMENT:
          return "env";
        default:
          return name().toLowerCase(Locale.ROOT);
      }
    }
  }

  /** One effective value. */
  public static final class Entry {
    private final String value;
    private final Source source;

    public Entry(String value, Source source) {
      this.value = value;
      this.source = source;
    }

    /** The raw value ({@value TokenHolder#MASK} for the token), or null when unset. */
    public String value() {
      return value;
    }

    public Source source() {
      return source;
    }

    @Override
    public String toString() {
      return value + " (" + source + ")";
    }
  }

  private final Map<String, Entry> entries;

  /**
   * The effective configuration of {@code entries} (canonical key to entry; copied, order kept).
   */
  public EffectiveConfig(Map<String, Entry> entries) {
    this.entries = Collections.unmodifiableMap(new LinkedHashMap<String, Entry>(entries));
  }

  /** Every key with its entry, in registry order as given. */
  public Map<String, Entry> entries() {
    return entries;
  }

  /** The value of {@code key}, or null. */
  public String value(String key) {
    Entry entry = entries.get(key);
    return entry == null ? null : entry.value();
  }

  /** The source of {@code key}, {@link Source#DEFAULT} when it has no entry. */
  public Source source(String key) {
    Entry entry = entries.get(key);
    return entry == null ? Source.DEFAULT : entry.source();
  }

  /**
   * One line per key, {@code key = value (source)} in entry order, {@code -} for no value: the text
   * form of self-diagnostics and {@code --check}. The token is {@value TokenHolder#MASK}.
   */
  public List<String> lines() {
    List<String> lines = new ArrayList<String>(entries.size());
    for (Map.Entry<String, Entry> entry : entries.entrySet()) {
      String value = entry.getValue().value();
      lines.add(
          entry.getKey()
              + " = "
              + (value == null ? "-" : value)
              + " ("
              + entry.getValue().source().label()
              + ")");
    }
    return lines;
  }
}
