/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Request exclusion rules (10 §7.2, F-A23, D-19), compiled once and immutable; the tracer swaps the
 * whole object when the configuration changes. Evaluation order: method, path glob, extension, user
 * agent; the first match gives the reason key that {@code transactions_ignored_total{reason}}
 * counts.
 *
 * <p>Path globs ({@code instrumentation.ignore-paths}) match the path within the web application:
 * {@code *} within one segment, {@code **} across segments, {@code ?} one character other than
 * {@code /}. User-agent globs: {@code *} any characters, {@code ?} one character.
 */
public final class IgnoreRules {
  public static final String REASON_METHOD = "method";
  public static final String REASON_PATH = "path";
  public static final String REASON_EXTENSION = "extension";
  public static final String REASON_USER_AGENT = "user_agent";

  /** {@code instrumentation.ignore-paths} default (10 §15). */
  public static final List<String> DEFAULT_PATHS =
      Collections.unmodifiableList(
          Arrays.asList(
              "/actuator/health",
              "/actuator/health/**",
              "/health",
              "/healthz",
              "/ready",
              "/favicon.ico"));

  /** {@code instrumentation.ignore-extensions} default (10 §15). */
  public static final List<String> DEFAULT_EXTENSIONS =
      Collections.unmodifiableList(
          Arrays.asList(
              "css", "js", "map", "png", "jpg", "jpeg", "gif", "svg", "ico", "woff", "woff2", "ttf",
              "eot"));

  /** {@code instrumentation.ignore-user-agents} default: none. */
  public static final List<String> DEFAULT_USER_AGENTS = Collections.emptyList();

  /** {@code instrumentation.ignore-methods} default. */
  public static final List<String> DEFAULT_METHODS = Collections.singletonList("OPTIONS");

  private static final IgnoreRules DEFAULTS =
      of(DEFAULT_PATHS, DEFAULT_EXTENSIONS, DEFAULT_USER_AGENTS, DEFAULT_METHODS);

  private final Set<String> methods;
  private final Set<String> exactPaths;
  private final Pattern[] pathPatterns;
  private final Set<String> extensions;
  private final Pattern[] userAgents;

  private IgnoreRules(
      Set<String> methods,
      Set<String> exactPaths,
      Pattern[] pathPatterns,
      Set<String> extensions,
      Pattern[] userAgents) {
    this.methods = methods;
    this.exactPaths = exactPaths;
    this.pathPatterns = pathPatterns;
    this.extensions = extensions;
    this.userAgents = userAgents;
  }

  /** The documented defaults. */
  public static IgnoreRules defaults() {
    return DEFAULTS;
  }

  /**
   * Compiles the four rule lists; blank entries are skipped and a null list is empty. The result is
   * immutable: a configuration change compiles a new object and the tracer swaps it in one volatile
   * write ({@code TracerImpl.setIgnoreRules}), so a request sees either the old or the new rules.
   */
  public static IgnoreRules of(
      List<String> paths, List<String> extensions, List<String> userAgents, List<String> methods) {
    Set<String> methodSet = new HashSet<String>();
    for (String method : orEmpty(methods)) {
      if (method == null) {
        continue;
      }
      String trimmed = method.trim();
      if (!trimmed.isEmpty()) {
        methodSet.add(trimmed.toUpperCase(Locale.ROOT));
      }
    }
    Set<String> exact = new HashSet<String>();
    List<Pattern> patterns = new ArrayList<Pattern>();
    for (String glob : orEmpty(paths)) {
      String trimmed = glob == null ? "" : glob.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (trimmed.indexOf('*') < 0 && trimmed.indexOf('?') < 0) {
        exact.add(trimmed);
      } else {
        patterns.add(Pattern.compile(pathGlobToRegex(trimmed)));
      }
    }
    Set<String> extensionSet = new HashSet<String>();
    for (String extension : orEmpty(extensions)) {
      String trimmed = extension == null ? "" : extension.trim();
      if (trimmed.startsWith(".")) {
        trimmed = trimmed.substring(1);
      }
      if (!trimmed.isEmpty()) {
        extensionSet.add(trimmed.toLowerCase(Locale.ROOT));
      }
    }
    List<Pattern> agents = new ArrayList<Pattern>();
    for (String glob : orEmpty(userAgents)) {
      String trimmed = glob == null ? "" : glob.trim();
      if (!trimmed.isEmpty()) {
        agents.add(Pattern.compile(userAgentGlobToRegex(trimmed)));
      }
    }
    return new IgnoreRules(
        Collections.unmodifiableSet(methodSet),
        Collections.unmodifiableSet(exact),
        patterns.toArray(new Pattern[0]),
        Collections.unmodifiableSet(extensionSet),
        agents.toArray(new Pattern[0]));
  }

  private static List<String> orEmpty(List<String> values) {
    return values == null ? Collections.<String>emptyList() : values;
  }

  /**
   * The reason the request is excluded, or null. {@code path} is the path within the web
   * application without query string; path parameters ({@code ;jsessionid=}) are ignored.
   */
  public String reason(String method, String path, String userAgent) {
    if (method != null && !methods.isEmpty() && methods.contains(method)) {
      return REASON_METHOD;
    }
    String clean = HttpPaths.clean(path);
    if (exactPaths.contains(clean)) {
      return REASON_PATH;
    }
    for (Pattern pattern : pathPatterns) {
      if (pattern.matcher(clean).matches()) {
        return REASON_PATH;
      }
    }
    if (!extensions.isEmpty()) {
      String extension = HttpPaths.extension(clean);
      if (extension != null && extensions.contains(extension)) {
        return REASON_EXTENSION;
      }
    }
    if (userAgent != null) {
      for (Pattern pattern : userAgents) {
        if (pattern.matcher(userAgent).matches()) {
          return REASON_USER_AGENT;
        }
      }
    }
    return null;
  }

  /** True when no user-agent rule exists (the helper may skip reading the header). */
  public boolean hasUserAgentRules() {
    return userAgents.length > 0;
  }

  static String pathGlobToRegex(String glob) {
    StringBuilder regex = new StringBuilder(glob.length() + 16);
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          regex.append(".*");
          i++;
        } else {
          regex.append("[^/]*");
        }
      } else if (c == '?') {
        regex.append("[^/]");
      } else {
        appendLiteral(regex, c);
      }
    }
    return regex.toString();
  }

  static String userAgentGlobToRegex(String glob) {
    StringBuilder regex = new StringBuilder(glob.length() + 16);
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        regex.append(".*");
      } else if (c == '?') {
        regex.append('.');
      } else {
        appendLiteral(regex, c);
      }
    }
    return regex.toString();
  }

  private static void appendLiteral(StringBuilder regex, char c) {
    if ("\\.[]{}()<>+-=!^$|".indexOf(c) >= 0) {
      regex.append('\\');
    }
    regex.append(c);
  }
}
