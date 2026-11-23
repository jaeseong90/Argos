/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Masked forms of channel targets (12 §5.5, SEC-52): a URL keeps its scheme, host and port and
 * hides the rest ({@code https://hooks.slack.com/****}); a mail address keeps the first character
 * of its local part and its domain ({@code o***@example.com}). Recipients are validated here too.
 */
public final class ChannelMasking {

  /** Recipients per e-mail channel at most. */
  public static final int MAX_RECIPIENTS = 20;

  /** A plain address: no display name, no comments, no quoted local part. */
  private static final Pattern ADDRESS =
      Pattern.compile(
          "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
              + "@[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");

  private ChannelMasking() {}

  /** {@code <scheme>://<host>[:<port>]/****}; {@code ****} alone when the URL does not parse. */
  public static String maskUrl(String url) {
    try {
      URI uri = new URI(url);
      if (uri.getScheme() == null || uri.getHost() == null) {
        return "****";
      }
      String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
      return uri.getScheme().toLowerCase(Locale.ROOT)
          + "://"
          + uri.getHost().toLowerCase(Locale.ROOT)
          + port
          + "/****";
    } catch (URISyntaxException e) {
      return "****";
    }
  }

  /** The masked recipients, comma separated. */
  public static String maskRecipients(List<String> recipients) {
    List<String> masked = new ArrayList<>(recipients.size());
    for (String r : recipients) {
      int at = r.lastIndexOf('@');
      if (at <= 0) {
        masked.add("****");
      } else {
        masked.add(r.charAt(0) + "***" + r.substring(at).toLowerCase(Locale.ROOT));
      }
    }
    return String.join(", ", masked);
  }

  /**
   * The recipients of a target string ({@code a@example.com, b@example.com}), validated.
   *
   * @throws IllegalArgumentException with a reason code: {@code required}, {@code too_many}, {@code
   *     invalid_address}
   */
  public static List<String> parseRecipients(String target) {
    if (target == null || target.isBlank()) {
      throw new IllegalArgumentException("required");
    }
    List<String> out = new ArrayList<>();
    for (String part : target.split("[,;]", -1)) {
      String address = part.strip();
      if (address.isEmpty()) {
        continue;
      }
      if (address.length() > 254 || !ADDRESS.matcher(address).matches()) {
        throw new IllegalArgumentException("invalid_address");
      }
      if (!out.contains(address)) {
        out.add(address);
      }
    }
    if (out.isEmpty()) {
      throw new IllegalArgumentException("required");
    }
    if (out.size() > MAX_RECIPIENTS) {
      throw new IllegalArgumentException("too_many");
    }
    return out;
  }

  /** Whether {@code address} is a plain mail address (no CR, LF or display name). */
  public static boolean validAddress(String address) {
    return address != null && address.length() <= 254 && ADDRESS.matcher(address).matches();
  }
}
