/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.token;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The keys of the access tokens: the current signing key and, during a rotation, the previous key
 * whose tokens are still accepted until they expire (16 §7.1 "키 교체는 kid로 두 키 동시 수용"). {@link
 * JwtKeyPhase} installs them in the {@code KEYS} start phase, before any listener accepts a
 * request; until then every use fails.
 */
@Component
public class JwtKeyRing {

  private volatile JwtKey signing;
  private volatile Map<String, JwtKey> verifying = Map.of();

  /**
   * Installs the keys.
   *
   * @param current the signing key (must be able to sign)
   * @param previous the previous key, or null
   * @throws IllegalArgumentException when both keys have the same id
   */
  public void install(JwtKey current, JwtKey previous) {
    Objects.requireNonNull(current, "current");
    if (!current.canSign()) {
      throw new IllegalArgumentException("the signing key must be a private key");
    }
    Map<String, JwtKey> keys = new LinkedHashMap<>();
    keys.put(current.kid(), current);
    if (previous != null) {
      if (previous.kid().equals(current.kid())) {
        throw new IllegalArgumentException("the previous key is the current key");
      }
      keys.put(previous.kid(), previous);
    }
    this.verifying = Map.copyOf(keys);
    this.signing = current;
  }

  /** The signing key. */
  public JwtKey signingKey() {
    JwtKey key = signing;
    if (key == null) {
      throw new IllegalStateException("the JWT keys are not loaded yet (KEYS start phase)");
    }
    return key;
  }

  /** The key with this id, if it is the current or the previous key. */
  public Optional<JwtKey> verificationKey(String kid) {
    if (kid == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(verifying.get(kid));
  }

  /** Whether the keys are installed. */
  public boolean loaded() {
    return signing != null;
  }
}
