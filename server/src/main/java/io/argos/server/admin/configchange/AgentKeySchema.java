/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.configchange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The server-side schema of the agent configuration keys (12 §6.2 "서버 측 스키마(허용 키 목록·타입·범위)",
 * DSN-12-09, 16 §5.5): every key of the {@code docs/agent-config.md} key reference with its type
 * text and its "Remote" column, verbatim. {@code AgentKeySchemaDocTest} compares this table with
 * the document, so a key, type or Remote value that differs fails the build.
 *
 * <p>A key may be changed remotely when its Remote column is {@code Y} or {@code N} (the agent's
 * local {@code remote-config.allow} decides which, SEC-23); {@code -} keys and the immutable
 * prefixes of 16 §5.5 step 1 ({@code server.*}, {@code remote-config.*}, {@code app.*}, {@code
 * log.dir}, {@code agent.disabled}) never are. Keys are canonical: a leading {@code argos.} is
 * removed ({@code argos.enabled} = {@code enabled}, the kill switch).
 *
 * <p>{@link #check} mirrors the agent's checks 1, 2 and 5 of docs/agent-config.md "Remote
 * configuration" (immutable, unknown key, invalid value); check 3 ({@code not_allowed}) is the
 * per-instance allow list ({@link AllowPatterns}); check 4 ({@code reduce_masking}) can only be
 * judged against the server-held list ({@code ConfigChangeService}), the agent judges it again.
 * Values are normalized to the agent's string form: booleans {@code true}/{@code false}, enums as
 * listed, lists as a YAML flow sequence of double-quoted items (the agent's {@code
 * KeySpec.splitList} reads it).
 */
public final class AgentKeySchema {

  /** The prefix a key or pattern may carry ({@code argos.enabled}). */
  public static final String PREFIX = "argos.";

  /** The kill switch key (canonical; {@code argos.enabled} on the wire means the same). */
  public static final String KILL_SWITCH_KEY = "enabled";

  /** Characters of a key at most ({@code app_config.key}). */
  public static final int MAX_KEY_CHARS = 128;

  /** Characters of a normalized value at most ({@code app_config.value}). */
  public static final int MAX_VALUE_CHARS = 1024;

  /** Characters of a list item at most. */
  static final int MAX_ITEM_CHARS = 512;

  /** The Remote column of a key. */
  public enum Remote {
    /** In the default allow list or changeable when allowed. */
    Y,
    /** Changeable only when the local allow list names it. */
    N,
    /** Never changed remotely. */
    NONE;

    static Remote of(String column) {
      return switch (column) {
        case "Y" -> Y;
        case "N" -> N;
        case "-" -> NONE;
        default -> throw new IllegalArgumentException("Remote column " + column);
      };
    }

    /** The column text ({@code Y}, {@code N}, {@code -}). */
    public String column() {
      return this == NONE ? "-" : name();
    }
  }

  /** Why a key or value is refused before any instance is asked. */
  public enum Refusal {
    /** 16 §5.5 step 1 or a {@code -} key. */
    IMMUTABLE,
    /** Not a key of the reference. */
    UNKNOWN_KEY,
    /** The value fails the key's type, range or item checks. */
    INVALID_VALUE;

    /** The API reason code. */
    public String code() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * One key of the reference.
   *
   * @param name canonical name; a family ends with {@code .*} (one free last part)
   * @param typeText the "Type" column verbatim
   * @param remote the "Remote" column
   */
  public record Key(String name, String typeText, Remote remote) {

    /** Whether this is a family ({@code ...services.*}). */
    public boolean family() {
      return name.endsWith(".*");
    }

    /** Whether a collector may ever change it (Remote {@code Y} or {@code N}). */
    public boolean remotable() {
      return remote != Remote.NONE;
    }
  }

  /** The outcome of {@link #check}: the normalized value, or the refusal. */
  public record Checked(@Nullable String value, @Nullable Refusal refusal) {

    static Checked ok(String value) {
      return new Checked(value, null);
    }

    static Checked refused(Refusal refusal) {
      return new Checked(null, refusal);
    }

    /** Whether the key and value pass. */
    public boolean ok() {
      return refusal == null;
    }
  }

  private static final Pattern RANGE =
      Pattern.compile("(int|number|bytes) \\[(-?[0-9.]+), (-?[0-9.]+)]");
  private static final Pattern LIST = Pattern.compile("list \\(<= ([0-9]+) items\\)");
  private static final Pattern KEY_CHARS = Pattern.compile("[A-Za-z0-9._*-]{1,128}");

  private static final List<Key> KEYS = new ArrayList<>();
  private static final Map<String, Key> EXACT = new LinkedHashMap<>();
  private static final List<Key> FAMILIES = new ArrayList<>();

