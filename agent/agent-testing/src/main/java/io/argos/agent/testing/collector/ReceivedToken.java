/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * A token a {@link FakeCollector} received in the {@code authorization: Bearer <token>} metadata
 * (11 §2). The value never leaves this object as text: {@link #toString()} is the mask {@code
 * argos_****} (11 §11, SEC-06, ADR-0010 C-94), so records that hold a token ({@link SessionInfo},
 * {@link ReceivedBatch}) and AssertJ failure messages print the mask only. Tests compare with
 * {@link #matches(String)} or {@link #equals} against {@link #of(String)}.
 */
public final class ReceivedToken {
  /** The agent token mask (ADR-0010 C-94). */
  public static final String MASK = "argos_****";

  /** {@code argos_<base64url>} (11 §2, 12 §3.1 prefix check). */
  private static final Pattern WELL_FORMED = Pattern.compile("argos_[A-Za-z0-9_-]+");

  private final String value;

  private ReceivedToken(String value) {
    this.value = value;
  }

  /** A token value (for comparisons with received ones). */
  public static ReceivedToken of(String value) {
    if (value == null) {
      throw new IllegalArgumentException("token value is null");
    }
    return new ReceivedToken(value);
  }

  /**
   * The token of an {@code authorization} metadata value: the text after {@code Bearer }, or null
   * when the header is missing or does not use the Bearer scheme.
   */
  public static ReceivedToken fromAuthorization(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      return null;
    }
    return new ReceivedToken(authorization.substring("Bearer ".length()).strip());
  }

  /** True for {@code argos_<base64url>} (11 §2). */
  public boolean wellFormed() {
    return WELL_FORMED.matcher(value).matches();
  }

  /** Constant-time comparison with {@code expected}. */
  public boolean matches(String expected) {
    return expected != null
        && MessageDigest.isEqual(
            value.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
  }

  /** Length of the value in characters. */
  public int length() {
    return value.length();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ReceivedToken token && matches(token.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  /** Always the mask, never the value. */
  @Override
  public String toString() {
    return MASK;
  }
}
