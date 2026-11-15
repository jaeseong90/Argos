/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of {@link ConfigLoader#load(ConfigInputs)}: the typed snapshot, the effective values with
 * their sources, the token chosen from the three ways of SEC-06, and the lines to log once at
 * start: {@link #warnings()} (unknown keys, invalid values, plaintext token, ignored test keys,
 * missing {@code app.env}), {@link #errors()} (missing required keys) and {@link #notes()} (facts
 * that are not problems, such as a token file permission check that this file system cannot do).
 */
public final class LoadedConfig {
  private final AgentConfig config;
  private final EffectiveConfig effective;
  private final TokenHolder token;
  private final List<String> warnings;
  private final List<String> errors;
  private final List<String> notes;
  private volatile String configHash;

  public LoadedConfig(
      AgentConfig config, EffectiveConfig effective, TokenHolder token, List<String> warnings) {
    this(
        config,
        effective,
        token,
        warnings,
        Collections.<String>emptyList(),
        Collections.<String>emptyList());
  }

  public LoadedConfig(
      AgentConfig config,
      EffectiveConfig effective,
      TokenHolder token,
      List<String> warnings,
      List<String> errors,
      List<String> notes) {
    this.config = config;
    this.effective = effective;
    this.token = token == null ? TokenHolder.NONE : token;
    this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    this.errors = Collections.unmodifiableList(new ArrayList<String>(errors));
    this.notes = Collections.unmodifiableList(new ArrayList<String>(notes));
  }

  public AgentConfig config() {
    return config;
  }

  public EffectiveConfig effective() {
    return effective;
  }

  /** The effective token (16 §2.6: {@code server.token} > {@code server.token-file} > env). */
  public TokenHolder token() {
    return token;
  }

  /** Warnings to log once as WARN; they never contain the token (SEC-06). */
  public List<String> warnings() {
    return warnings;
  }

  /**
   * Lines to log once as ERROR: a missing {@code server.address} (no transport; not reported when a
   * development build selects {@code test.exporter}) and a missing {@code app.name} (the fallback
   * name of {@link ConfigLoader#fallbackAppName(String)} is used). The application and the
   * instrumentation keep running (F-A15).
   */
  public List<String> errors() {
    return errors;
  }

  /** Lines to log once as INFO; never collected data and never the token. */
  public List<String> notes() {
    return notes;
  }

  /**
   * True when {@code server.address} is set, so the transport (WO-11) may start; false means the
   * agent sends nothing (a development build may still use {@code test.exporter}).
   */
  public boolean transportConfigured() {
    return config.serverAddress() != null;
  }

  /** {@link ConfigHash#of(AgentConfig)} of {@link #config()}: 16 hex digits, token excluded. */
  public String configHash() {
    String hash = configHash;
    if (hash == null) {
      hash = ConfigHash.of(config);
      configHash = hash;
    }
    return hash;
  }
}