  static {
    // Collector connection
    key("server.address", "string", "-");
    key("server.tls", "bool", "-");
    key("server.tls.ca-file", "path", "-");
    key("server.tls.insecure-skip-verify", "bool", "-");
    key("server.token", "string", "-");
    key("server.token-file", "path", "-");
    key("server.connect-timeout-ms", "int [1, 600000]", "-");
    key("server.deadline-ms", "int [1, 600000]", "-");
    // Application
    key("app.name", "string", "-");
    key("app.env", "string", "-");
    key("app.instance", "string", "-");
    key("app.version", "string", "-");
    key("app.group", "string", "-");
    // Kill switch
    key("enabled", "bool", "Y");
    key("agent.disabled", "bool", "-");
    // Sampling
    key("sampling.profile.slow-threshold-ms", "int [0, 86400000]", "Y");
    key("sampling.profile.slow-threshold-ms.services.*", "int [0, 86400000]", "Y");
    key("sampling.profile.normal-rate", "number [0.0, 1.0]", "Y");
    key("sampling.profile.max-steps", "int [1, 10000]", "Y");
    // Data capture
    key("capture.query-string", "enum: off, whitelist, full", "N");
    key("capture.query-string.whitelist", "list (<= 64 items)", "N");
    key("capture.http-headers", "list (<= 10 items)", "N");
    key("capture.request-id-header", "string", "N");
    key("capture.remote-ip", "enum: full, masked, off", "N");
    key("capture.trusted-proxies", "list (<= 64 items)", "N");
    key("capture.user-id.mode", "enum: off, plain, hashed", "N");
    key("capture.user-id.source", "string", "N");
    key("capture.sql-parameters", "enum: masked, partial, full", "N");
    key("capture.sql-parameters.whitelist-columns", "list (<= 256 items)", "N");
    key("capture.mask-patterns", "list (<= 32 items)", "N");
    key("capture.error-message-max-length", "int [0, 65536]", "Y");
    key("capture.stack-depth", "int [0, 64]", "Y");
    // Instrumentation
    key("instrumentation.disabled", "list (<= 64 items)", "Y");
    key("instrumentation.exclude-classes", "list (<= 256 items)", "Y");
    key("instrumentation.ignore-paths", "list (<= 256 items)", "Y");
    key("instrumentation.ignore-extensions", "list (<= 128 items)", "Y");
    key("instrumentation.ignore-user-agents", "list (<= 64 items)", "Y");
    key("instrumentation.ignore-methods", "list (<= 16 items)", "Y");
    key("instrumentation.custom-methods", "list (<= 256 items)", "N");
    key("instrumentation.custom-methods.max-classes-per-pattern", "int [1, 10000]", "N");
    key("instrumentation.app-packages", "list (<= 32 items)", "Y");
    key("instrumentation.service-dictionary-max", "int [1, 100000]", "N");
    key("instrumentation.error-threshold", "int [1, 1000000]", "N");
    key("instrumentation.transform-error-threshold", "int [1, 1000000]", "N");
    // Transactions
    key("transaction.max-duration-ms", "int [1000, 86400000]", "Y");
    key("transaction.slow-stack.enabled", "bool", "Y");
    key("transaction.slow-stack.interval-ms", "int [100, 3600000]", "Y");
    key("transaction.slow-stack.max-captures", "int [0, 100]", "Y");
    key("transaction.slow-stack.max-threads-per-cycle", "int [1, 100]", "Y");
    key("transaction.slow-stack.depth", "int [1, 64]", "Y");
    // Context propagation
    key("propagation.exclude-hosts", "list (<= 256 items)", "N");
    key("propagation.argos-state.hosts", "list (<= 256 items)", "N");
    key("propagation.preserve-incoming", "bool", "N");
    key("async.step", "bool", "Y");
    // Buffers and sending
    key("buffer.transaction.capacity", "int [1, 1000000]", "N");
    key("buffer.max-age-seconds", "int [1, 3600]", "N");
    key("buffer.max-bytes", "bytes [1024, 1073741824]", "N");
    key("sender.flush-interval-ms", "int [1, 60000]", "N");
    key("sender.batch-size", "int [1, 5000]", "N");
    key("sender.max-in-flight", "int [1, 4]", "N");
    key("sender.compression", "enum: gzip, none", "N");
    // Metrics, statistics and active transactions
    key("metrics.interval-seconds", "int [1, 3600]", "Y");
    key("metrics.max-series", "int [1, 500]", "N");
    key("stats.window-seconds", "int [1, 3600]", "N");
    key("stats.max-keys", "int [1, 5000]", "N");
    key("active.snapshot-interval-seconds", "int [1, 3600]", "Y");
    // SQL
    key("sql.max-length", "int [64, 65536]", "N");
    key("sql.backslash-escape", "bool", "N");
    key("sql.keep-hints", "bool", "N");
    // Commands
    key("commands.env-dump.mask-patterns", "list (<= 64 items)", "N");
    key("commands.thread-dump.cooldown-seconds", "int [1, 3600]", "N");
    key("commands.thread-dump.max-bytes", "int [1024, 1048576]", "N");
    // Remote configuration and agent log
    key("remote-config.allow", "list (<= 128 items)", "-");
    key("log.level", "enum: ERROR, WARN, INFO, DEBUG, TRACE", "Y");
    key("log.dir", "path", "-");
    key("log.max-file-mb", "int [1, 1024]", "-");
    key("log.max-files", "int [1, 100]", "-");
    // Diagnostic keys
    key("debug.transform-timing", "bool", "-");
    key("debug.transform-log", "path", "-");
    key("debug.transform-list", "path", "-");
    // Development-only test keys
    key("test.exporter", "string", "-");
    key("test.faults", "list (<= 32 items)", "-");
    key("test.selfdiag-file", "path", "-");
    key("test.overhead-monitor", "enum: on, off", "-");
  }

