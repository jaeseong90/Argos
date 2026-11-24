/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.transport.AgentSession;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code capture.user-id.*} (16 §6.1, §6.3, SEC-32, ADR-0010 C-125): what the agent records of the
 * user id that the servlet helper read from {@code capture.user-id.source}.
 *
 * <ul>
 *   <li>{@code off} (default): nothing; the helper does not even read the source.
 *   <li>{@code plain}: control characters removed, cut at 128 characters ({@link
 *       CaptureConfig#userIdMaxLength()}), trimmed ({@link #sanitize}).
 *   <li>{@code hashed}: the first {@value #HASH_CHARS} characters of base64url(HMAC-SHA256(salt,
 *       user id)) (unpadded RFC 4648 §5 alphabet: {@code A-Z a-z 0-9 - _}), with the app's salt of
 *       {@code ServerHello.user_id_salt} (16 §6.3: the same id gives the same hash on every
 *       instance). The HMAC input is the <b>normalized</b> id, the same value {@code plain} would
 *       record: ISO control characters removed, cut at 128 characters (never inside a surrogate
 *       pair), leading and trailing whitespace removed, then encoded as UTF-8 (a space, {@code kim}
 *       and a BEL character hash as {@code kim}). A search by user id (S-12) must normalize its
 *       input the same way before the HMAC. Without a salt (the collector sends it empty until
 *       WO-26/WO-28) the user id is <b>not</b> recorded, never sent in plain text instead: counted
 *       as {@code argos_agent_user_id_skipped_total{reason="no_salt"}} with one WARN; once a salt
 *       arrives the ids are hashed from that request on.
 *   <li>Source {@code mdc:<key>} (ADR-0010 C-125 (3), WO-18): the logging module reads the key from
 *       the MDC of each log event the transaction creates (Logback, Log4j 2, Log4j 1.2) and hands
 *       the value to {@code Tracer.setUserId}; the first usable value decides, with the rules
 *       above. A transaction that ends without one (no log event carried the key, or the logging
 *       module is off) records no user id and counts {@code reason="mdc_absent"} ({@link #atEnd}),
 *       without a WARN: most transactions of an application log nothing.
 * </ul>
 *
 * <p>The salt stays in memory (the session's copy and the key of the cached {@link Mac}); it is
 * never logged, and no message here carries a user id. Thread-safe and lock-free: the keyed {@link
 * Mac} is built once per salt and cloned per hash ({@code Mac} instances are not thread-safe).
 */
public final class UserIds {
  /** Characters of a hashed user id (16 §6.3 "앞 16자"). */
  public static final int HASH_CHARS = 16;

  static final String MAC_ALGORITHM = "HmacSHA256";

  /** Where the app's user id salt comes from. */
  public interface SaltSource {
    /** The current salt; empty (or null) when none is known. Must not block. */
    byte[] salt();
  }

  /** The salt of the running transport's session ({@link AgentSession#userIdSalt()}). */
  public static final SaltSource SESSION =
      new SaltSource() {
        @Override
        public byte[] salt() {
          AgentSession session = AgentSession.current();
          return session == null ? null : session.userIdSalt();
        }
      };

  private static final char[] BASE64URL =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

  private final AgentCounters counters;
  private final SaltSource salts;
  private volatile Keyed keyed;

  public UserIds(AgentCounters counters, SaltSource salts) {
    this.counters = counters;
    this.salts = salts == null ? SESSION : salts;
  }

  /**
   * Applies {@code capture} to {@code raw} for the transaction {@code tx}: records the user id to
   * send, records the decision to send none (counted here), or does nothing when the source gave
   * nothing usable yet (a later call of the same transaction may still decide). Only the first
   * decision of a transaction counts ({@link Transaction#userIdDecided()}).
   */
  void apply(Transaction tx, CaptureConfig capture, String raw) {
    if (tx.userIdDecided()) {
      return;
    }
    int mode = capture.userIdMode();
    if (mode != CaptureConfig.USER_ID_PLAIN && mode != CaptureConfig.USER_ID_HASHED) {
      return;
    }
    String value = sanitize(raw, capture.userIdMaxLength());
    if (value == null) {
      return;
    }
    if (mode == CaptureConfig.USER_ID_PLAIN) {
      tx.decideUserId(value);
      return;
    }
    String hashed = hash(value);
    tx.decideUserId(hashed);
    if (hashed == null) {
      counters.incUserIdSkipped(AgentCounters.USER_ID_SKIPPED_NO_SALT);
      AgentLogger.warnOnce(
          "user-id.no-salt",
          "capture.user-id.mode is hashed but the collector sent no user id salt; user ids are not"
              + " recorded until it does");
    }
  }

  /**
   * At the end of {@code tx}: with {@code capture.user-id.source: mdc:<key>} and a mode that
   * records user ids, a transaction that no log event gave a usable value counts {@code
   * argos_agent_user_id_skipped_total{reason="mdc_absent"}} (ADR-0010 C-125 (3)). Other sources
   * count nothing here.
   */
  void atEnd(Transaction tx, CaptureConfig capture) {
    if (tx.userIdDecided()
        || !CaptureConfig.USER_ID_SOURCE_MDC.equals(capture.userIdSourceType())) {
      return;
    }
    int mode = capture.userIdMode();
    if (mode == CaptureConfig.USER_ID_PLAIN || mode == CaptureConfig.USER_ID_HASHED) {
      counters.incUserIdSkipped(AgentCounters.USER_ID_SKIPPED_MDC_ABSENT);
    }
  }

  /**
   * {@code raw} without ISO control characters, cut at {@code max} characters (never inside a
   * surrogate pair) and trimmed; null when nothing is left (16 §6.3 "길이 128 절단, 제어 문자 제거").
   */
  static String sanitize(String raw, int max) {
    if (raw == null) {
      return null;
    }
    int limit = Math.max(1, max);
    StringBuilder out = new StringBuilder(Math.min(raw.length(), limit));
    for (int i = 0; i < raw.length() && out.length() < limit; i++) {
      char c = raw.charAt(i);
      if (!Character.isISOControl(c)) {
        out.append(c);
      }
    }
    int length = out.length();
    if (length > 0 && Character.isHighSurrogate(out.charAt(length - 1))) {
      out.setLength(length - 1);
    }
    String value = out.toString().trim();
    return value.isEmpty() ? null : value;
  }

  /** The hashed form of {@code value}, or null when no salt is known. */
  String hash(String value) {
    byte[] salt = salts.salt();
    if (salt == null || salt.length == 0) {
      return null;
    }
    try {
      Keyed current = keyed;
      if (current == null || !Arrays.equals(current.salt, salt)) {
        current = new Keyed(salt);
        keyed = current;
      }
      byte[] digest = current.copy().doFinal(value.getBytes(StandardCharsets.UTF_8));
      return base64Url(digest, HASH_CHARS);
    } catch (Exception e) {
      AgentLogger.warnOnce(
          "user-id.hash", "user id hashing (HmacSHA256) failed; user ids are not recorded");
      return null;
    }
  }

  /** The first {@code chars} characters of the unpadded base64url encoding of {@code bytes}. */
  static String base64Url(byte[] bytes, int chars) {
    StringBuilder out = new StringBuilder(chars + 4);
    int i = 0;
    while (out.length() < chars && i < bytes.length) {
      int b0 = bytes[i] & 0xff;
      int b1 = i + 1 < bytes.length ? bytes[i + 1] & 0xff : 0;
      int b2 = i + 2 < bytes.length ? bytes[i + 2] & 0xff : 0;
      int remaining = bytes.length - i;
      out.append(BASE64URL[b0 >>> 2]);
      out.append(BASE64URL[((b0 & 0x3) << 4) | (b1 >>> 4)]);
      if (remaining > 1) {
        out.append(BASE64URL[((b1 & 0xf) << 2) | (b2 >>> 6)]);
      }
      if (remaining > 2) {
        out.append(BASE64URL[b2 & 0x3f]);
      }
      i += 3;
    }
    return out.length() > chars ? out.substring(0, chars) : out.toString();
  }

  /** A {@link Mac} keyed with one salt; {@link #copy()} gives each hash its own instance. */
  private static final class Keyed {
    final byte[] salt;
    private final Mac prototype;

    Keyed(byte[] salt) throws Exception {
      this.salt = salt.clone();
      Mac mac = Mac.getInstance(MAC_ALGORITHM);
      mac.init(new SecretKeySpec(this.salt, MAC_ALGORITHM));
      this.prototype = mac;
    }

    Mac copy() throws Exception {
      try {
        return (Mac) prototype.clone();
      } catch (CloneNotSupportedException e) {
        Mac mac = Mac.getInstance(MAC_ALGORITHM);
        mac.init(new SecretKeySpec(salt, MAC_ALGORITHM));
        return mac;
      }
    }
  }
}
