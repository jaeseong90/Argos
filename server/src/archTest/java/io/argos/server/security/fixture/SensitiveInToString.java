/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.fixture;

import io.argos.server.auth.Sensitive;

/** Negative fixture: a {@code toString()} that reads the secret (never production code). */
public final class SensitiveInToString {

  @Sensitive private final String token;

  /** A holder of {@code token}. */
  public SensitiveInToString(String token) {
    this.token = token;
  }

  @Override
  public String toString() {
    return "SensitiveInToString[" + token + "]";
  }
}
