/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.seed;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PII, secret and XSS seed values of 18 §4.3 (DSN-18-09). Every seed contains the sentinel {@value
 * #SENTINEL}, so one {@code LIKE '%ARGOS_SEED%'} finds a leak anywhere. Documented exceptions: the
 * {@code *_PATTERN} values (realistic shapes without the sentinel, for the masking pattern tests)
 * and {@link #ACCESS_KEY_LIKE}, which carries {@code ARGOSSEED} instead of {@code ARGOS_SEED} (the
 * 18 §4.3 value; an access key has no underscore): leak scans must also search {@code ARGOSSEED}.
 *
 * <p>Exception messages (ADR-0010 C-250 (1)): the agent masks a message with the regular
 * expressions of SEC-36 only (email, Korean mobile number, resident number, Luhn card, IPv4), so a
 * phone or card seed in a message must have the real shape, or it tests no masking rule. The phone
 * and card exception seeds are therefore the fixed real-shaped sentinels {@link #PHONE_PATTERN} and
 * {@link #CARD_PATTERN} (Luhn-valid); query-string and bind seeds keep {@code ARGOS_SEED}. Leak
 * scans search every value of {@link #LEAK_SENTINELS}. Seed values are test data only; none of them
 * is a real person, card or credential.
 */
public final class Seeds {

  /** The sentinel substring of every seed. */
  public static final String SENTINEL = "ARGOS_SEED";

  public static final String EMAIL = SENTINEL + ".kim@example.test";
  public static final String PHONE = "010-" + SENTINEL + "-0001";
  public static final String PHONE_PATTERN = "010-9999-0001";
  public static final String RESIDENT_NUMBER = "990101-1" + SENTINEL;
  public static final String RESIDENT_NUMBER_PATTERN = "990101-1234567";
  public static final String CARD = "4111-1111-" + SENTINEL;
  public static final String CARD_PATTERN = "4111111111111111";
  public static final String USER_ID = "user-" + SENTINEL + "-42";
  public static final String FORWARDED_FOR = "203.0.113." + SENTINEL;
  public static final String JWT_LIKE = "eyJhbGciOi." + SENTINEL + ".sig";

  /**
   * Access key shape of 18 §4.3. Documented exception: carries {@code ARGOSSEED} instead of {@code
   * ARGOS_SEED} (18 §4.3 value); leak scans must also search {@code ARGOSSEED}. Planted in the
   * {@code X-Api-Key} header only (not in {@link #environment()}).
   */
  public static final String ACCESS_KEY_LIKE = "AKIAARGOSSEED0000001";

  /** The sentinel of {@link #ACCESS_KEY_LIKE} (no underscore, ADR-0004 C-44). */
  public static final String ACCESS_KEY_SENTINEL = "ARGOSSEED";

  /**
   * Every string a leak scan must find nowhere (T-NFR-10c, ADR-0010 C-250 (1)): {@value #SENTINEL},
   * {@value #ACCESS_KEY_SENTINEL} and the real-shaped values {@value #PHONE_PATTERN}, {@value
   * #RESIDENT_NUMBER_PATTERN} and {@value #CARD_PATTERN}. The seed sender (the catalog, {@link
   * SeedService}) and the PII seed grep both use this list.
   */
  public static final List<String> LEAK_SENTINELS =
      Collections.unmodifiableList(
          Arrays.asList(
              SENTINEL, ACCESS_KEY_SENTINEL, PHONE_PATTERN, RESIDENT_NUMBER_PATTERN, CARD_PATTERN));

  /** Exception message of {@code GET /seed/phone?fail=1}: the real-shaped phone sentinel. */
  public static final String PHONE_ERROR_MESSAGE = "payment declined for phone " + PHONE_PATTERN;

  /** Exception message of {@code GET /seed/card?fail=1}: the Luhn-valid card sentinel. */
  public static final String CARD_ERROR_MESSAGE = "card " + CARD_PATTERN + " declined";

  /** Statement literals written as SQL (Korean text, doubled quote, dollar quoting). */
  public static final List<String> SQL_LITERALS =
      Collections.unmodifiableList(
          Arrays.asList(
              "'홍길동" + SENTINEL + "'", // '홍길동ARGOS_SEED'
              "'O''Brien " + SENTINEL + "'",
              "$$" + SENTINEL + "$$"));

  /** XSS payloads of 18 §4.3, each followed by the sentinel so that leaks are found as well. */
  public static final List<String> XSS =
      Collections.unmodifiableList(
          Arrays.asList(
              "<img src=x onerror=\"window.__xss=1\">" + SENTINEL,
              "\"><svg/onload=window.__xss=1>" + SENTINEL,
              "javascript:window.__xss='" + SENTINEL + "'",
              "<script>window.__xss=1</script>" + SENTINEL));

  /** CSV formula payloads of 18 §4.3 (sentinel appended where the payload has none). */
  public static final List<String> CSV =
      Collections.unmodifiableList(
          Arrays.asList(
              "=HYPERLINK(\"http://x\",\"" + SENTINEL + "\")",
              "+cmd|' /C calc'!A0 " + SENTINEL,
              "-1+1 " + SENTINEL,
              "@SUM(1) " + SENTINEL));

  private Seeds() {}

  /**
   * Secret-looking environment variables of the app process (environment dump tests, SEC-20). The
   * harness sets them; the app only reports which ones it sees (GET /seed/env), never their values.
   */
  public static Map<String, String> environment() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("DB_PASSWORD", SENTINEL + "_pw");
    env.put("AWS_SECRET_ACCESS_KEY", SENTINEL + "AKIA" + "EXAMPLEONLY");
    env.put("ARGOS_TOKEN", JWT_LIKE);
    env.put("JAVA_TOOL_OPTIONS", "-Dsecret=" + SENTINEL);
    return env;
  }

  /** The payload at {@code index} of {@code payloads}; 400-style failure for a bad index. */
  public static String pick(List<String> payloads, int index) {
    if (index < 0 || index >= payloads.size()) {
      throw new IllegalArgumentException(
          "i must be between 0 and " + (payloads.size() - 1) + ", got " + index);
    }
    return payloads.get(index);
  }
}
