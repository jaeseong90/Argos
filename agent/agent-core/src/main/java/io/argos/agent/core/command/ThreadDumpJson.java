/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The JSON form of {@code THREAD_DUMP} and {@code THREAD_STACK} results, the shape the server
 * parses ({@code ThreadDump.parseJson}, 14 §4.10):
 *
 * <pre>
 * {"threads":[{"id":1,"name":"main","state":"RUNNABLE","virtual":false,"locks_held":["..."],
 *              "waiting_on":"...","frames":["..."],"tx_id":"123"}],
 *  "deadlocks":[{"thread_ids":[12,13]}],
 *  "truncated":false, "virtual_threads_source":"dumpThreads"}
 * </pre>
 *
 * <p>{@code tx_id} is an unsigned decimal string (a 64-bit id does not survive a JSON number in
 * JavaScript), absent when the thread runs no transaction; {@code waiting_on} is absent when the
 * thread waits on nothing. At most {@value #MAX_FRAMES} frames and {@value #MAX_LOCKS} locks per
 * thread, strings cut at {@value #MAX_TEXT_CHARS} characters (the server's bounds). The writer cuts
 * whole threads at the byte cap: threads that carry a {@code tx_id} are kept first, the order of
 * the kept threads is the given order.
 *
 * <p>Thread names and frames are collected data: they go into the result only, never into the agent
 * log.
 */
final class ThreadDumpJson {
  /** Frames per thread at most (16 §1.4, the server's {@code MAX_FRAMES}). */
  static final int MAX_FRAMES = 64;

  /** Locks per thread at most. */
  static final int MAX_LOCKS = 64;

  /** Characters of one name, frame or lock at most. */
  static final int MAX_TEXT_CHARS = 1_024;

  /** Deadlock cycles at most, and threads per cycle. */
  static final int MAX_DEADLOCKS = 100;

  /** Bytes reserved for the closing members ({@code deadlocks}, {@code truncated}, source). */
  private static final int TAIL_RESERVE = 256;

  /** One thread of a dump. */
  static final class ThreadEntry {
    final long id;
    final String name;
    final String state;
    final boolean virtual;
    final List<String> locksHeld;
    final String waitingOn;
    final List<String> frames;
    final boolean hasTx;
    final long txId;

    ThreadEntry(
        long id,
        String name,
        String state,
        boolean virtual,
        List<String> locksHeld,
        String waitingOn,
        List<String> frames,
        boolean hasTx,
        long txId) {
      this.id = id;
      this.name = name == null ? "" : name;
      this.state = state;
      this.virtual = virtual;
      this.locksHeld = cap(locksHeld, MAX_LOCKS);
      this.waitingOn = waitingOn;
      this.frames = cap(frames, MAX_FRAMES);
      this.hasTx = hasTx;
      this.txId = txId;
    }

    /** This entry carrying {@code txId}. */
    ThreadEntry withTx(long tx) {
      return new ThreadEntry(id, name, state, virtual, locksHeld, waitingOn, frames, true, tx);
    }

    private static List<String> cap(List<String> values, int max) {
      if (values == null || values.isEmpty()) {
        return Collections.emptyList();
      }
      return values.size() <= max
          ? values
          : Collections.unmodifiableList(new ArrayList<String>(values.subList(0, max)));
    }
  }

  /** Encoded payload and whether threads were cut. */
  static final class Encoded {
    final byte[] utf8;
    final boolean truncated;
    final int threads;

    Encoded(byte[] utf8, boolean truncated, int threads) {
      this.utf8 = utf8;
      this.truncated = truncated;
      this.threads = threads;
    }
  }

  private ThreadDumpJson() {}

  /**
   * Encodes {@code threads} and {@code deadlocks} (cycles of thread ids) within {@code maxBytes}
   * UTF-8 bytes. {@code source}, when not null, is written as {@code virtual_threads_source}.
   * {@code alreadyTruncated} marks a dump that was cut before encoding.
   */
  static Encoded encode(
      List<ThreadEntry> threads,
      List<long[]> deadlocks,
      String source,
      boolean alreadyTruncated,
      int maxBytes) {
    int n = threads.size();
    byte[][] parts = new byte[n][];
    for (int i = 0; i < n; i++) {
      StringBuilder one = new StringBuilder(256);
      thread(one, threads.get(i));
      parts[i] = one.toString().getBytes(StandardCharsets.UTF_8);
    }
    StringBuilder tail = new StringBuilder(64);
    tail.append("],\"deadlocks\":[");
    int cycles = 0;
    for (long[] cycle : deadlocks) {
      if (cycles >= MAX_DEADLOCKS) {
        break;
      }
      tail.append(cycles == 0 ? "" : ",").append("{\"thread_ids\":[");
      for (int i = 0; i < cycle.length && i < MAX_DEADLOCKS; i++) {
        tail.append(i == 0 ? "" : ",").append(cycle[i]);
      }
      tail.append("]}");
      cycles++;
    }
    tail.append(']');
    if (source != null) {
      tail.append(",\"virtual_threads_source\":");
      string(tail, source);
    }
    byte[] tailBytes = tail.toString().getBytes(StandardCharsets.UTF_8);
    String head = "{\"threads\":[";
    long budget =
        (long) maxBytes - head.length() - tailBytes.length - TAIL_RESERVE - 2 /* "}" and margin */;
    boolean[] keep = new boolean[n];
    long used = 0;
    boolean cut = alreadyTruncated;
    // Threads running a transaction first, then the rest in order.
    for (int pass = 0; pass < 2; pass++) {
      for (int i = 0; i < n; i++) {
        boolean tx = threads.get(i).hasTx;
        if ((pass == 0) != tx || keep[i]) {
          continue;
        }
        long need = parts[i].length + 1L;
        if (used + need <= budget) {
          keep[i] = true;
          used += need;
        } else {
          cut = true;
        }
      }
    }
    StringBuilder end = new StringBuilder(32);
    end.append(",\"truncated\":").append(cut).append('}');
    byte[] endBytes = end.toString().getBytes(StandardCharsets.UTF_8);
    int size = head.length() + (int) used + tailBytes.length + endBytes.length;
    byte[] out = new byte[size];
    int pos = 0;
    byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
    System.arraycopy(headBytes, 0, out, pos, headBytes.length);
    pos += headBytes.length;
    boolean first = true;
    int kept = 0;
    for (int i = 0; i < n; i++) {
      if (!keep[i]) {
        continue;
      }
      if (!first) {
        out[pos++] = ',';
      }
      System.arraycopy(parts[i], 0, out, pos, parts[i].length);
      pos += parts[i].length;
      first = false;
      kept++;
    }
    System.arraycopy(tailBytes, 0, out, pos, tailBytes.length);
    pos += tailBytes.length;
    System.arraycopy(endBytes, 0, out, pos, endBytes.length);
    pos += endBytes.length;
    if (pos != size) {
      byte[] exact = new byte[pos];
      System.arraycopy(out, 0, exact, 0, pos);
      out = exact;
    }
    return new Encoded(out, cut, kept);
  }

  /** One thread object. */
  static void thread(StringBuilder out, ThreadEntry t) {
    out.append("{\"id\":").append(t.id).append(",\"name\":");
    string(out, cut(t.name));
    if (t.state != null) {
      out.append(",\"state\":");
      string(out, cut(t.state));
    }
    out.append(",\"virtual\":").append(t.virtual);
    out.append(",\"locks_held\":[");
    for (int i = 0; i < t.locksHeld.size(); i++) {
      out.append(i == 0 ? "" : ",");
      string(out, cut(t.locksHeld.get(i)));
    }
    out.append(']');
    if (t.waitingOn != null) {
      out.append(",\"waiting_on\":");
      string(out, cut(t.waitingOn));
    }
    out.append(",\"frames\":[");
    for (int i = 0; i < t.frames.size(); i++) {
      out.append(i == 0 ? "" : ",");
      string(out, cut(t.frames.get(i)));
    }
    out.append(']');
    if (t.hasTx) {
      out.append(",\"tx_id\":\"").append(Long.toUnsignedString(t.txId)).append('"');
    }
    out.append('}');
  }

  private static String cut(String value) {
    if (value == null || value.length() <= MAX_TEXT_CHARS) {
      return value;
    }
    int end = MAX_TEXT_CHARS;
    if (Character.isHighSurrogate(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(0, end);
  }

  /** A JSON string (RFC 8259 escaping; lone surrogates become U+FFFD). */
  static void string(StringBuilder out, String value) {
    if (value == null) {
      out.append("null");
      return;
    }
    out.append('"');
    int length = value.length();
    for (int i = 0; i < length; i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        out.append('\\').append(c);
      } else if (c == '\n') {
        out.append("\\n");
      } else if (c == '\r') {
        out.append("\\r");
      } else if (c == '\t') {
        out.append("\\t");
      } else if (c < 0x20 || c == 0x2028 || c == 0x2029) {
        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
      } else if (Character.isHighSurrogate(c)) {
        if (i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
          out.append(c).append(value.charAt(i + 1));
          i++;
        } else {
          out.append('�');
        }
      } else if (Character.isLowSurrogate(c)) {
        out.append('�');
      } else {
        out.append(c);
      }
    }
    out.append('"');
  }
}
