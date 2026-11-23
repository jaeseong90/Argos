/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The fixed masking of {@code ENV_DUMP} (16 §5.4, SEC-20, 10 §12.1): applied by the agent before
 * the result leaves the JVM. There is no setting and no code path that turns it off; operator
 * patterns ({@code commands.env-dump.mask-patterns}) can only add to it.
 *
 * <ul>
 *   <li>Key rule ({@link #KEY_PATTERN}, case-insensitive, partial match): the whole value becomes
 *       {@code ****(len=N)}.
 *   <li>Value rules, applied when the key does not match, in this order: a PEM private key (whole
 *       value), a JWT (the token), URL credentials (the password part only), JDBC-style {@code
 *       password=|pwd=|user=} (the value), an AWS key id {@code AKIA[0-9A-Z]{16}}, any run of 32
 *       characters or more within one alphabet (standard base64, URL-safe base64 or hex; ADR-0010
 *       C-235 (3)(c)), then the operator patterns (each match).
 *   <li>Composite values ({@code JAVA_TOOL_OPTIONS}, {@code JDK_JAVA_OPTIONS}, {@code
 *       CATALINA_OPTS}, {@code _JAVA_OPTIONS}, any {@code *_OPTS}/{@code *_OPTIONS} variable, the
 *       {@code sun.java.command} property and every JVM input argument) are split into whitespace
 *       separated items; an item {@code -Dkey=value} (also {@code --key=value}, {@code -key=value})
 *       gets the key rule for its key and the value rules for its value, any other item the value
 *       rules.
 * </ul>
 *
 * <p>Deviations from the letter of 16 §5.4, all of them masking more, never less: the URL rule
 * accepts an empty user ({@code redis://:p@h}, which 16 §5.4 case ST-20-24 requires to be masked
 * while its pattern {@code ://[^/\s:]+:} needs a user), and the JWT rule also finds a token inside
 * a longer value (16 §5.4 anchors it at both ends). If masking a value fails for any reason the
 * whole value is masked.
 */
public final class EnvMasker {
  /** The key rule of 16 §5.4, exactly. */
  public static final Pattern KEY_PATTERN =
      Pattern.compile(
          "(?i)(pass|pwd|secret|token|key|credential|auth|cert|private|session|cookie|salt|argos"
              + "|apikey|api_key|access|signature|license|bearer|jwt|otp|pin\\b|dsn"
              + "|connection[-_]?string)");

  static final Pattern PEM = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");

  /** 16 §5.4 JWT {@code ^eyJ...$}, also found inside a longer value (masks more). */
  static final Pattern JWT =
      Pattern.compile(
          "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"
              + "(?![A-Za-z0-9_-])");

  /** 16 §5.4 URL credentials; group 2 is the password. The user may be empty (ST-20-24). */
  static final Pattern URL_CREDENTIALS = Pattern.compile("://([^/\\s:@]*):([^/\\s@]+)@");

  /** 16 §5.4 JDBC; group 2 is the value. */
  static final Pattern JDBC = Pattern.compile("(?i)(password|pwd|user)=([^;&\\s]+)");

  static final Pattern AWS_KEY_ID = Pattern.compile("AKIA[0-9A-Z]{16}");

  /**
   * 16 §5.4 long secret candidate, standard base64 alphabet: a run of 32 characters or more of
   * {@code [A-Za-z0-9+/=]} (hex is a subset). ADR-0010 C-235 (3)(c): a run counts only within one
   * alphabet, so a long Unix path that mixes {@code /} with {@code -} or {@code _} ({@code
   * /usr/lib/jvm/java-21-openjdk-amd64}) is not a candidate.
   */
  static final Pattern LONG_SECRET_BASE64 =
      Pattern.compile("(?<![A-Za-z0-9+/=])[A-Za-z0-9+/=]{32,}(?![A-Za-z0-9+/=])");

  /** The same for the URL-safe base64 alphabet {@code [A-Za-z0-9_=-]} (hex is a subset). */
  static final Pattern LONG_SECRET_URL_SAFE =
      Pattern.compile("(?<![A-Za-z0-9_=-])[A-Za-z0-9_=-]{32,}(?![A-Za-z0-9_=-])");

  /**
   * {@code -Dkey=value}, {@code --key=value}, {@code -key=value}: group 1 prefix, 2 key, 3 value.
   */
  private static final Pattern OPTION =
      Pattern.compile("^(-D|--|-)([^=\\s]+)=(.*)$", Pattern.DOTALL);

  /** Operator patterns kept at most. */
  static final int MAX_USER_PATTERNS = 32;

  /** Characters of one operator pattern at most. */
  static final int MAX_USER_PATTERN_CHARS = 512;

  private final List<Pattern> userPatterns;
  private final int rejectedUserPatterns;

  /** The built-in rules plus {@code userPatterns} (invalid or excess entries are ignored). */
  public EnvMasker(List<String> userPatterns) {
    List<Pattern> compiled = new ArrayList<Pattern>();
    int rejected = 0;
    if (userPatterns != null) {
      for (String pattern : userPatterns) {
        if (pattern == null || pattern.trim().isEmpty()) {
          continue;
        }
        if (compiled.size() >= MAX_USER_PATTERNS || pattern.length() > MAX_USER_PATTERN_CHARS) {
          rejected++;
          continue;
        }
        try {
          compiled.add(Pattern.compile(pattern));
        } catch (PatternSyntaxException e) {
          rejected++;
        }
      }
    }
    this.userPatterns = Collections.unmodifiableList(compiled);
    this.rejectedUserPatterns = rejected;
  }

  /** The built-in rules only. */
  public static EnvMasker builtIn() {
    return new EnvMasker(Collections.<String>emptyList());
  }

  /** Operator patterns that were ignored (invalid, too long or over the count bound). */
  public int rejectedUserPatterns() {
    return rejectedUserPatterns;
  }

  /** Operator patterns in use. */
  public int userPatternCount() {
    return userPatterns.size();
  }

  /** {@code ****(len=N)}. */
  public static String mask(String value) {
    return "****(len=" + (value == null ? 0 : value.length()) + ")";
  }

  /** True when the key rule or an operator pattern matches {@code key}. */
  public boolean keyMatches(String key) {
    if (key == null) {
      return false;
    }
    if (KEY_PATTERN.matcher(key).find()) {
      return true;
    }
    for (Pattern pattern : userPatterns) {
      if (pattern.matcher(key).find()) {
        return true;
      }
    }
    return false;
  }

  /** An environment variable: composite variables item by item. */
  public String maskEnv(String key, String value) {
    return maskEntry(key, value, isCompositeEnv(key));
  }

  /** A system property: {@code sun.java.command} item by item. */
  public String maskProperty(String key, String value) {
    return maskEntry(key, value, "sun.java.command".equals(key));
  }

  /** One JVM input argument ({@code RuntimeMXBean.getInputArguments()}): item by item. */
  public String maskArgument(String argument) {
    if (argument == null) {
      return null;
    }
    try {
      return maskComposite(argument);
    } catch (RuntimeException | StackOverflowError e) {
      return mask(argument);
    }
  }

  /** {@code key=value} with the key rule, then (composite) item or value rules. */
  String maskEntry(String key, String value, boolean composite) {
    if (value == null) {
      return null;
    }
    try {
      if (keyMatches(key)) {
        return mask(value);
      }
      return composite ? maskComposite(value) : maskValue(value);
    } catch (RuntimeException | StackOverflowError e) {
      return mask(value);
    }
  }

  /** True for the composite variables of 16 §5.4 and any {@code *_OPTS}/{@code *_OPTIONS}. */
  static boolean isCompositeEnv(String key) {
    if (key == null) {
      return false;
    }
    String upper = key.toUpperCase(Locale.ROOT);
    return upper.equals("JAVA_TOOL_OPTIONS")
        || upper.equals("JDK_JAVA_OPTIONS")
        || upper.equals("CATALINA_OPTS")
        || upper.equals("_JAVA_OPTIONS")
        || upper.endsWith("_OPTS")
        || upper.endsWith("_OPTIONS");
  }

  /** Splits {@code value} at whitespace (quotes kept together) and masks every item. */
  String maskComposite(String value) {
    StringBuilder out = new StringBuilder(value.length() + 16);
    int i = 0;
    int n = value.length();
    while (i < n) {
      char c = value.charAt(i);
      if (Character.isWhitespace(c)) {
        out.append(c);
        i++;
        continue;
      }
      int start = i;
      char quote = 0;
      while (i < n) {
        char d = value.charAt(i);
        if (quote != 0) {
          if (d == quote) {
            quote = 0;
          }
        } else if (d == '"' || d == '\'') {
          quote = d;
        } else if (Character.isWhitespace(d)) {
          break;
        }
        i++;
      }
      out.append(maskItem(value.substring(start, i)));
    }
    return out.toString();
  }

  /** One item of a composite value. */
  String maskItem(String item) {
    if (item.length() >= 2) {
      char first = item.charAt(0);
      if ((first == '"' || first == '\'') && item.charAt(item.length() - 1) == first) {
        return first + maskItem(item.substring(1, item.length() - 1)) + first;
      }
    }
    Matcher option = OPTION.matcher(item);
    if (option.matches()) {
      String key = option.group(2);
      String value = option.group(3);
      String masked = keyMatches(key) ? mask(value) : maskValue(value);
      return option.group(1) + key + "=" + masked;
    }
    return maskValue(item);
  }

  /** The value rules (key not matched). */
  String maskValue(String value) {
    if (value.isEmpty()) {
      return value;
    }
    if (PEM.matcher(value).find()) {
      return mask(value);
    }
    String out = replaceAll(JWT, value, 0);
    out = replaceAll(URL_CREDENTIALS, out, 2);
    out = replaceAll(JDBC, out, 2);
    out = replaceAll(AWS_KEY_ID, out, 0);
    out = maskLongRuns(out);
    for (Pattern pattern : userPatterns) {
      out = replaceAll(pattern, out, 0);
    }
    return out;
  }

  /**
   * The long-run rule (C-235 (3)(c)): every run of 32 or more characters of the standard or of the
   * URL-safe base64 alphabet, found in the same value; overlapping runs of the two alphabets are
   * masked as one range, so a secret is never left half visible.
   */
  static String maskLongRuns(String value) {
    Matcher standard = LONG_SECRET_BASE64.matcher(value);
    Matcher urlSafe = LONG_SECRET_URL_SAFE.matcher(value);
    boolean hasStandard = standard.find();
    boolean hasUrlSafe = urlSafe.find();
    if (!hasStandard && !hasUrlSafe) {
      return value;
    }
    // Ranges of both alphabets in start order, merged when they overlap or touch.
    StringBuilder out = new StringBuilder(value.length() + 16);
    int last = 0;
    int start = -1;
    int end = -1;
    while (hasStandard || hasUrlSafe) {
      Matcher next;
      if (!hasUrlSafe || (hasStandard && standard.start() <= urlSafe.start())) {
        next = standard;
      } else {
        next = urlSafe;
      }
      int s = next.start();
      int e = next.end();
      if (start >= 0 && s <= end) {
        end = Math.max(end, e);
      } else {
        if (start >= 0) {
          out.append(value, last, start).append(mask(value.substring(start, end)));
          last = end;
        }
        start = s;
        end = e;
      }
      if (next == standard) {
        hasStandard = standard.find();
      } else {
        hasUrlSafe = urlSafe.find();
      }
    }
    out.append(value, last, start).append(mask(value.substring(start, end)));
    out.append(value, end, value.length());
    return out.toString();
  }

  /** Every match of {@code pattern}: {@code group} replaced by its mask. */
  private static String replaceAll(Pattern pattern, String value, int group) {
    Matcher m = pattern.matcher(value);
    if (!m.find()) {
      return value;
    }
    StringBuilder out = new StringBuilder(value.length() + 16);
    int last = 0;
    do {
      int start = m.start(group);
      int end = m.end(group);
      if (start < 0 || end <= start) {
        continue;
      }
      out.append(value, last, start).append(mask(value.substring(start, end)));
      last = end;
    } while (m.find());
    out.append(value, last, value.length());
    return out.toString();
  }
}
