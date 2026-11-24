/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import io.argos.server.config.ArgosProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The sinks of {@code argos.audit.sinks} (12 §8.1, 16 §11.4) and the fallback file (16 §11.3).
 *
 * <p>Accepted values (trimmed, the scheme case-insensitive):
 *
 * <ul>
 *   <li>{@code postgres}: {@code audit_logs}, mandatory (the list must name it);
 *   <li>{@code syslog://host[:port]} (UDP, 514), {@code syslog+tcp://host[:port]} (514), {@code
 *       syslog+tls://host[:port]} (6514); an IPv6 address in brackets; no user, path or query;
 *   <li>{@code file:<path>}: a JSON Lines file, rolled by {@code argos.audit.file.*}.
 * </ul>
 *
 * Anything else, a duplicate, or a list without {@code postgres} refuses the start ({@link
 * IllegalArgumentException} naming the key and the value).
 */
public final class AuditSinks {

  /** The configuration key of the list. */
  public static final String KEY = "argos.audit.sinks";

  /** What one entry names. */
  public enum Kind {
    /** {@code postgres}. */
    POSTGRES,
    /** {@code syslog…://}. */
    SYSLOG,
    /** {@code file:}. */
    FILE
  }

  /**
   * One parsed entry.
   *
   * @param kind what it names
   * @param transport the syslog transport, or null
   * @param host the syslog host, or null
   * @param port the syslog port, or 0
   * @param path the file, or null
   */
  public record Spec(
      Kind kind, SyslogAuditSink.Transport transport, String host, int port, Path path) {}

  /**
   * The sinks of a configuration.
   *
   * @param sinks every sink, the primary ones first
   * @param fallback the fallback file of the primary sinks
   */
  public record Configured(List<AuditSink> sinks, AuditSink fallback) {

    public Configured {
      sinks = List.copyOf(sinks);
      Objects.requireNonNull(fallback, "fallback");
    }
  }

  private AuditSinks() {}

  /**
   * Parses the list.
   *
   * @throws IllegalArgumentException for an unknown or malformed value, a duplicate, or no {@code
   *     postgres}
   */
  public static List<Spec> parse(List<String> values) {
    List<Spec> specs = new ArrayList<>();
    for (String raw : values == null ? List.<String>of() : values) {
      Spec spec = parseOne(raw);
      if (specs.contains(spec)) {
        throw new IllegalArgumentException(KEY + ": " + describe(raw) + " is listed twice");
      }
      specs.add(spec);
    }
    if (specs.stream().noneMatch(s -> s.kind() == Kind.POSTGRES)) {
      throw new IllegalArgumentException(
          KEY + " must contain postgres (audit_logs is the mandatory audit store, 12 §8.1)");
    }
    return specs;
  }

  static Spec parseOne(String raw) {
    String value = raw == null ? "" : raw.strip();
    String lower = value.toLowerCase(Locale.ROOT);
    if (lower.equals("postgres")) {
      return new Spec(Kind.POSTGRES, null, null, 0, null);
    }
    if (lower.startsWith("file:")) {
      String path = value.substring("file:".length()).strip();
      if (path.isEmpty()) {
        throw new IllegalArgumentException(
            KEY + ": file: needs a path (file:logs/audit/audit.jsonl)");
      }
      try {
        return new Spec(Kind.FILE, null, null, 0, Path.of(path).toAbsolutePath().normalize());
      } catch (InvalidPathException e) {
        throw new IllegalArgumentException(KEY + ": " + describe(value) + " is not a valid path");
      }
    }
    SyslogAuditSink.Transport transport;
    if (lower.startsWith("syslog://")) {
      transport = SyslogAuditSink.Transport.UDP;
    } else if (lower.startsWith("syslog+tcp://")) {
      transport = SyslogAuditSink.Transport.TCP;
    } else if (lower.startsWith("syslog+tls://")) {
      transport = SyslogAuditSink.Transport.TLS;
    } else {
      throw new IllegalArgumentException(
          KEY
              + ": unknown sink "
              + describe(value)
              + " (postgres, syslog://host:514, syslog+tcp://host:514, syslog+tls://host:6514 or"
              + " file:<path>)");
    }
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(KEY + ": " + describe(value) + " is not a valid address");
    }
    String host = uri.getHost();
    boolean extra =
        uri.getRawUserInfo() != null
            || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
            || uri.getRawQuery() != null
            || uri.getRawFragment() != null;
    if (host == null || host.isEmpty() || extra) {
      throw new IllegalArgumentException(
          KEY + ": " + describe(value) + " must be <scheme>://host[:port] without a path");
    }
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    int port = uri.getPort() < 0 ? transport.defaultPort() : uri.getPort();
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException(KEY + ": " + describe(value) + " has an invalid port");
    }
    return new Spec(Kind.SYSLOG, transport, host, port, null);
  }

  /**
   * Builds the sinks of {@code audit}: {@code primary} (the PostgreSQL sink beans) for {@code
   * postgres}, a {@link SyslogAuditSink} per syslog entry, a {@link JsonlAuditSink} per file entry,
   * and the fallback {@link JsonlAuditSink} of {@code argos.audit.fallback-file}.
   *
   * @throws IllegalArgumentException for an invalid configuration (the start is refused)
   */
  public static Configured configure(
      ArgosProperties.Audit audit, List<AuditSink> primary, MeterRegistry registry) {
    List<Spec> specs = parse(audit.sinks());
    long maxBytes = audit.file().maxBytes().toBytes();
    int maxFiles = audit.file().maxFiles();
    Path fallbackPath;
    try {
      fallbackPath = Path.of(audit.fallbackFile()).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      throw new IllegalArgumentException("argos.audit.fallback-file is not a valid path");
    }
    for (Spec s : specs) {
      if (s.kind() == Kind.FILE && s.path().equals(fallbackPath)) {
        throw new IllegalArgumentException(
            KEY + ": a file sink must not be argos.audit.fallback-file");
      }
    }
    List<AuditSink> sinks = new ArrayList<>();
    for (Spec s : specs) {
      if (s.kind() == Kind.POSTGRES) {
        sinks.addAll(primary);
      }
    }
    for (Spec s : specs) {
      switch (s.kind()) {
        case SYSLOG -> sinks.add(new SyslogAuditSink(s.transport(), s.host(), s.port(), registry));
        case FILE -> sinks.add(new JsonlAuditSink(s.path(), maxBytes, maxFiles));
        default -> {}
      }
    }
    return new Configured(sinks, new JsonlAuditSink(fallbackPath, maxBytes, maxFiles));
  }

  /** The value for an error message: at most 100 characters, control characters removed. */
  private static String describe(String value) {
    // A user part (never valid here) could be a credential: it is not repeated.
    String v =
        value == null ? "" : value.replaceAll("\\p{Cntrl}", "").replaceAll("//[^/@]*@", "//****@");
    return "'" + (v.length() > 100 ? v.substring(0, 100) + "..." : v) + "'";
  }
}
