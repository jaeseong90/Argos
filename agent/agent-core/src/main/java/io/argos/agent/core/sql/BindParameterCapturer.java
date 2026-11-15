/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.SqlState;
import io.argos.agent.core.masking.Masker;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

/**
 * Bind parameter capture (F-A04, SEC-3A, 16 §6.7, 10 §6.3) for the three modes of {@code
 * capture.sql-parameters}:
 *
 * <ul>
 *   <li>{@code masked} (default): nothing; the {@code set*} advice returns before it calls the
 *       core.
 *   <li>{@code partial}: the value of a position whose column is in {@code
 *       capture.sql-parameters.whitelist-columns}, after the {@link Masker}; any other position its
 *       type and length ({@code [String(11)]}). A position has a column only when the statement
 *       reads {@code <column> = ?} there ({@link #whitelistMask}); otherwise it is masked.
 *   <li>{@code full}: every value after the {@link Masker} (the server stores it encrypted, WO-71;
 *       before that it drops it, ADR-0010 C-148).
 * </ul>
 *
 * <p>Values (F-A04 "JDK 기본 타입만"): {@code String}, the JDK number classes ({@code Integer}, {@code
 * Long}, {@code Short}, {@code Byte}, {@code Float}, {@code Double}, {@code BigDecimal}, {@code
 * BigInteger}), {@code Boolean}, {@code java.util.Date}, {@code java.sql.Date}, {@code Time},
 * {@code Timestamp}, the {@code java.time} types and {@code UUID} are rendered; a {@code byte[]}
 * gives its length only; anything else (streams, readers, {@code Blob}, {@code Clob}, {@code
 * Array}, entities, application subclasses of the classes above) gives {@code <Type>}, from its
 * class name alone: no method of the object is called, so a stream is never read and no application
 * {@code toString()} runs. The whole text goes through the masker (which reads at most {@value
 * Masker#MAX_INPUT_CHARS} characters under its own work bound) and only the masked result is cut to
 * {@value SqlState#MAX_VALUE_CHARS} characters (16 §6.6 "길이 상한 절단은 마스킹 후"): a cut before masking
 * would split a token at the cut so that no pattern matches the remaining part, and the shorter
 * placeholders in front of it ({@code <email>}) would pull that part into the kept characters.
 * {@link SqlState} keeps indexes 1 to {@value SqlState#MAX_PARAMS} and only the first parameter set
 * of a batch.
 */
public final class BindParameterCapturer {
  /** Bits of a {@code BigInteger} or {@code BigDecimal} unscaled value rendered (above: type). */
  static final int MAX_BIG_BITS = 4096;

  /** Characters of a type name. */
  static final int MAX_TYPE_CHARS = 64;

  private BindParameterCapturer() {}

  /**
   * Renders {@code value} of parameter {@code index} for {@code capture} and keeps it in {@code
   * state}. {@code normalizer} and {@code dialect} read the statement for the {@code partial}
   * column mapping (once per statement).
   */
  public static void capture(
      SqlState state,
      int index,
      Object value,
      String setter,
      CaptureConfig capture,
      Masker masker,
      SqlNormalizer normalizer,
      int dialect) {
    if (state == null || capture == null || index < 1 || index > SqlState.MAX_PARAMS) {
      return;
    }
    int mode = capture.sqlParamMode();
    if (mode == CaptureConfig.SQL_PARAMS_FULL) {
      state.param(index, plain(value, setter, masker));
    } else if (mode == CaptureConfig.SQL_PARAMS_PARTIAL) {
      if (!state.paramWhitelistKnown) {
        state.paramWhitelist =
            whitelistMask(state.rawSql, dialect, capture.sqlParamWhitelistColumns(), normalizer);
        state.paramWhitelistKnown = true;
      }
      boolean whitelisted = (state.paramWhitelist & (1L << (index - 1))) != 0;
      state.param(index, whitelisted ? plain(value, setter, masker) : typeAndLength(value, setter));
    }
  }

  // --- rendering -------------------------------------------------------------------------------

  /** The value after {@code masker}, quoted when it is text; {@code <Type>} when not rendered. */
  static String plain(Object value, String setter, Masker masker) {
    if (value == null || isSetNull(setter)) {
      return "NULL";
    }
    if (value instanceof byte[]) {
      return "[byte[](" + ((byte[]) value).length + ")]";
    }
    String text = text(value);
    if (text == null) {
      return "<" + typeName(value) + ">";
    }
    // Mask first, cut after (16 §6.6): the masker bounds its own input and work.
    String masked = masker == null ? text : masker.mask(text);
    String rendered = quoted(value) ? quote(masked) : masked;
    return cut(rendered);
  }

  /** {@code [<Type>(<length>)]}: the type and the length of the value's text, never the value. */
  static String typeAndLength(Object value, String setter) {
    if (value == null || isSetNull(setter)) {
      return "NULL";
    }
    if (value instanceof byte[]) {
      return "[byte[](" + ((byte[]) value).length + ")]";
    }
    if (value instanceof String) {
      return "[String(" + ((String) value).length() + ")]";
    }
    String text = text(value);
    if (text == null) {
      return "<" + typeName(value) + ">";
    }
    return "[" + typeName(value) + "(" + text.length() + ")]";
  }

