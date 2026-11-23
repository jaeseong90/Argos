/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the JSON thread dump of {@code HotSpotDiagnosticMXBean.dumpThreads(path, JSON)} (JDK 21+,
 * 10 §12.1, 10 U-2) without a JSON dependency and within bounds: at most {@value #MAX_INPUT_CHARS}
 * characters of input, nesting depth {@value #MAX_DEPTH}, strings kept up to {@value
 * #MAX_STRING_CHARS} characters and arrays up to {@value #MAX_ARRAY_ITEMS} items (the rest is read
 * and dropped), and at most {@value #MAX_THREADS} threads. Input over a bound or malformed input
 * ends the read with an {@link IOException} (the caller then reports the dump without virtual
 * threads); it never loops or grows without bound.
 *
 * <p>Layout (JDK 21 and 25): {@code {"threadDump":{"threadContainers":[{"container":...,
 * "threads":[{"tid":"1","name":"main","stack":["..."]}, ...]}]}}}. JDK 25 adds per thread {@code
 * "state"}, {@code "virtual":true} (virtual threads only), {@code "waitingOn"}, {@code
 * "blockedOn"}, {@code "parkBlocker":{"object":...}} and {@code "monitorsOwned":[{"depth":n,
 * "locks":["..."]}]}; JDK 21 has none of them. Each thread object is converted as soon as it is
 * read, so the reader holds one thread object at a time, not the document.
 */
final class JdkThreadDumpReader {
  /** Input characters at most (16 MiB). */
  static final int MAX_INPUT_CHARS = 16 * 1024 * 1024;

  /** Nesting depth at most. */
  static final int MAX_DEPTH = 32;

  /** Characters of one string kept (longer strings are read and cut). */
  static final int MAX_STRING_CHARS = 4_096;

  /** Items of one array kept (later items are read and dropped). */
  static final int MAX_ARRAY_ITEMS = 256;

  /** Threads read at most; later threads are dropped and the result is marked truncated. */
  static final int MAX_THREADS = 200_000;

  /**
   * Characters of names, states, frames and locks kept over all threads (about 8 MiB of heap; the
   * result is cut at 1 MiB anyway): later threads are dropped and the result is marked truncated.
   */
  static final long MAX_RETAINED_CHARS = 4L * 1024 * 1024;

  /** One thread of the dump. */
  static final class DumpedThread {
    final long tid;
    final String name;
    final String state;

    /** {@code "virtual"} of JDK 25: TRUE, or null when the dump does not say (JDK 21). */
    final Boolean virtual;

    final List<String> stack;
    final String waitingOn;
    final List<String> locks;

    DumpedThread(
        long tid,
        String name,
        String state,
        Boolean virtual,
        List<String> stack,
        String waitingOn,
        List<String> locks) {
      this.tid = tid;
      this.name = name;
      this.state = state;
      this.virtual = virtual;
      this.stack = stack;
      this.waitingOn = waitingOn;
      this.locks = locks;
    }
  }

  /** The threads read and whether threads were dropped. */
  static final class Result {
    final List<DumpedThread> threads;
    final boolean truncated;

    Result(List<DumpedThread> threads, boolean truncated) {
      this.threads = threads;
      this.truncated = truncated;
    }
  }

  private final Reader in;
  private final int maxInputChars;
  private final long maxRetainedChars;
  private final List<DumpedThread> threads = new ArrayList<DumpedThread>();
  private boolean truncated;
  private int consumed;
  private long retainedChars;
  private int peeked = -2;

  private JdkThreadDumpReader(Reader in, int maxInputChars, long maxRetainedChars) {
    this.in = in;
    this.maxInputChars = maxInputChars;
    this.maxRetainedChars = maxRetainedChars;
  }

  /**
   * Reads every thread of the dump on {@code in}; {@code in} is not closed. Input longer than
   * {@value #MAX_INPUT_CHARS} characters ends the read: the threads read so far are returned,
   * marked truncated.
   */
  static Result read(Reader in) throws IOException {
    return read(in, MAX_INPUT_CHARS);
  }

  /** {@link #read(Reader)} with an input bound of {@code maxInputChars} (tests). */
  static Result read(Reader in, int maxInputChars) throws IOException {
    return read(in, maxInputChars, MAX_RETAINED_CHARS);
  }

  /** {@link #read(Reader)} with an input and a retained-characters bound (tests). */
  static Result read(Reader in, int maxInputChars, long maxRetainedChars) throws IOException {
    JdkThreadDumpReader reader = new JdkThreadDumpReader(in, maxInputChars, maxRetainedChars);
    try {
      reader.skipWhitespace();
      reader.value(null, 0);
      reader.skipWhitespace();
      if (reader.peek() != -1) {
        throw new IOException("thread dump: trailing data");
      }
    } catch (InputLimitException e) {
      reader.truncated = true;
    }
    return new Result(Collections.unmodifiableList(reader.threads), reader.truncated);
  }

  /** The input is longer than {@link #MAX_INPUT_CHARS}. */
  static final class InputLimitException extends IOException {
    private static final long serialVersionUID = 1L;

    InputLimitException() {
      super("thread dump: input over the character bound");
    }
  }

  // --- Values ------------------------------------------------------------------------------

  /**
   * Reads one value. {@code key} is the member name the value belongs to: the items of an array
   * named {@code threads} are converted to {@link DumpedThread}s and not kept.
   */
  private Object value(String key, int depth) throws IOException {
    if (depth > MAX_DEPTH) {
      throw new IOException("thread dump: nesting deeper than " + MAX_DEPTH);
    }
    int c = peek();
    switch (c) {
      case '{':
        return object(depth);
      case '[':
        return array("threads".equals(key), depth);
      case '"':
        return string();
      case 't':
        literal("true");
        return true;
      case 'f':
        literal("false");
        return false;
      case 'n':
        literal("null");
        return null;
      default:
        if (c == '-' || (c >= '0' && c <= '9')) {
          return number();
        }
        throw new IOException("thread dump: unexpected character");
    }
  }

  private Map<String, Object> object(int depth) throws IOException {
    expect('{');
    Map<String, Object> members = new LinkedHashMap<String, Object>();
    skipWhitespace();
    if (peek() == '}') {
      next();
      return members;
    }
    while (true) {
      skipWhitespace();
      String name = string();
      skipWhitespace();
      expect(':');
      skipWhitespace();
      Object member = value(name, depth + 1);
      if (members.size() < MAX_ARRAY_ITEMS) {
        members.put(name, member);
      }
      skipWhitespace();
      int c = next();
      if (c == '}') {
        return members;
      }
      if (c != ',') {
        throw new IOException("thread dump: expected , or }");
      }
    }
  }

  private List<Object> array(boolean threadArray, int depth) throws IOException {
    expect('[');
    List<Object> items = new ArrayList<Object>();
    skipWhitespace();
    if (peek() == ']') {
      next();
      return items;
    }
    while (true) {
      skipWhitespace();
      Object item = value(null, depth + 1);
      if (threadArray) {
        if (item instanceof Map) {
          thread((Map<?, ?>) item);
        }
      } else if (items.size() < MAX_ARRAY_ITEMS) {
        items.add(item);
      }
      skipWhitespace();
      int c = next();
      if (c == ']') {
        return items;
      }
      if (c != ',') {
        throw new IOException("thread dump: expected , or ]");
      }
    }
  }

  private String string() throws IOException {
    expect('"');
    StringBuilder out = new StringBuilder(32);
    while (true) {
      int c = next();
      if (c == -1) {
        throw new IOException("thread dump: unterminated string");
      }
      if (c == '"') {
        return out.toString();
      }
      char decoded;
      if (c == '\\') {
        int e = next();
        switch (e) {
          case '"':
          case '\\':
          case '/':
            decoded = (char) e;
            break;
          case 'b':
            decoded = '\b';
            break;
          case 'f':
            decoded = '\f';
            break;
          case 'n':
            decoded = '\n';
            break;
          case 'r':
            decoded = '\r';
            break;
          case 't':
            decoded = '\t';
            break;
          case 'u':
            int code = 0;
            for (int i = 0; i < 4; i++) {
              int h = Character.digit(next(), 16);
              if (h < 0) {
                throw new IOException("thread dump: bad \\u escape");
              }
              code = code * 16 + h;
            }
            decoded = (char) code;
            break;
          default:
            throw new IOException("thread dump: bad escape");
        }
      } else if (c < 0x20) {
        throw new IOException("thread dump: control character in string");
      } else {
        decoded = (char) c;
      }
      if (out.length() < MAX_STRING_CHARS) {
        out.append(decoded);
      }
    }
  }

  private Object number() throws IOException {
    StringBuilder digits = new StringBuilder(20);
    while (true) {
      int c = peek();
      if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
        if (digits.length() >= 64) {
          throw new IOException("thread dump: number too long");
        }
        digits.append((char) next());
      } else {
        break;
      }
    }
    String text = digits.toString();
    try {
      if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
        return Long.valueOf(text);
      }
      return Double.valueOf(text);
    } catch (NumberFormatException e) {
      throw new IOException("thread dump: bad number", e);
    }
  }

  private void literal(String word) throws IOException {
    for (int i = 0; i < word.length(); i++) {
      if (next() != word.charAt(i)) {
        throw new IOException("thread dump: bad literal");
      }
    }
  }

  // --- Threads -----------------------------------------------------------------------------

  private void thread(Map<?, ?> t) {
    Long tid = id(t.get("tid"));
    if (tid == null) {
      return;
    }
    if (threads.size() >= MAX_THREADS) {
      truncated = true;
      return;
    }
    Object virtual = t.get("virtual");
    String waitingOn = text(t.get("waitingOn"));
    if (waitingOn == null) {
      waitingOn = text(t.get("blockedOn"));
    }
    if (waitingOn == null && t.get("parkBlocker") instanceof Map) {
      waitingOn = text(((Map<?, ?>) t.get("parkBlocker")).get("object"));
    }
    List<String> locks = new ArrayList<String>();
    if (t.get("monitorsOwned") instanceof List) {
      for (Object owned : (List<?>) t.get("monitorsOwned")) {
        if (owned instanceof Map && ((Map<?, ?>) owned).get("locks") instanceof List) {
          for (Object lock : (List<?>) ((Map<?, ?>) owned).get("locks")) {
            String name = text(lock);
            if (name != null && locks.size() < ThreadDumpJson.MAX_LOCKS) {
              locks.add(name);
            }
          }
        }
      }
    }
    DumpedThread thread =
        new DumpedThread(
            tid,
            text(t.get("name")),
            text(t.get("state")),
            Boolean.TRUE.equals(virtual) ? Boolean.TRUE : null,
            strings(t.get("stack"), ThreadDumpJson.MAX_FRAMES),
            waitingOn,
            locks);
    long size = 64L + length(thread.name) + length(thread.state) + length(thread.waitingOn);
    for (String frame : thread.stack) {
      size += length(frame) + 16;
    }
    for (String lock : thread.locks) {
      size += length(lock) + 16;
    }
    if (retainedChars + size > maxRetainedChars) {
      truncated = true;
      return;
    }
    retainedChars += size;
    threads.add(thread);
  }

  private static int length(String value) {
    return value == null ? 0 : value.length();
  }

  private static Long id(Object value) {
    if (value instanceof Long) {
      return (Long) value;
    }
    if (value instanceof String) {
      try {
        return Long.valueOf((String) value);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private static String text(Object value) {
    return value instanceof String ? (String) value : null;
  }

  private static List<String> strings(Object value, int max) {
    if (!(value instanceof List)) {
      return new ArrayList<String>(0);
    }
    List<String> out = new ArrayList<String>();
    for (Object item : (List<?>) value) {
      if (item instanceof String && out.size() < max) {
        out.add((String) item);
      }
    }
    return out;
  }

  // --- Characters --------------------------------------------------------------------------

  private int peek() throws IOException {
    if (peeked == -2) {
      peeked = readChar();
    }
    return peeked;
  }

  private int next() throws IOException {
    int c = peek();
    peeked = -2;
    return c;
  }

  private int readChar() throws IOException {
    int c = in.read();
    if (c != -1 && ++consumed > maxInputChars) {
      throw new InputLimitException();
    }
    return c;
  }

  private void expect(char c) throws IOException {
    if (next() != c) {
      throw new IOException("thread dump: expected " + c);
    }
  }

  private void skipWhitespace() throws IOException {
    while (true) {
      int c = peek();
      if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
        next();
      } else {
        return;
      }
    }
  }
}
