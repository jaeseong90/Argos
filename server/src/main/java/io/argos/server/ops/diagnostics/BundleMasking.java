/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.diagnostics;

import io.argos.server.config.ArgosProperties;
import io.argos.server.config.ConfigKeyCatalog;
import io.argos.server.config.ConfigKeyCatalog.ConfigKey;
import io.argos.server.config.logging.LogMasking;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The masking of every text in a diagnostic bundle (12 §9.5, 17 §4.4 "토큰·비밀번호·마스터 키·채널 URL은 {@code
 * ****}", SEC-06, SEC-26, ADR-0010 C-94). Applied to the copied server log and to every generated
 * file, on top of what the log already masked when it was written:
 *
 * <ol>
 *   <li>{@link LogMasking#mask}: agent tokens {@code argos_[A-Za-z0-9_-]{43}} become {@code
 *       argos_****}, bearer and basic credentials, credentials in URLs, {@code *password=}, {@code
 *       *token=}, {@code *secret=} and similar pairs, session cookies, JWTs, PEM private keys;
 *   <li>the one line of the server that shows the setup token ({@code Setup token (...): <token>})
 *       loses the token;
 *   <li>URLs keep their scheme and host and lose user information, path, query and fragment ({@code
 *       https://hooks.example.com/****}): a webhook or Slack URL carries its secret in the path;
 *   <li>the secret values of the configuration itself (every {@code *password}, {@code *token},
 *       {@code *master-key}, {@code *jwt-key} value and the content of every {@code *-file} that
 *       holds one: database passwords, the master key, the metrics token, the JWT key) are replaced
 *       wherever they appear, whatever the surrounding text.
 * </ol>
 */
public final class BundleMasking {

  /** The replacement. */
  public static final String MASK = LogMasking.MASK;

  /** Shortest configured secret that is replaced literally (shorter ones would mask words). */
  static final int MIN_LITERAL = 8;

  /** Most bytes read from one secret file. */
  static final int MAX_SECRET_FILE_BYTES = 64 * 1024;

  private static final Pattern SETUP_TOKEN =
      Pattern.compile("(Setup token \\([^)]{0,300}\\): )[A-Za-z0-9_-]{43}");

  private static final Pattern URL =
      Pattern.compile(
          "\\b((?:https?|wss?|smtps?|syslog(?:\\+tcp|\\+tls)?)://)(?:[^\\s/\"'<>@]*@)?"
              + "([^\\s/\"'<>?#\\\\]+)([/?#][^\\s\"'<>,;)\\]}\\\\]*)?");

  private final List<String> literals;

  /** Masking with these literal secrets (shorter than {@value #MIN_LITERAL} characters ignored). */
  public BundleMasking(Collection<String> literals) {
    Set<String> unique = new LinkedHashSet<>();
    for (String s : literals) {
      if (s != null && s.length() >= MIN_LITERAL) {
        unique.add(s);
      }
    }
    List<String> sorted = new ArrayList<>(unique);
    sorted.sort(Comparator.comparingInt(String::length).reversed());
    this.literals = List.copyOf(sorted);
  }

  /** The masking of a configuration: its own secret values ({@link #secretLiterals}). */
  public static BundleMasking of(ArgosProperties properties) {
    return new BundleMasking(secretLiterals(properties));
  }

  /** The number of literal secrets (diagnostics of the bundle's manifest, never the values). */
  public int literalCount() {
    return literals.size();
  }

  /** Masks {@code text}. */
  public String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String t = text;
    for (String literal : literals) {
      if (t.contains(literal)) {
        t = t.replace(literal, MASK);
      }
    }
    t = LogMasking.mask(t);
    t = SETUP_TOKEN.matcher(t).replaceAll("$1" + MASK);
    t = urls(t);
    for (String literal : literals) {
      if (t.contains(literal)) {
        t = t.replace(literal, MASK);
      }
    }
    return t;
  }

  private static String urls(String text) {
    Matcher m = URL.matcher(text);
    if (!m.find()) {
      return text;
    }
    StringBuilder sb = new StringBuilder(text.length());
    do {
      String path = m.group(3);
      boolean masked = path != null && !path.equals("/") && !path.equals("/" + MASK);
      m.appendReplacement(
          sb,
          Matcher.quoteReplacement(
              m.group(1) + m.group(2) + (masked ? "/" + MASK : path == null ? "" : path)));
    } while (m.find());
    m.appendTail(sb);
    return sb.toString();
  }

  /**
   * The secret values of {@code properties}: the value of every secret key and the content (whole,
   * and each line) of every file key that holds a secret, as far as the files can be read.
   */
  public static List<String> secretLiterals(ArgosProperties properties) {
    List<String> out = new ArrayList<>();
    for (ConfigKey key : ConfigKeyCatalog.keys()) {
      Object value;
      try {
        value = key.valueIn(properties);
      } catch (RuntimeException e) {
        continue;
      }
      if (!(value instanceof String s) || s.isBlank()) {
        continue;
      }
      String last = key.name().substring(key.name().lastIndexOf('.') + 1);
      if (key.kind() == ConfigKeyCatalog.Kind.SECRET) {
        out.add(s.strip());
      } else if (key.kind() == ConfigKeyCatalog.Kind.SECRET_FILE
          || last.endsWith("password-file")) {
        out.addAll(fileSecrets(s.strip()));
      }
    }
    return out;
  }

  private static List<String> fileSecrets(String file) {
    byte[] bytes;
    try (InputStream in = Files.newInputStream(Path.of(file))) {
      bytes = in.readNBytes(MAX_SECRET_FILE_BYTES);
    } catch (IOException | InvalidPathException | SecurityException e) {
      return List.of();
    }
    String text = new String(bytes, StandardCharsets.UTF_8);
    List<String> out = new ArrayList<>();
    out.add(text.strip());
    for (String line : text.lines().toList()) {
      if (!line.isBlank() && !line.startsWith("-----")) {
        out.add(line.strip());
      }
    }
    return out;
  }
}
