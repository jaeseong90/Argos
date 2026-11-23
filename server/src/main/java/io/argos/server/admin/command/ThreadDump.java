/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.command;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A thread dump result parsed for {@code GET /commands/{commandId}/result} (14 §4.10: {@code
 * threads[]} of {@code id, name, state, virtual, locks_held[], waiting_on, frames[], tx_id?} and
 * {@code deadlocks[]}). The agent sends either JSON of that shape ({@code application/json}) or a
 * JDK-style text dump ({@code text/plain}); both are parsed defensively with bounds (the payload is
 * collected data from an agent, at most 1 MiB): at most {@value #MAX_THREADS} threads, {@value
 * #MAX_FRAMES} frames per thread (the agent's depth, 16 §1.4), strings cut at {@value
 * #MAX_TEXT_CHARS} characters. A text dump has no deadlock list.
 *
 * @param threads the threads in dump order
 * @param deadlocks the deadlock cycles the agent found
 * @param cut the parser dropped threads, frames or text beyond its bounds
 */
public record ThreadDump(List<ThreadInfo> threads, List<Deadlock> deadlocks, boolean cut) {

  /** Threads kept at most. */
  public static final int MAX_THREADS = 10_000;

  /** Frames kept per thread at most. */
  public static final int MAX_FRAMES = 64;

  /** Locks kept per thread at most. */
  public static final int MAX_LOCKS = 64;

  /** Characters of a name, frame or lock at most. */
  public static final int MAX_TEXT_CHARS = 1_024;

  /** Deadlock cycles kept at most, and threads per cycle. */
  public static final int MAX_DEADLOCKS = 100;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern HEADER = Pattern.compile("^\"(.*)\"(?:\\s+#(\\d{1,19}))?(.*)$");
  private static final Pattern STATE =
      Pattern.compile("^\\s*java\\.lang\\.Thread\\.State:\\s*(\\S+)");
  private static final Pattern TX_ID = Pattern.compile("[0-9]{1,20}");

  /** One thread. */
  public record ThreadInfo(
      @Nullable Long id,
      String name,
      @Nullable String state,
      boolean virtual,
      List<String> locksHeld,
      @Nullable String waitingOn,
      List<String> frames,
      @Nullable String txId) {}

  /** One deadlock cycle: the ids of its threads. */
  public record Deadlock(List<Long> threadIds) {}

  /** Parses a result by its content type. */
  public static ThreadDump parse(@Nullable String contentType, byte[] payload) {
    String text = new String(payload, StandardCharsets.UTF_8);
    if (contentType != null && contentType.startsWith("application/json")) {
      ThreadDump json = parseJson(text);
      if (json != null) {
        return json;
      }
    }
    return parseText(text);
  }

  /** The JSON form; null when it is not an object with a {@code threads} array. */
  static @Nullable ThreadDump parseJson(String text) {
    JsonNode root;
    try {
      root = JSON.readTree(text);
    } catch (RuntimeException e) {
      return null;
    }
    if (root == null || !root.isObject() || !root.path("threads").isArray()) {
      return null;
    }
    boolean[] cut = {false};
    List<ThreadInfo> threads = new ArrayList<>();
    for (JsonNode t : root.path("threads")) {
      if (threads.size() >= MAX_THREADS) {
        cut[0] = true;
        break;
      }
      if (!t.isObject()) {
        continue;
      }
      JsonNode id = t.path("id");
      JsonNode tx = t.path("tx_id");
      String txId = tx.isIntegralNumber() ? tx.asString() : tx.isString() ? tx.asString() : null;
      threads.add(
          new ThreadInfo(
              id.isIntegralNumber() && id.canConvertToLong() ? id.asLong() : null,
              cut(textOf(t.path("name")), cut, ""),
              cut(textOf(t.path("state")), cut, null),
              t.path("virtual").asBoolean(false),
              strings(t.path("locks_held"), MAX_LOCKS, cut),
              cut(textOf(t.path("waiting_on")), cut, null),
              strings(t.path("frames"), MAX_FRAMES, cut),
              txId != null && TX_ID.matcher(txId).matches() ? txId : null));
    }
    List<Deadlock> deadlocks = new ArrayList<>();
    for (JsonNode d : root.path("deadlocks")) {
      if (deadlocks.size() >= MAX_DEADLOCKS) {
        cut[0] = true;
        break;
      }
      JsonNode ids = d.isArray() ? d : d.path("thread_ids");
      List<Long> cycle = new ArrayList<>();
      for (JsonNode id : ids) {
        if (id.isIntegralNumber() && id.canConvertToLong() && cycle.size() < MAX_DEADLOCKS) {
          cycle.add(id.asLong());
        }
      }
      if (!cycle.isEmpty()) {
        deadlocks.add(new Deadlock(List.copyOf(cycle)));
      }
    }
    return new ThreadDump(List.copyOf(threads), List.copyOf(deadlocks), cut[0]);
  }

  /** The JDK text form ({@code jstack}, {@code Thread.print}). */
  static ThreadDump parseText(String text) {
    boolean[] cut = {false};
    List<ThreadInfo> threads = new ArrayList<>();
    Builder current = null;
    for (String raw : text.split("\n", -1)) {
      String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
      Matcher h = HEADER.matcher(line);
      if (h.matches()) {
        if (current != null && !add(threads, current.build(), cut)) {
          current = null;
          break;
        }
        current = new Builder();
        current.name = cut(h.group(1), cut, "");
        current.id = h.group(2) == null ? null : parseLong(h.group(2));
        current.virtual = h.group(3) != null && h.group(3).contains("virtual");
        continue;
      }
      if (current == null) {
        continue;
      }
      if (line.isBlank()) {
        if (!add(threads, current.build(), cut)) {
          current = null;
          break;
        }
        current = null;
        continue;
      }
      Matcher s = STATE.matcher(line);
      String trimmed = line.strip();
      if (s.find()) {
        current.state = cut(s.group(1), cut, null);
      } else if (trimmed.startsWith("at ")) {
        if (current.frames.size() < MAX_FRAMES) {
          current.frames.add(cut(trimmed.substring(3), cut, ""));
        } else {
          cut[0] = true;
        }
      } else if (trimmed.startsWith("- locked ")) {
        if (current.locks.size() < MAX_LOCKS) {
          current.locks.add(cut(trimmed.substring("- locked ".length()), cut, ""));
        } else {
          cut[0] = true;
        }
      } else if (trimmed.startsWith("- waiting to lock ")) {
        current.waitingOn = cut(trimmed.substring("- waiting to lock ".length()), cut, null);
      } else if (trimmed.startsWith("- waiting on ")) {
        current.waitingOn = cut(trimmed.substring("- waiting on ".length()), cut, null);
      } else if (trimmed.startsWith("- parking to wait for ")) {
        current.waitingOn = cut(trimmed.substring("- parking to wait for ".length()), cut, null);
      }
    }
    if (current != null) {
      add(threads, current.build(), cut);
    }
    return new ThreadDump(List.copyOf(threads), List.of(), cut[0]);
  }

  private static boolean add(List<ThreadInfo> threads, ThreadInfo t, boolean[] cut) {
    if (threads.size() >= MAX_THREADS) {
      cut[0] = true;
      return false;
    }
    threads.add(t);
    return true;
  }

  private static @Nullable Long parseLong(String digits) {
    try {
      return Long.parseLong(digits);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static @Nullable String textOf(JsonNode n) {
    return n.isString() ? n.asString() : null;
  }

  private static List<String> strings(JsonNode array, int max, boolean[] cut) {
    List<String> out = new ArrayList<>();
    if (!array.isArray()) {
      return out;
    }
    for (JsonNode n : array) {
      if (!n.isString()) {
        continue;
      }
      if (out.size() >= max) {
        cut[0] = true;
        break;
      }
      out.add(cut(n.asString(), cut, ""));
    }
    return List.copyOf(out);
  }

  private static String cut(@Nullable String s, boolean[] cut, @Nullable String whenNull) {
    if (s == null) {
      return whenNull;
    }
    if (s.length() > MAX_TEXT_CHARS) {
      cut[0] = true;
      return s.substring(0, MAX_TEXT_CHARS);
    }
    return s;
  }

  /** A thread being read from text. */
  private static final class Builder {
    private @Nullable Long id;
    private String name = "";
    private @Nullable String state;
    private boolean virtual;
    private final List<String> locks = new ArrayList<>();
    private @Nullable String waitingOn;
    private final List<String> frames = new ArrayList<>();

    ThreadInfo build() {
      return new ThreadInfo(
          id, name, state, virtual, List.copyOf(locks), waitingOn, List.copyOf(frames), null);
    }
  }
}
