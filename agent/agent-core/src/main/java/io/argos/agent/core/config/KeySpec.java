/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One configuration key of the registry {@link ConfigKeys} (10 §15): name, type, documented
 * default, the "원격" column, secrecy, validation bounds, kind and a one-line description.
 *
 * <p>Raw values are strings (the YAML file, {@code -Dargos.<key>}, {@code ARGOS_<KEY>}); {@link
 * #parse(String)} turns a raw value into the typed value or throws {@link IllegalArgumentException}
 * with a reason that never contains the value (it may be a secret). Typed values:
 *
 * <table>
 *   <caption>Typed values</caption>
 *   <tr><th>{@link Type}</th><th>Java value</th><th>Raw form</th></tr>
 *   <tr><td>BOOL</td><td>{@link Boolean}</td><td>{@code true} / {@code false}</td></tr>
 *   <tr><td>INT, LONG</td><td>{@link Integer}, {@link Long}</td><td>decimal, inside [min, max]
 *   </td></tr>
 *   <tr><td>DOUBLE</td><td>{@link Double}</td><td>finite decimal inside [min, max]</td></tr>
 *   <tr><td>STRING, PATH</td><td>{@link String}</td><td>at most {@link #maxLength()} chars</td></tr>
 *   <tr><td>ENUM</td><td>{@link String}, the declared spelling</td><td>one of {@link
 *   #enumValues()}, case-insensitive</td></tr>
 *   <tr><td>LIST</td><td>unmodifiable {@code List<String>}</td><td>{@code a, b, c} or a YAML flow
 *   sequence {@code ["a,b", 'c']} (items that contain commas)</td></tr>
 *   <tr><td>BYTES</td><td>{@link Long} bytes</td><td>{@code 33554432}, {@code 32MB}, {@code 512KB},
 *   {@code 1GB} (binary units, also {@code K}/{@code KiB}, ...)</td></tr>
 *   <tr><td>DURATION</td><td>{@link Long} milliseconds</td><td>{@code 250}, {@code 250ms}, {@code
 *   5s}, {@code 2m}, {@code 1h}</td></tr>
 * </table>
 *
 * <p>Every value is bounded (03 §2 input caps): numbers by [min, max], strings and paths by {@link
 * #maxLength()}, lists by {@link #maxItems()} items of at most {@link #itemMaxLength()} chars, and
 * every raw value by {@link #MAX_RAW_LENGTH} chars before it is looked at.
 */
public final class KeySpec {
  /** Value types of 10 §15. */
  public enum Type {
    BOOL,
    INT,
    LONG,
    DOUBLE,
    STRING,
    LIST,
    BYTES,
    DURATION,
    ENUM,
    PATH
  }

  /** The "원격" column of 10 §15 and the default allow list of ADR-0010 C-92. */
  public enum Remote {
    /** "Y": remote changes allowed by default (in the C-92 default {@code remote-config.allow}). */
    Y,
    /** "N": remote changes only when the local {@code remote-config.allow} names the key. */
    N,
    /**
     * "-": never changed remotely ({@code server.*}, {@code app.*}, {@code remote-config.*}, ...).
     */
    NONE
  }

  /** Who may use a key. */
  public enum Kind {
    /** Documented in {@code docs/agent-config.md}; a public contract (O-R03). */
    PUBLIC,
    /** Diagnostics ({@code debug.*}); honoured in release builds (ADR-0009 C-89). */
    DIAGNOSTIC,
    /**
     * {@code test.*}: development builds only, ignored with a WARN in release builds (T-BLD-010).
     */
    TEST
  }

  /** Format checks of single values and list items. */
  public enum Format {
    /** Any text without control characters. */
    TEXT,
    /** {@code host[:port]} or {@code [ipv6][:port]}, port 1..65535. */
    HOST_PORT,
    /** An instrumentation module id ({@code [a-z0-9-]}, 10 §5.1). */
    MODULE_ID,
    /** A glob ({@code *}, {@code **}, {@code ?}) over paths, user agents or host names. */
    GLOB,
    /** A Java class name glob ({@code instrumentation.exclude-classes}). */
    CLASS_GLOB,
    /** A Java package name ({@code instrumentation.app-packages}). */
    PACKAGE,
    /** An IPv4 or IPv6 address with an optional {@code /prefix}. */
    CIDR,
    /** A Java regular expression that compiles. */
    REGEX,
    /** An HTTP header field name (RFC 9110 token). */
    HEADER_NAME,
    /** An HTTP method ({@code [A-Za-z]{1,16}}). */
    HTTP_METHOD,
    /** A file extension without the dot ({@code [A-Za-z0-9]{1,16}}). */
    EXTENSION,
    /** A query parameter name (no {@code &}, {@code =}, {@code #}, whitespace). */
    QUERY_PARAM,
    /** A SQL column name ({@code [A-Za-z0-9_$.]}). */
    COLUMN,
    /** {@code header:<name>}, {@code session:<attr>} or {@code mdc:<key>} (16 §6.1). */
    USER_ID_SOURCE,
    /**
     * {@code <class glob>#<method glob>[|<STEP TYPE>[|<as-transaction>]]}: one item of {@code
     * instrumentation.custom-methods} (10 §6.10); the step type is {@code METHOD} by default.
     */
    CUSTOM_METHOD,
    /** A key glob of {@code remote-config.allow} ({@code [a-z0-9.*-]}, optional {@code argos.}). */
    KEY_GLOB,
    /** A secret: no whitespace or control characters; reasons never quote it. */
    SECRET
  }

  /** Upper bound of a raw value in characters, checked before anything else. */
  public static final int MAX_RAW_LENGTH = 65_536;

  private static final Pattern MODULE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
  private static final Pattern GLOB = Pattern.compile("[^\\p{Cntrl}]{1,1024}");
  private static final Pattern CLASS_GLOB = Pattern.compile("[A-Za-z0-9_$.*?]{1,512}");
  private static final Pattern PACKAGE =
      Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
  private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
  private static final Pattern HTTP_METHOD = Pattern.compile("[A-Za-z]{1,16}");
  private static final Pattern EXTENSION = Pattern.compile("[A-Za-z0-9]{1,16}");
  private static final Pattern QUERY_PARAM = Pattern.compile("[^&=#\\s\\p{Cntrl}]{1,256}");
  private static final Pattern COLUMN = Pattern.compile("[A-Za-z0-9_$.]{1,128}");
  private static final Pattern USER_ID_SOURCE =
      Pattern.compile("(header|session|mdc):[^\\s\\p{Cntrl}]{1,256}");
  private static final Pattern CUSTOM_METHOD =
      Pattern.compile(
          "[A-Za-z0-9_$.*?]{1,512}#[A-Za-z0-9_$*?<>]{1,256}"
              + "(\\|(SQL|DBC|COMMIT|ROLLBACK|HTTP|METHOD|ASYNC|ERROR|STACK|MESSAGE)"
              + "(\\|(true|false))?)?");
  private static final Pattern KEY_GLOB = Pattern.compile("[a-z0-9*][a-z0-9.*-]{0,255}");
  private static final Pattern HOST =
      Pattern.compile("([A-Za-z0-9-]{1,63}(\\.[A-Za-z0-9-]{1,63})*|\\[[0-9A-Fa-f:.]{2,45}])");
  private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
  private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

  private final String name;
  private final Type type;
  private final String defaultValue;
  private final Remote remote;
  private final boolean secret;
  private final Kind kind;
  private final String description;
  private final long min;
  private final long max;
  private final double minDouble;
  private final double maxDouble;
  private final String[] enumValues;
  private final Format format;
  private final int maxLength;
  private final int maxItems;
  private final int itemMaxLength;
  private final boolean required;
  private final boolean propertyOnly;
  private final boolean family;
  private final Object defaultTyped;

  private KeySpec(Builder b) {
    this.name = b.name;
    this.type = b.type;
    this.defaultValue = b.defaultValue;
    this.remote = b.remote;
    this.secret = b.secret;
    this.kind = b.kind;
    this.description = b.description;
    this.min = b.min;
    this.max = b.max;
    this.minDouble = b.minDouble;
    this.maxDouble = b.maxDouble;
    this.enumValues = b.enumValues.clone();
    this.format = b.format;
    this.maxLength = b.maxLength;
    this.maxItems = b.maxItems;
    this.itemMaxLength = b.itemMaxLength;
    this.required = b.required;
    this.propertyOnly = b.propertyOnly;
    this.family = b.name.endsWith(".*");
    // Parsed once: a documented default must pass its own validation (build() fails otherwise),
    // and AgentConfig.of reuses it instead of parsing every default again (C-79 start path).
    this.defaultTyped = b.defaultValue == null ? null : parse(b.defaultValue);
  }

  /** A builder for key {@code name} of {@code type}. */
  static Builder builder(String name, Type type) {
    return new Builder(name, type);
  }

  /**
   * The canonical key name (dot notation without the {@code argos.} prefix, 10 §15). A family ends
   * with {@code .*} ({@link #isFamily()}).
   */
  public String name() {
    return name;
  }

  public Type type() {
    return type;
  }

  /**
   * The documented default as a raw string, or null when the key has no default: required keys
   * ({@link #isRequired()}), unset optional values ({@code server.tls.ca-file}, ...) and values the
   * agent computes at start ({@code app.instance}, {@code app.version}, {@code log.dir}, {@code
   * instrumentation.app-packages}).
   */
  public String defaultValue() {
    return defaultValue;
  }

  /** The typed default ({@link #parse(String)} of {@link #defaultValue()}), or null. */
  public Object defaultTyped() {
    return defaultTyped;
  }

  public Remote remote() {
    return remote;
  }

  /** True for {@code server.token}: never logged, hashed or reported (SEC-06, 16 §2.6). */
  public boolean isSecret() {
    return secret;
  }

  public Kind kind() {
    return kind;
  }

  /** English one-line description (the {@code docs/agent-config.md} text). */
  public String description() {
    return description;
  }

  public long min() {
    return min;
  }

  public long max() {
    return max;
  }

  public double minDouble() {
    return minDouble;
  }

  public double maxDouble() {
    return maxDouble;
  }

  /** The allowed values of an {@link Type#ENUM} key, in documented order. */
  public List<String> enumValues() {
    List<String> values = new ArrayList<String>(enumValues.length);
    Collections.addAll(values, enumValues);
    return Collections.unmodifiableList(values);
  }

  public Format format() {
    return format;
  }

  /** Maximum characters of a STRING, PATH or ENUM value. */
  public int maxLength() {
    return maxLength;
  }

  /** Maximum items of a LIST value. */
  public int maxItems() {
    return maxItems;
  }

  /** Maximum characters of one LIST item. */
  public int itemMaxLength() {
    return itemMaxLength;
  }

  /** True for {@code server.address} and {@code app.name} (10 §4.3 P4 "필수"). */
  public boolean isRequired() {
    return required;
  }

  /**
   * True for {@code agent.disabled}: read from {@code -Dargos.agent.disabled} only (10 §4.3 P0).
   */
  public boolean isPropertyOnly() {
    return propertyOnly;
  }

  /** True when {@link #name()} ends with {@code .*} and covers every key below that prefix. */
  public boolean isFamily() {
    return family;
  }

  /** True when {@code key} is this key or, for a family, a member of it. */
  public boolean matches(String key) {
    if (!family) {
      return name.equals(key);
    }
    String prefix = name.substring(0, name.length() - 1);
    return key.length() > prefix.length() && key.startsWith(prefix);
  }

  /** Null when {@code raw} is a valid value, else the reason (never containing the value). */
  public String check(String raw) {
    try {
      parse(raw);
      return null;
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
  }

  /**
   * The typed value of {@code raw} (see the class comment).
   *
   * @throws IllegalArgumentException with a reason that does not contain {@code raw}
   */
  public Object parse(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("no value");
    }
    if (raw.length() > MAX_RAW_LENGTH) {
      throw new IllegalArgumentException("longer than " + MAX_RAW_LENGTH + " characters");
    }
    String value = raw.trim();
    switch (type) {
      case BOOL:
        return parseBool(value);
      case INT:
        return Integer.valueOf((int) parseRange(parseLong(value)));
      case LONG:
        return Long.valueOf(parseRange(parseLong(value)));
      case DOUBLE:
        return parseDouble(value);
      case BYTES:
        return Long.valueOf(parseRange(parseBytes(value)));
      case DURATION:
        return Long.valueOf(parseRange(parseDuration(value)));
      case ENUM:
        return parseEnum(value);
      case LIST:
        return parseList(value);
      case STRING:
      case PATH:
        return parseString(value);
    }
    throw new IllegalArgumentException("unsupported type " + type);
  }

  private static Boolean parseBool(String value) {
    if (value.equalsIgnoreCase("true")) {
      return Boolean.TRUE;
    }
    if (value.equalsIgnoreCase("false")) {
      return Boolean.FALSE;
    }
    throw new IllegalArgumentException("expected true or false");
  }

  private long parseRange(long value) {
    if (value < min || value > max) {
      throw new IllegalArgumentException("expected a value in [" + min + ", " + max + "]");
    }
    return value;
  }

  private static long parseLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("expected an integer", e);
    }
  }

  private Double parseDouble(String value) {
    double parsed;
    try {
      parsed = Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("expected a number", e);
    }
    if (Double.isNaN(parsed) || parsed < minDouble || parsed > maxDouble) {
      throw new IllegalArgumentException(
          "expected a number in [" + minDouble + ", " + maxDouble + "]");
    }
    return Double.valueOf(parsed);
  }

  /** Bytes with an optional binary unit: B, K/KB/KiB, M/MB/MiB, G/GB/GiB (case-insensitive). */
  static long parseBytes(String value) {
    String text = value.toUpperCase(Locale.ROOT).replace(" ", "");
    long multiplier = 1;
    String digits = text;
    String[][] units = {
      {"KIB", "1024"},
      {"MIB", "1048576"},
      {"GIB", "1073741824"},
      {"KB", "1024"},
      {"MB", "1048576"},
      {"GB", "1073741824"},
      {"K", "1024"},
      {"M", "1048576"},
      {"G", "1073741824"},
      {"B", "1"}
    };
    for (String[] unit : units) {
      if (text.endsWith(unit[0])) {
        digits = text.substring(0, text.length() - unit[0].length());
        multiplier = Long.parseLong(unit[1]);
        break;
      }
    }
    long number;
    try {
      number = Long.parseLong(digits);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("expected bytes such as 33554432, 512KB or 32MB", e);
    }
    if (number < 0 || number > Long.MAX_VALUE / multiplier) {
      throw new IllegalArgumentException("expected bytes such as 33554432, 512KB or 32MB");
    }
    return number * multiplier;
  }

  /** Milliseconds with an optional unit: ms (default), s, m, h. */
  static long parseDuration(String value) {
    String text = value.toLowerCase(Locale.ROOT).replace(" ", "");
    long multiplier = 1;
    String digits = text;
    if (text.endsWith("ms")) {
      digits = text.substring(0, text.length() - 2);
    } else if (text.endsWith("s")) {
      digits = text.substring(0, text.length() - 1);
      multiplier = 1000L;
    } else if (text.endsWith("m")) {
      digits = text.substring(0, text.length() - 1);
      multiplier = 60_000L;
    } else if (text.endsWith("h")) {
      digits = text.substring(0, text.length() - 1);
      multiplier = 3_600_000L;
    }
    long number;
    try {
      number = Long.parseLong(digits);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("expected a duration such as 250ms, 5s, 2m or 1h", e);
    }
    if (number < 0 || number > Long.MAX_VALUE / multiplier) {
      throw new IllegalArgumentException("expected a duration such as 250ms, 5s, 2m or 1h");
    }
    return number * multiplier;
  }

  private String parseEnum(String value) {
    for (String allowed : enumValues) {
      if (allowed.equalsIgnoreCase(value)) {
        return allowed;
      }
    }
    StringBuilder expected = new StringBuilder("expected one of ");
    for (int i = 0; i < enumValues.length; i++) {
      expected.append(i == 0 ? "" : ", ").append(enumValues[i]);
    }
    throw new IllegalArgumentException(expected.toString());
  }

  private String parseString(String value) {
    if (value.length() > maxLength) {
      throw new IllegalArgumentException("longer than " + maxLength + " characters");
    }
    String reason = checkFormat(format, value);
    if (reason != null) {
      throw new IllegalArgumentException(reason);
    }
    return value;
  }

  private List<String> parseList(String value) {
    List<String> items = splitList(value, maxItems);
    if (items == null) {
      throw new IllegalArgumentException("more than " + maxItems + " items or a malformed list");
    }
    for (String item : items) {
      if (item.length() > itemMaxLength) {
        throw new IllegalArgumentException(
            "an item is longer than " + itemMaxLength + " characters");
      }
      String reason = checkFormat(format, item);
      if (reason != null) {
        throw new IllegalArgumentException("an item is invalid: " + reason);
      }
    }
    return Collections.unmodifiableList(items);
  }

  /**
   * Splits a raw list: a YAML flow sequence ({@code [a, "b,c", 'd']}) when the value is enclosed in
   * brackets, else comma-separated items. Items are trimmed and blank items dropped. Returns null
   * when there are more than {@code maxItems} items or a quote is not closed.
   */
  static List<String> splitList(String value, int maxItems) {
    List<String> items = new ArrayList<String>();
    String text = value.trim();
    boolean flow = text.length() >= 2 && text.startsWith("[") && text.endsWith("]");
    if (flow) {
      text = text.substring(1, text.length() - 1);
    }
    StringBuilder item = new StringBuilder();
    char quote = 0;
    boolean quoted = false;
    boolean closed = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\' && quote == '"' && i + 1 < text.length()) {
          item.append(text.charAt(++i));
        } else if (c == quote) {
          if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
            item.append('\'');
            i++;
          } else {
            quote = 0;
            closed = true;
          }
        } else {
          item.append(c);
        }
      } else if (c == ',') {
        if (!add(items, item, quoted, maxItems)) {
          return null;
        }
        item.setLength(0);
        quoted = false;
        closed = false;
      } else if (closed) {
        if (!Character.isWhitespace(c)) {
          return null; // text after a closing quote
        }
      } else if (flow && (c == '"' || c == '\'') && item.toString().trim().isEmpty()) {
        quote = c;
        quoted = true;
        item.setLength(0);
      } else {
        item.append(c);
      }
    }
    if (quote != 0 || !add(items, item, quoted, maxItems)) {
      return null;
    }
    return items;
  }

  private static boolean add(List<String> items, StringBuilder item, boolean quoted, int max) {
    String text = quoted ? item.toString() : item.toString().trim();
    if (text.isEmpty() && !quoted) {
      return true;
    }
    if (items.size() >= max) {
      return false;
    }
    items.add(text);
    return true;
  }

  /** Null when {@code value} has {@code format}, else the reason (without the value). */
  static String checkFormat(Format format, String value) {
    switch (format) {
      case TEXT:
        return hasControl(value) ? "contains control characters" : null;
      case SECRET:
        return value.isEmpty() || hasControlOrSpace(value) ? "empty or contains whitespace" : null;
      case HOST_PORT:
        return checkHostPort(value);
      case MODULE_ID:
        return matches(MODULE_ID, value, "expected a module id such as jdbc or httpclient-okhttp");
      case GLOB:
        return matches(GLOB, value, "expected a glob");
      case CLASS_GLOB:
        return matches(CLASS_GLOB, value, "expected a class name glob");
      case PACKAGE:
        return matches(PACKAGE, value, "expected a Java package name");
      case CIDR:
        return checkCidr(value);
      case REGEX:
        try {
          Pattern.compile(value);
          return null;
        } catch (PatternSyntaxException e) {
          return "expected a regular expression";
        }
      case HEADER_NAME:
        return matches(HEADER_NAME, value, "expected a header name");
      case HTTP_METHOD:
        return matches(HTTP_METHOD, value, "expected an HTTP method");
      case EXTENSION:
        return matches(EXTENSION, value, "expected a file extension without the dot");
      case QUERY_PARAM:
        return matches(QUERY_PARAM, value, "expected a query parameter name");
      case COLUMN:
        return matches(COLUMN, value, "expected a column name");
      case USER_ID_SOURCE:
        return matches(
            USER_ID_SOURCE, value, "expected header:<name>, session:<attribute> or mdc:<key>");
      case CUSTOM_METHOD:
        return matches(
            CUSTOM_METHOD, value, "expected <class glob>#<method glob>[|<step type>[|<bool>]]");
      case KEY_GLOB:
        return matches(KEY_GLOB, value, "expected a key glob such as sampling.* or log.level");
    }
    return "unsupported format " + format;
  }

  private static String matches(Pattern pattern, String value, String reason) {
    return pattern.matcher(value).matches() ? null : reason;
  }

  private static boolean hasControl(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasControlOrSpace(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isISOControl(c) || Character.isWhitespace(c)) {
        return true;
      }
    }
    return false;
  }

  private static String checkHostPort(String value) {
    String host = value;
    int colon = value.lastIndexOf(':');
    int bracket = value.lastIndexOf(']');
    if (colon > bracket && (value.startsWith("[") || value.indexOf(':') == colon)) {
      host = value.substring(0, colon);
      String port = value.substring(colon + 1);
      try {
        int number = Integer.parseInt(port);
        if (number < 1 || number > 65535) {
          return "expected a port in [1, 65535]";
        }
      } catch (NumberFormatException e) {
        return "expected host:port";
      }
    }
    return HOST.matcher(host).matches() ? null : "expected host:port";
  }

  private static String checkCidr(String value) {
    String address = value;
    int slash = value.indexOf('/');
    int maxPrefix = value.indexOf(':') >= 0 ? 128 : 32;
    if (slash >= 0) {
      address = value.substring(0, slash);
      try {
        int prefix = Integer.parseInt(value.substring(slash + 1));
        if (prefix < 0 || prefix > maxPrefix) {
          return "expected a prefix length in [0, " + maxPrefix + "]";
        }
      } catch (NumberFormatException e) {
        return "expected an address/prefix";
      }
    }
    boolean ok =
        maxPrefix == 32
            ? IPV4.matcher(address).matches()
            : IPV6.matcher(address).matches() && address.indexOf(':') >= 0;
    return ok ? null : "expected an IPv4 or IPv6 address with an optional /prefix";
  }

  @Override
  public String toString() {
    return name + " (" + type + ", remote " + remote + ", " + kind + ")";
  }

  /** Builder used by {@link ConfigKeys}; defaults: no default value, remote "-", PUBLIC. */
  static final class Builder {
    private final String name;
    private final Type type;
    private String defaultValue;
    private Remote remote = Remote.NONE;
    private boolean secret;
    private Kind kind = Kind.PUBLIC;
    private String description = "";
    private long min = Long.MIN_VALUE;
    private long max = Long.MAX_VALUE;
    private double minDouble = -Double.MAX_VALUE;
    private double maxDouble = Double.MAX_VALUE;
    private String[] enumValues = new String[0];
    private Format format = Format.TEXT;
    private int maxLength = 1024;
    private int maxItems = 64;
    private int itemMaxLength = 1024;
    private boolean required;
    private boolean propertyOnly;

    private Builder(String name, Type type) {
      this.name = name;
      this.type = type;
    }

    Builder defaultValue(String value) {
      this.defaultValue = value;
      return this;
    }

    Builder remote(Remote value) {
      this.remote = value;
      return this;
    }

    Builder secret() {
      this.secret = true;
      this.format = Format.SECRET;
      return this;
    }

    Builder kind(Kind value) {
      this.kind = value;
      return this;
    }

    Builder description(String value) {
      this.description = value;
      return this;
    }

    Builder range(long minimum, long maximum) {
      this.min = minimum;
      this.max = maximum;
      return this;
    }

    Builder range(double minimum, double maximum) {
      this.minDouble = minimum;
      this.maxDouble = maximum;
      return this;
    }

    Builder values(String... allowed) {
      this.enumValues = allowed.clone();
      return this;
    }

    Builder format(Format value) {
      this.format = value;
      return this;
    }

    Builder maxLength(int value) {
      this.maxLength = value;
      return this;
    }

    Builder items(int count, int itemLength) {
      this.maxItems = count;
      this.itemMaxLength = itemLength;
      return this;
    }

    Builder required() {
      this.required = true;
      return this;
    }

    Builder propertyOnly() {
      this.propertyOnly = true;
      return this;
    }

    KeySpec build() {
      return new KeySpec(this); // parses and so validates the documented default
    }
  }
}
