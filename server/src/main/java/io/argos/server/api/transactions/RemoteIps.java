/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client addresses of the transaction API (14 §4.6 {@code remote_ip}, §7.3; 02 §5.2, SEC-31). The
 * {@code xlog.remote_ip} column is IPv6 (an IPv4 address as {@code ::ffff:a.b.c.d}, {@code ::} when
 * the mode is {@code off}). The agent's {@code masked} mode zeroes the last IPv4 octet (/24) or the
 * low 80 bits of an IPv6 address (/48), so a filter matches masked rows by the masked form of the
 * searched address.
 *
 * <p>Parsing is for literals only: a value that is not an IPv4 dotted quad or an IPv6 literal is
 * refused before any {@link InetAddress} call, so no name is ever resolved (no outbound lookup).
 */
public final class RemoteIps {

  private static final Pattern IPV4 =
      Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

  /** IPv6 literal characters, starting like a literal (so the JDK parses it, never resolves it). */
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]{1,44}");

  private RemoteIps() {}

  /**
   * A searched address.
   *
   * @param exact the address as ClickHouse IPv6 text (an IPv4 address mapped)
   * @param masked the masked form of the same address
   */
  public record Filter(String exact, String masked) {}

  /**
   * The filter of {@code text}.
   *
   * @throws ApiException 400 {@code filter_invalid} ({@code remote_ip/invalid_format})
   */
  public static Filter filter(String text) {
    byte[] address = parse(text);
    byte[] v6 = toV6(address);
    byte[] masked = v6.clone();
    if (isMappedV4(v6)) {
      masked[15] = 0;
    } else {
      for (int i = 6; i < 16; i++) {
        masked[i] = 0;
      }
    }
    return new Filter(v6Text(v6), v6Text(masked));
  }

  /**
   * The display form of a stored address: dotted for a mapped IPv4 address, the IPv6 text
   * otherwise, null for {@code ::} (mode {@code off}) or an empty value.
   */
  public static String display(String stored) {
    if (stored == null || stored.isEmpty() || stored.equals("::")) {
      return null;
    }
    String lower = stored.toLowerCase(java.util.Locale.ROOT);
    if (lower.startsWith("::ffff:") && IPV4.matcher(lower.substring(7)).matches()) {
      return lower.substring(7);
    }
    return stored;
  }

  private static byte[] parse(String text) {
    if (text == null) {
      throw invalid();
    }
    String t = text.strip();
    Matcher v4 = IPV4.matcher(t);
    if (v4.matches()) {
      byte[] out = new byte[4];
      for (int i = 0; i < 4; i++) {
        int octet = Integer.parseInt(v4.group(i + 1));
        if (octet > 255) {
          throw invalid();
        }
        out[i] = (byte) octet;
      }
      return out;
    }
    if (!IPV6.matcher(t).matches() || t.indexOf(':') < 0) {
      throw invalid();
    }
    try {
      // A literal with ':' is parsed as an IPv6 literal; nothing is resolved.
      InetAddress a = InetAddress.getByName(t);
      return a.getAddress();
    } catch (UnknownHostException | SecurityException e) {
      throw invalid();
    }
  }

  private static byte[] toV6(byte[] address) {
    if (address.length == 16) {
      return address;
    }
    byte[] v6 = new byte[16];
    v6[10] = (byte) 0xff;
    v6[11] = (byte) 0xff;
    System.arraycopy(address, 0, v6, 12, 4);
    return v6;
  }

  private static boolean isMappedV4(byte[] v6) {
    for (int i = 0; i < 10; i++) {
      if (v6[i] != 0) {
        return false;
      }
    }
    return v6[10] == (byte) 0xff && v6[11] == (byte) 0xff;
  }

  private static String v6Text(byte[] v6) {
    if (isMappedV4(v6)) {
      return "::ffff:"
          + (v6[12] & 0xff)
          + "."
          + (v6[13] & 0xff)
          + "."
          + (v6[14] & 0xff)
          + "."
          + (v6[15] & 0xff);
    }
    try {
      InetAddress a = InetAddress.getByAddress(v6);
      return a instanceof Inet4Address ? "::ffff:" + a.getHostAddress() : a.getHostAddress();
    } catch (UnknownHostException e) {
      throw invalid();
    }
  }

  private static ApiException invalid() {
    return new ApiException(ErrorCode.FILTER_INVALID, "remote_ip is not an IP address literal")
        .detail("remote_ip", "invalid_format");
  }
}
