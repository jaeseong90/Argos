/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The webhook signature (16 §8.4, SEC-53):
 *
 * <pre>
 * X-Argos-Signature: t=&lt;unix seconds&gt;,v1=&lt;hex(HMAC-SHA256(key, t + "." + body))&gt;[,v1=...]
 * </pre>
 *
 * The key is the signing secret as shown once at channel creation or rotation (43 base64url
 * characters), used as its UTF-8 bytes. During the 7-day grace after a rotation the header carries
 * one {@code v1=} per valid key, newest first; a receiver accepts the request when any of them
 * matches its key.
 *
 * <p>Receiver check ({@link #verify}): parse {@code t} and every {@code v1}, refuse when {@code t}
 * is more than 5 minutes away from the receiver's clock (replay window), compute the HMAC of {@code
 * t + "." + body} with the raw request body and compare in constant time.
 */
public final class WebhookSignature {

  /** The header name. */
  public static final String HEADER = "X-Argos-Signature";

  /** The replay tolerance of the receiver example (16 §8.4). */
  public static final Duration TOLERANCE = Duration.ofMinutes(5);

  private WebhookSignature() {}

  /**
   * The header value for {@code body} at {@code time}.
   *
   * @param secrets the valid signing secrets, newest first (one, or two during a rotation grace)
   */
  public static String header(List<String> secrets, Instant time, byte[] body) {
    if (secrets.isEmpty()) {
      throw new IllegalArgumentException("no signing secret");
    }
    long t = time.getEpochSecond();
    StringBuilder sb = new StringBuilder("t=").append(t);
    for (String secret : secrets) {
      sb.append(",v1=").append(hex(hmac(secret, t, body)));
    }
    return sb.toString();
  }

  /**
   * The receiver's check (16 §8.4 example): true when {@code header} is well formed, its {@code t}
   * is within {@link #TOLERANCE} of {@code now} and one of its {@code v1} values is the HMAC of
   * {@code t + "." + body} under {@code secret}.
   */
  public static boolean verify(String header, String secret, byte[] body, Instant now) {
    if (header == null) {
      return false;
    }
    Long t = null;
    List<String> signatures = new ArrayList<>();
    for (String part : header.split(",", -1)) {
      int eq = part.indexOf('=');
      if (eq <= 0) {
        return false;
      }
      String name = part.substring(0, eq).strip();
      String value = part.substring(eq + 1).strip();
      if ("t".equals(name)) {
        try {
          t = Long.parseLong(value);
        } catch (NumberFormatException e) {
          return false;
        }
      } else if ("v1".equals(name)) {
        signatures.add(value);
      }
    }
    if (t == null || signatures.isEmpty()) {
      return false;
    }
    if (Math.abs(now.getEpochSecond() - t) > TOLERANCE.toSeconds()) {
      return false;
    }
    byte[] expected = hex(hmac(secret, t, body)).getBytes(StandardCharsets.US_ASCII);
    boolean match = false;
    for (String candidate : signatures) {
      // Constant time per candidate; every candidate is compared.
      match |= MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII));
    }
    return match;
  }

  /** {@code HMAC-SHA256(secret, t + "." + body)}. */
  static byte[] hmac(String secret, long t, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((t + ".").getBytes(StandardCharsets.US_ASCII));
      return mac.doFinal(body);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
