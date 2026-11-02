/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

/** Minimal streaming JSON writer into a {@link StringBuilder} (no dependency, RFC 8259 strings). */
final class JsonWriter {
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private final StringBuilder out;
  private boolean needsComma;

  JsonWriter(StringBuilder out) {
    this.out = out;
  }

  JsonWriter beginObject() {
    separator();
    out.append('{');
    needsComma = false;
    return this;
  }

  JsonWriter endObject() {
    out.append('}');
    needsComma = true;
    return this;
  }

  JsonWriter beginArray(String name) {
    name(name);
    out.append('[');
    needsComma = false;
    return this;
  }

  JsonWriter endArray() {
    out.append(']');
    needsComma = true;
    return this;
  }

  JsonWriter field(String name, String value) {
    name(name);
    string(value);
    needsComma = true;
    return this;
  }

  JsonWriter field(String name, long value) {
    name(name);
    out.append(value);
    needsComma = true;
    return this;
  }

  JsonWriter field(String name, boolean value) {
    name(name);
    out.append(value);
    needsComma = true;
    return this;
  }

  /** Milliseconds with microsecond precision from nanoseconds ({@code -1} stays {@code -1}). */
  JsonWriter millis(String name, long nanos) {
    name(name);
    if (nanos < 0) {
      out.append(-1);
    } else {
      out.append(nanos / 1_000_000L).append('.');
      long micros = (nanos / 1_000L) % 1_000L;
      if (micros < 100) {
        out.append('0');
      }
      if (micros < 10) {
        out.append('0');
      }
      out.append(micros);
    }
    needsComma = true;
    return this;
  }

  /** An array element string. */
  JsonWriter value(String value) {
    separator();
    string(value);
    needsComma = true;
    return this;
  }

  private void name(String name) {
    separator();
    string(name);
    out.append(':');
  }

  private void separator() {
    if (needsComma) {
      out.append(',');
    }
  }

  private void string(String value) {
    if (value == null) {
      out.append("null");
      return;
    }
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
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
            out.append("\\u")
                .append(HEX[(c >> 12) & 0xF])
                .append(HEX[(c >> 8) & 0xF])
                .append(HEX[(c >> 4) & 0xF])
                .append(HEX[c & 0xF]);
          } else {
            out.append(c);
          }
      }
    }
    out.append('"');
  }
}