  private AgentKeySchema() {}

  private static void key(String name, String typeText, String remote) {
    Key k = new Key(name, typeText, Remote.of(remote));
    KEYS.add(k);
    if (k.family()) {
      FAMILIES.add(k);
    } else if (EXACT.put(name, k) != null) {
      throw new IllegalStateException("duplicate key " + name);
    }
  }

  /** Every key of the reference, in document order. */
  public static List<Key> keys() {
    return Collections.unmodifiableList(KEYS);
  }

  /** The exact keys a collector may ever change (no families), in document order. */
  public static List<String> remotableKeys() {
    List<String> out = new ArrayList<>();
    for (Key k : KEYS) {
      if (k.remotable() && !k.family()) {
        out.add(k.name());
      }
    }
    return out;
  }

  /** {@code key} without a leading {@code argos.}, trimmed. */
  public static String canonical(String key) {
    String k = key.strip();
    return k.startsWith(PREFIX) ? k.substring(PREFIX.length()) : k;
  }

  /** 16 §5.5 step 1: never changed remotely, known key or not. */
  public static boolean immutablePrefix(String canonicalKey) {
    return canonicalKey.startsWith("server.")
        || canonicalKey.startsWith("remote-config.")
        || canonicalKey.startsWith("app.")
        || canonicalKey.equals("log.dir")
        || canonicalKey.equals("agent.disabled");
  }

  /**
   * Whether {@code key} can be a key: 1 to {@value #MAX_KEY_CHARS} printable ASCII characters (a
   * service name in a family key may hold spaces and slashes).
   */
  public static boolean wellFormed(String key) {
    return !key.isEmpty()
        && key.length() <= MAX_KEY_CHARS
        && key.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
  }

  /**
   * Whether {@code key} has only the characters of a plain key ({@code [A-Za-z0-9._*-]}): such a
   * key may name the field of an error detail.
   */
  public static boolean plain(String key) {
    return KEY_CHARS.matcher(key).matches();
  }

  /** The reference entry of a canonical key (a family member gives its family). */
  public static Optional<Key> find(String canonicalKey) {
    Key exact = EXACT.get(canonicalKey);
    if (exact != null) {
      return Optional.of(exact);
    }
    for (Key f : FAMILIES) {
      String prefix = f.name().substring(0, f.name().length() - 1);
      if (canonicalKey.length() > prefix.length() && canonicalKey.startsWith(prefix)) {
        return Optional.of(f);
      }
    }
    return Optional.empty();
  }

  /** Whether a canonical key is a list key. */
  public static boolean isList(String canonicalKey) {
    return find(canonicalKey).map(k -> k.typeText().startsWith("list")).orElse(false);
  }

  /**
   * Checks a canonical key and the raw value (already a string; see {@link #normalize}) and returns
   * the normalized value or the refusal (immutable before unknown before invalid value, like the
   * agent).
   */
  public static Checked check(String canonicalKey, String raw) {
    if (immutablePrefix(canonicalKey)) {
      return Checked.refused(Refusal.IMMUTABLE);
    }
    Optional<Key> found = wellFormedKey(canonicalKey) ? find(canonicalKey) : Optional.empty();
    if (found.isEmpty()) {
      return Checked.refused(Refusal.UNKNOWN_KEY);
    }
    Key key = found.get();
    if (!key.remotable()) {
      return Checked.refused(Refusal.IMMUTABLE);
    }
    String value = normalize(key.typeText(), raw);
    if (value == null || value.length() > MAX_VALUE_CHARS) {
      return Checked.refused(Refusal.INVALID_VALUE);
    }
    return Checked.ok(value);
  }