  /**
   * The text of an allowed JDK value, or null for any other object. Exact class checks: an
   * application subclass of {@code java.util.Date} or a custom {@code Number} is not rendered.
   */
  static String text(Object value) {
    Class<?> type = value.getClass();
    if (type == String.class) {
      return (String) value;
    }
    if (type == Integer.class
        || type == Long.class
        || type == Short.class
        || type == Byte.class
        || type == Float.class
        || type == Double.class
        || type == Boolean.class
        || type == UUID.class
        || type == java.util.Date.class
        || type == java.sql.Date.class
        || type == java.sql.Time.class
        || type == java.sql.Timestamp.class) {
      return String.valueOf(value);
    }
    if (type == BigDecimal.class) {
      BigDecimal decimal = (BigDecimal) value;
      return decimal.unscaledValue().bitLength() > MAX_BIG_BITS ? null : decimal.toString();
    }
    if (type == BigInteger.class) {
      BigInteger integer = (BigInteger) value;
      return integer.bitLength() > MAX_BIG_BITS ? null : integer.toString();
    }
    if (type.getClassLoader() == null && type.getName().startsWith("java.time.")) {
      // JDK classes only (the bootstrap loader): their toString() is the ISO form.
      return String.valueOf(value);
    }
    return null;
  }

  private static boolean quoted(Object value) {
    Class<?> type = value.getClass();
    return !(type == Integer.class
        || type == Long.class
        || type == Short.class
        || type == Byte.class
        || type == Float.class
        || type == Double.class
        || type == Boolean.class
        || type == BigDecimal.class
        || type == BigInteger.class);
  }

  private static boolean isSetNull(String setter) {
    return "setNull".equals(setter);
  }

  /**
   * The simple name of the value's class, from its name alone (no call on the object): the part
   * after the package and after the last {@code $} of a nested class ({@code $Proxy12} becomes
   * {@code Proxy12}); an anonymous class keeps its outer name; any array is {@code array}.
   */
  static String typeName(Object value) {
    String name = value.getClass().getName();
    int dot = name.lastIndexOf('.');
    String simple = dot < 0 ? name : name.substring(dot + 1);
    if (simple.startsWith("[")) {
      simple = "array";
    }
    int dollar = simple.lastIndexOf('$');
    if (dollar >= 0 && dollar + 1 < simple.length()) {
      String inner = simple.substring(dollar + 1);
      if (!SqlTokenizer.isDigit(inner.charAt(0))) {
        simple = inner;
      }
    }
    return simple.length() > MAX_TYPE_CHARS ? simple.substring(0, MAX_TYPE_CHARS) : simple;
  }

  private static String quote(String text) {
    StringBuilder out = new StringBuilder(text.length() + 2);
    out.append('\'');
    for (int i = 0; i < text.length() && out.length() <= SqlState.MAX_VALUE_CHARS; i++) {
      char c = text.charAt(i);
      if (c == '\'') {
        out.append('\'');
      }
      out.append(c);
    }
    return out.append('\'').toString();
  }

  private static String cut(String text) {
    if (text.length() <= SqlState.MAX_VALUE_CHARS) {
      return text;
    }
    int end = SqlState.MAX_VALUE_CHARS;
    if (Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(0, end);
  }

  // --- partial: which positions have a whitelisted column --------------------------------------

  /**
   * Bit {@code n - 1} set when the {@code n}-th positional marker {@code ?} of {@code sql} (n at
   * most {@value SqlState#MAX_PARAMS}) follows {@code <name> =} and that name (the last part of a
   * qualified name, unquoted, any case) is in {@code columns}. Markers inside literals and comments
   * are not counted. 0 when the statement is unknown, the list is empty or the statement cannot be
   * read.
   */
  public static long whitelistMask(
      String sql, int dialect, String[] columns, SqlNormalizer normalizer) {
    if (sql == null || columns == null || columns.length == 0) {
      return 0;
    }
    try {
      SqlNormalizer settings = normalizer == null ? SqlNormalizer.defaults() : normalizer;
      int length = Math.min(sql.length(), settings.maxLength());
      SqlTokenizer tokens =
          new SqlTokenizer(
              sql,
              0,
              length,
              dialect,
              settings.backslashEscape(),
              false,
              (long) SqlNormalizer.BUDGET_PER_CHAR * length + SqlNormalizer.BUDGET_BASE);
      long mask = 0;
      int position = 0;
      String identifier = null;
      String column = null;
      int kind;
      while ((kind = tokens.next()) != SqlTokenizer.END) {
        if (kind == SqlTokenizer.MARKER) {
          position++;
          if (position <= SqlState.MAX_PARAMS && column != null && listed(column, columns)) {
            mask |= 1L << (position - 1);
          }
          identifier = null;
          column = null;
        } else if (kind == SqlTokenizer.WORD || kind == SqlTokenizer.QUOTED_IDENTIFIER) {
          identifier = unquote(sql.substring(tokens.start(), tokens.end()));
          column = null;
        } else if (kind == SqlTokenizer.SYMBOL && tokens.symbol() == '.') {
          // t.col: the next word replaces the qualifier.
          column = null;
        } else if (kind == SqlTokenizer.SYMBOL && tokens.symbol() == '=') {
          column = identifier;
          identifier = null;
        } else {
          identifier = null;
          column = null;
        }
      }
      return mask;
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private static boolean listed(String column, String[] columns) {
    for (String entry : columns) {
      if (entry == null) {
        continue;
      }
      String name = entry.trim();
      int dot = name.lastIndexOf('.');
      if (dot >= 0) {
        name = name.substring(dot + 1);
      }
      if (unquote(name).equalsIgnoreCase(column)) {
        return true;
      }
    }
    return false;
  }

  private static String unquote(String name) {
    if (name.length() >= 2) {
      char first = name.charAt(0);
      char last = name.charAt(name.length() - 1);
      if ((first == '"' && last == '"')
          || (first == '`' && last == '`')
          || (first == '[' && last == ']')) {
        return name.substring(1, name.length() - 1);
      }
    }
    return name;
  }
}
