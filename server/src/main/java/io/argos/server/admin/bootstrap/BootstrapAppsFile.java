/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.bootstrap;

import io.argos.server.storage.postgres.app.AppRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * The bootstrap applications file ({@code argos.bootstrap.apps-file}, {@code
 * ARGOS_BOOTSTRAP_APPS_FILE}; 17 §3.8, §6.4; WO-41). It holds no secret, only where the tokens are:
 *
 * <pre>{@code
 * apps:
 *   - name: order-api                                 # application name, 1 to 128 characters
 *     env: demo                                       # environment, 1 to 32 characters
 *     token-file: /run/secrets/demo_order_api_token   # the agent token of that application
 * }</pre>
 *
 * Rules: the top level has the key {@code apps} only, a list of 1 to {@value #MAX_APPS} entries; an
 * entry has exactly the keys {@code name}, {@code env} and {@code token-file}, each a non-empty
 * string without control characters; (name, env) is unique in the file, and so is the token file.
 * Unknown or missing keys are errors. The YAML is read with SnakeYAML's safe constructor (plain
 * maps, lists and scalars only). Error messages name the entry and the key, never a token.
 */
public final class BootstrapAppsFile {

  /** Most applications in one file. */
  public static final int MAX_APPS = 100;

  /** Largest file accepted. */
  public static final int MAX_FILE_BYTES = 64 * 1024;

  /** Longest token file path. */
  static final int MAX_PATH_CHARS = 1024;

  private static final Set<String> ENTRY_KEYS = Set.of("name", "env", "token-file");

  /**
   * One application to register.
   *
   * @param name application name ({@code apps.name})
   * @param env environment ({@code apps.env})
   * @param tokenFile file holding its agent token
   */
  public record Entry(String name, String env, String tokenFile) {

    public Entry {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(env, "env");
      Objects.requireNonNull(tokenFile, "tokenFile");
    }
  }

  private BootstrapAppsFile() {}

  /**
   * Parses the file content.
   *
   * @param text the YAML text
   * @return the entries in file order
   * @throws IllegalArgumentException naming the problem (entry number and key)
   */
  public static List<Entry> parse(String text) {
    Object document;
    try {
      document = yaml().load(text.replace("\r\n", "\n"));
    } catch (YAMLException e) {
      throw new IllegalArgumentException("not valid YAML (" + firstLine(e.getMessage()) + ")");
    }
    if (!(document instanceof Map<?, ?> top)) {
      throw new IllegalArgumentException("the top level must be a map with the key 'apps'");
    }
    for (Object key : top.keySet()) {
      if (!"apps".equals(key)) {
        throw new IllegalArgumentException("unknown top-level key '" + safe(key) + "'");
      }
    }
    if (!(top.get("apps") instanceof List<?> apps)) {
      throw new IllegalArgumentException("'apps' must be a list of applications");
    }
    if (apps.isEmpty()) {
      throw new IllegalArgumentException("'apps' lists no application");
    }
    if (apps.size() > MAX_APPS) {
      throw new IllegalArgumentException("'apps' lists more than " + MAX_APPS + " applications");
    }
    List<Entry> entries = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Set<String> files = new HashSet<>();
    for (int i = 0; i < apps.size(); i++) {
      Entry entry = entry(i + 1, apps.get(i));
      if (!seen.add(entry.name() + "\u0000" + entry.env())) {
        throw new IllegalArgumentException(
            "apps[" + (i + 1) + "]: the application (name, env) is listed twice");
      }
      if (!files.add(entry.tokenFile())) {
        throw new IllegalArgumentException(
            "apps[" + (i + 1) + "]: the token-file is used by another entry");
      }
      entries.add(entry);
    }
    return List.copyOf(entries);
  }

  private static Entry entry(int number, Object raw) {
    String where = "apps[" + number + "]";
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(where + ": an entry is a map of name, env and token-file");
    }
    for (Object key : map.keySet()) {
      if (!(key instanceof String k) || !ENTRY_KEYS.contains(k)) {
        throw new IllegalArgumentException(where + ": unknown key '" + safe(key) + "'");
      }
    }
    String name = text(map, "name", where, AppRepository.MAX_NAME_CHARS);
    String env = text(map, "env", where, AppRepository.MAX_ENV_CHARS);
    String tokenFile = text(map, "token-file", where, MAX_PATH_CHARS);
    return new Entry(name, env, tokenFile);
  }

  private static String text(Map<?, ?> map, String key, String where, int maxChars) {
    Object value = map.get(key);
    if (value == null) {
      throw new IllegalArgumentException(where + ": '" + key + "' is required");
    }
    if (!(value instanceof String s)) {
      throw new IllegalArgumentException(where + ": '" + key + "' must be a string");
    }
    String v = s.strip();
    if (v.isEmpty()) {
      throw new IllegalArgumentException(where + ": '" + key + "' is empty");
    }
    if (v.length() > maxChars) {
      throw new IllegalArgumentException(
          where + ": '" + key + "' is longer than " + maxChars + " characters");
    }
    for (int i = 0; i < v.length(); i++) {
      if (Character.isISOControl(v.charAt(i))) {
        throw new IllegalArgumentException(where + ": '" + key + "' has a control character");
      }
    }
    return v;
  }

  /** A key for a message: short, printable. */
  private static String safe(Object key) {
    String s = String.valueOf(key);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < s.length() && out.length() < 40; i++) {
      char c = s.charAt(i);
      out.append(Character.isISOControl(c) ? '?' : c);
    }
    return out.toString();
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "parse error";
    }
    int nl = message.indexOf('\n');
    String line = (nl < 0 ? message : message.substring(0, nl)).strip();
    return line.length() > 120 ? line.substring(0, 120) : line;
  }

  private static Yaml yaml() {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(10);
    options.setAllowRecursiveKeys(false);
    options.setCodePointLimit(MAX_FILE_BYTES);
    return new Yaml(new SafeConstructor(options));
  }
}
