/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.core.config.LoadedConfig;
import io.argos.agent.core.log.AgentLogger;
import io.argos.proto.v1.AgentEventType;
import io.argos.proto.v1.RejectedKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code CONFIG_RELOAD} (10 §12.1, 16 §5.5 step 7, WO-17): reads the local configuration again with
 * the inputs of the agent start ({@code argos-agent.yml}, the {@code -Dargos.*} and {@code ARGOS_*}
 * values captured at start) and applies it through {@link ConfigPushApplier#reload}: changed local
 * keys that the running agent can apply take effect (local keys are not hard-capped), the {@code
 * remote-config.allow} list is refreshed and the remote values are gated again against it. Keys
 * read only at start ({@code server.*}, {@code app.*}, {@code log.dir}, {@code agent.disabled},
 * {@code buffer.*}, ...) keep their running value and are listed in {@code restart_required}.
 *
 * <p>Payload ({@code application/json}): {@code {"applied_keys":[...], "rejected":[{"key", "code",
 * "message"}], "effective_config_hash", "restart_required":[...]}} with key names, reject codes,
 * constant messages and the hash only, never values. Afterwards one {@code AgentEvent
 * CONFIG_FILE_RELOADED}. The configuration's own warnings (unknown keys, invalid values) are logged
 * as at start.
 */
public final class ReloadCommand implements CommandHandler {
  /** Loads the local configuration again ({@code ConfigLoader.load} of the start inputs). */
  public interface Source {
    LoadedConfig load();
  }

  private final ConfigPushApplier applier;
  private final Source source;
  private final RuntimeApplier.Events events;

  public ReloadCommand(ConfigPushApplier applier, Source source, RuntimeApplier.Events events) {
    this.applier = Objects.requireNonNull(applier, "applier");
    this.source = Objects.requireNonNull(source, "source");
    this.events = events;
  }

  @Override
  public CommandOutput run(Map<String, String> args) {
    LoadedConfig fresh = source.load();
    reportConfiguration(fresh);
    ConfigPushApplier.Reloaded reloaded = applier.reload(fresh);
    if (events != null) {
      events.event(
          AgentEventType.CONFIG_FILE_RELOADED,
          "",
          "configuration file reloaded: "
              + reloaded.appliedKeys().size()
              + " key(s) applied, "
              + reloaded.restartRequired().size()
              + " restart required");
    }
    return CommandOutput.json(json(reloaded).getBytes(StandardCharsets.UTF_8), false);
  }

  /** The configuration's warnings and errors, as at start (key names and checks, no token). */
  private static void reportConfiguration(LoadedConfig fresh) {
    for (String warning : fresh.warnings()) {
      AgentLogger.warn("configuration reload: {}", warning);
    }
    for (String error : fresh.errors()) {
      AgentLogger.error("configuration reload: {}", error);
    }
  }

  /** The payload of {@code reloaded} (see the class comment). */
  static String json(ConfigPushApplier.Reloaded reloaded) {
    StringBuilder out = new StringBuilder(256);
    out.append("{\"applied_keys\":");
    strings(out, reloaded.appliedKeys());
    out.append(",\"rejected\":[");
    boolean first = true;
    for (RejectedKey key : reloaded.rejected()) {
      out.append(first ? "" : ",").append("{\"key\":");
      string(out, key.getKey());
      out.append(",\"code\":");
      string(out, key.getCode().name());
      out.append(",\"message\":");
      string(out, key.getMessage());
      out.append('}');
      first = false;
    }
    out.append("],\"effective_config_hash\":");
    string(out, reloaded.configHash());
    out.append(",\"restart_required\":");
    strings(out, reloaded.restartRequired());
    out.append('}');
    return out.toString();
  }

  private static void strings(StringBuilder out, List<String> values) {
    out.append('[');
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      string(out, values.get(i));
    }
    out.append(']');
  }

  /** An RFC 8259 string. */
  static void string(StringBuilder out, String value) {
    out.append('"');
    String text = value == null ? "" : value;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 0x20 || c == 0x2028 || c == 0x2029) {
            String hex = Integer.toHexString(c);
            out.append("\\u");
            for (int pad = hex.length(); pad < 4; pad++) {
              out.append('0');
            }
            out.append(hex);
          } else {
            out.append(c);
          }
      }
    }
    out.append('"');
  }
}