  private static boolean wellFormedKey(String canonicalKey) {
    return canonicalKey.length() <= MAX_KEY_CHARS
        && !canonicalKey.isEmpty()
        && canonicalKey.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
  }

  /** The normalized value of {@code raw} for a key of {@code typeText}, or null when invalid. */
  static @Nullable String normalize(String typeText, String raw) {
    String v = raw.strip();
    if (typeText.equals("bool")) {
      if (v.equalsIgnoreCase("true")) {
        return "true";
      }
      return v.equalsIgnoreCase("false") ? "false" : null;
    }
    if (typeText.startsWith("enum: ")) {
      for (String option : typeText.substring("enum: ".length()).split(", ", -1)) {
        if (option.equalsIgnoreCase(v)) {
          return option;
        }
      }
      return null;
    }
    Matcher range = RANGE.matcher(typeText);
    if (range.matches()) {
      return number(range.group(1), range.group(2), range.group(3), v);
    }
    Matcher list = LIST.matcher(typeText);
    if (list.matches()) {
      List<String> items = splitList(v);
      if (items == null || items.size() > Integer.parseInt(list.group(1))) {
        return null;
      }
      return flow(items);
    }
    if (typeText.equals("string") || typeText.equals("path")) {
      return v.isEmpty() || v.chars().anyMatch(c -> c < 0x20 || c == 0x7f) ? null : v;
    }
    return null;
  }

  private static @Nullable String number(String kind, String min, String max, String v) {
    try {
      BigDecimal value;
      if (kind.equals("bytes")) {
        Long bytes = bytes(v);
        if (bytes == null) {
          return null;
        }
        value = BigDecimal.valueOf(bytes);
      } else {
        value = new BigDecimal(v);
      }
      if (value.compareTo(new BigDecimal(min)) < 0 || value.compareTo(new BigDecimal(max)) > 0) {
        return null;
      }
      if (kind.equals("number")) {
        return value.stripTrailingZeros().toPlainString();
      }
      return value.toBigIntegerExact().toString();
    } catch (NumberFormatException | ArithmeticException e) {
      return null;
    }
  }

  /** Bytes with an optional binary unit (the agent's {@code KeySpec.parseBytes}). */
  static @Nullable Long bytes(String v) {
    String text = v.toUpperCase(Locale.ROOT).replace(" ", "");
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
    long multiplier = 1;
    String digits = text;
    for (String[] unit : units) {
      if (text.endsWith(unit[0])) {
        digits = text.substring(0, text.length() - unit[0].length());
        multiplier = Long.parseLong(unit[1]);
        break;
      }
    }
    if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit) || digits.length() > 12) {
      return null;
    }
    return Long.parseLong(digits) * multiplier;
  }

  /**
   * The items of a list value: a YAML flow sequence ({@code ["a", 'b']}) or comma-separated items;
   * items trimmed, blank items dropped; null when an item has control characters, is too long or a
   * quote is not closed.
   */
  static @Nullable List<String> splitList(String value) {
    String text = value.strip();
    boolean flow = text.length() >= 2 && text.startsWith("[") && text.endsWith("]");
    if (flow) {
      text = text.substring(1, text.length() - 1);
    }
    List<String> items = new ArrayList<>();
    StringBuilder item = new StringBuilder();
    char quote = 0;
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\' && quote == '"' && i + 1 < text.length()) {
          item.append(text.charAt(++i));
        } else if (c == quote) {
          quote = 0;
        } else {
          item.append(c);
        }
      } else if (c == ',') {
        if (!add(items, item, quoted)) {
          return null;
        }
        item.setLength(0);
        quoted = false;
      } else if (flow && (c == '"' || c == '\'') && item.toString().isBlank()) {
        quote = c;
        quoted = true;
        item.setLength(0);
      } else {
        item.append(c);
      }
    }
    if (quote != 0 || !add(items, item, quoted)) {
      return null;
    }
    return items;
  }

  private static boolean add(List<String> items, StringBuilder item, boolean quoted) {
    String text = quoted ? item.toString() : item.toString().strip();
    if (text.isEmpty()) {
      return true;
    }
    if (text.length() > MAX_ITEM_CHARS || text.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      return false;
    }
    items.add(text);
    return true;
  }

  /** A YAML flow sequence of double-quoted items ({@code ["a", "b"]}, {@code []} when empty). */
  public static String flow(List<String> items) {
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) {
        out.append(", ");
      }
      out.append('"');
      String item = items.get(i);
      for (int j = 0; j < item.length(); j++) {
        char c = item.charAt(j);
        if (c == '"' || c == '\\') {
          out.append('\\');
        }
        out.append(c);
      }
      out.append('"');
    }
    return out.append(']').toString();
  }
}
