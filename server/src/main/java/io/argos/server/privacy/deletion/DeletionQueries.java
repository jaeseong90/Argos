/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.privacy.deletion;

import io.argos.server.ops.migration.SchemaParameters;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The ClickHouse statements of one privacy deletion (13 §9, 16 §6.8; ADR-0007 C-66: {@code ALTER
 * TABLE ... DELETE IN PARTITION}, so parts of other days are never rewritten). Pure text building,
 * unit-tested.
 *
 * <p>The subject value never appears as readable text in a statement: a user id is written as
 * {@code unhex('<hex of its UTF-8 bytes>')} (no quoting or escaping of request input at all), an
 * address as the canonical text of a parsed IP literal ({@code toIPv6('192.0.2.1')}). The condition
 * of a user id covers both storage modes of 16 §6.3: rows of {@code plain} mode with the value
 * itself, rows of {@code hashed} mode with the application's HMAC of it (one alternative per
 * application with a salt). The condition of an address covers {@code full} rows with the address
 * and {@code masked} rows with its masked form (the last IPv4 octet or the low 80 IPv6 bits zero,
 * as the agent masks, 10 §15), which match every address of that /24 or /48 network.
 */
public final class DeletionQueries {

  private static final DateTimeFormatter DAY_ID = DateTimeFormatter.BASIC_ISO_DATE;
  private static final HexFormat HEX = HexFormat.of();
  private static final Pattern HASH = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private DeletionQueries() {}

  /** The subject of a deletion. */
  public sealed interface Subject permits UserId, Ip {}

  /**
   * A user id.
   *
   * @param normalized the normalised value (C-163 (2))
   * @param hashedByApp the application's HMAC of it by application id (16 §6.3)
   */
  public record UserId(String normalized, Map<Integer, String> hashedByApp) implements Subject {

    public UserId {
      hashedByApp = Map.copyOf(hashedByApp);
      for (String h : hashedByApp.values()) {
        if (!HASH.matcher(h).matches()) {
          throw new IllegalArgumentException("Not a hashed user id");
        }
      }
    }
  }

  /**
   * A client address.
   *
   * @param address the parsed IP literal
   */
  public record Ip(InetAddress address) implements Subject {}

  /**
   * The row condition of a subject (and application) on {@code xlog}.
   *
   * @param appId the application, or null for every application
   */
  public static String condition(Subject subject, @Nullable Integer appId) {
    String scope = appId == null ? "" : "app_id = " + appId + " AND ";
    return switch (subject) {
      case UserId u -> scope + userIdCondition(u);
      case Ip ip -> scope + ipCondition(ip.address());
    };
  }

  private static String userIdCondition(UserId u) {
    String plain =
        "(user_id_mode = 'plain' AND user_id = unhex('"
            + HEX.formatHex(u.normalized().getBytes(StandardCharsets.UTF_8))
            + "'))";
    if (u.hashedByApp().isEmpty()) {
      return plain;
    }
    StringJoiner hashed = new StringJoiner(" OR ", "(user_id_mode = 'hashed' AND (", "))");
    u.hashedByApp().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            e ->
                hashed.add(
                    "(app_id = "
                        + e.getKey()
                        + " AND user_id = unhex('"
                        + HEX.formatHex(e.getValue().getBytes(StandardCharsets.US_ASCII))
                        + "'))"));
    return "(" + plain + " OR " + hashed + ")";
  }

  private static String ipCondition(InetAddress address) {
    return "((ip_mode = 'full' AND remote_ip = toIPv6('"
        + text(address)
        + "')) OR (ip_mode = 'masked' AND remote_ip = toIPv6('"
        + text(masked(address))
        + "')))";
  }

  /** The masked form of an address (10 §15: last IPv4 octet, low 80 IPv6 bits). */
  public static InetAddress masked(InetAddress address) {
    byte[] b = address.getAddress();
    if (address instanceof Inet4Address) {
      b[3] = 0;
    } else {
      for (int i = 6; i < 16; i++) {
        b[i] = 0;
      }
    }
    try {
      return InetAddress.getByAddress(b);
    } catch (java.net.UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The canonical literal of an address (digits, hex, dots and colons only). */
  static String text(InetAddress address) {
    String s = address.getHostAddress();
    if (address instanceof Inet6Address) {
      int zone = s.indexOf('%');
      if (zone >= 0) {
        s = s.substring(0, zone);
      }
    }
    return s;
  }

  /** {@code SELECT count()} of the matching {@code xlog} rows of one day. */
  public static String countXlog(SchemaParameters p, LocalDate day, String condition) {
    return "SELECT toString(count()) FROM "
        + p.table("xlog")
        + " WHERE toDate(ts) = '"
        + day
        + "' AND "
        + condition;
  }

  /** {@code SELECT count()} of the matching {@code xlog} rows of masked instances of one day. */
  public static String countMaskedXlog(SchemaParameters p, LocalDate day, String condition) {
    return countXlog(p, day, condition) + " AND ip_mode = 'masked'";
  }

  /** {@code SELECT count()} of the {@code profile_step} rows of the matching transactions. */
  public static String countProfile(SchemaParameters p, LocalDate day, String condition) {
    return "SELECT toString(count()) FROM "
        + p.table("profile_step")
        + " WHERE toDate(ts) = '"
        + day
        + "' AND "
        + transactions(p, day, condition);
  }

  /**
   * The {@code profile_step} mutation of one day: the steps of the matching transactions. Issued
   * and finished before the {@code xlog} mutation of the day (its subquery reads {@code xlog}, 13
   * §9).
   */
  public static String deleteProfile(SchemaParameters p, LocalDate day, String condition) {
    return "ALTER TABLE "
        + p.table("profile_step")
        + " DELETE IN PARTITION ID '"
        + DAY_ID.format(day)
        + "' WHERE "
        + transactions(p, day, condition);
  }

  /** The {@code xlog} mutation of one day. */
  public static String deleteXlog(SchemaParameters p, LocalDate day, String condition) {
    return "ALTER TABLE "
        + p.table("xlog")
        + " DELETE IN PARTITION ID '"
        + DAY_ID.format(day)
        + "' WHERE "
        + condition;
  }

  private static String transactions(SchemaParameters p, LocalDate day, String condition) {
    return "(app_id, tx_id) IN (SELECT app_id, tx_id FROM "
        + p.table("xlog")
        + " WHERE toDate(ts) = '"
        + day
        + "' AND "
        + condition
        + ")";
  }

  /** The days of {@code [from, to)} as UTC dates, oldest first. */
  public static List<LocalDate> days(LocalDate first, LocalDate last) {
    return first.datesUntil(last.plusDays(1)).toList();
  }
}
