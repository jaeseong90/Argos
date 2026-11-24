/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.secrets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The master keys of this server (16 §8.2): the current one, which encrypts and decrypts, and the
 * decrypt-only keys of a rotation (the previous key of the configuration; {@code argos-admin
 * secrets rekey} also holds the key it rotates away from).
 *
 * <p>The server's ring is filled once by the {@code KEYS} start phase ({@code
 * io.argos.server.bootstrap.MasterKeyPhase}, ADR-0010 C-226 (2)), which refuses the start when the
 * configuration has no usable key ({@link MasterKeyLoader}). So in a running server {@link
 * #available()} is true; it is false only before that phase and in contexts that do not run the
 * start sequence (web-layer tests), where every operation that needs a secret answers 409 {@code
 * secret_unavailable} instead of failing differently. Keys appear as their ids only.
 */
@Component
public class MasterKeyRing implements DisposableBean {

  private volatile @Nullable MasterKey current;
  private volatile List<MasterKey> decryptOnly = List.of();

  /** An empty ring, filled by {@link #install}. */
  @Autowired
  public MasterKeyRing() {}

  /** A ring of the given keys (tests); either may be null. */
  public MasterKeyRing(@Nullable MasterKey current, @Nullable MasterKey previous) {
    this.current = current;
    this.decryptOnly = previous == null ? List.of() : List.of(previous);
  }

  /**
   * A ring that encrypts with {@code current} and also decrypts with {@code decryptOnly} (keys with
   * the current key's id are skipped).
   */
  public static MasterKeyRing of(MasterKey current, Collection<MasterKey> decryptOnly) {
    MasterKeyRing ring = new MasterKeyRing();
    ring.install(current, decryptOnly);
    return ring;
  }

  /**
   * Sets the keys (once).
   *
   * @throws IllegalStateException when the ring already holds keys
   */
  public synchronized void install(MasterKey current, Collection<MasterKey> decryptOnly) {
    if (this.current != null) {
      throw new IllegalStateException("The master key ring is already filled");
    }
    List<MasterKey> others = new ArrayList<>();
    for (MasterKey k : decryptOnly) {
      if (k != null
          && !k.kid().equals(current.kid())
          && others.stream().noneMatch(o -> o.kid().equals(k.kid()))) {
        others.add(k);
      }
    }
    this.decryptOnly = List.copyOf(others);
    this.current = current;
  }

  /** The key that encrypts, when one is loaded. */
  public Optional<MasterKey> current() {
    return Optional.ofNullable(current);
  }

  /** The key with id {@code kid} (current or decrypt-only), when loaded. */
  public Optional<MasterKey> find(String kid) {
    MasterKey c = current;
    if (c != null && c.kid().equals(kid)) {
      return Optional.of(c);
    }
    for (MasterKey k : decryptOnly) {
      if (k.kid().equals(kid)) {
        return Optional.of(k);
      }
    }
    return Optional.empty();
  }

  /** The ids of the decrypt-only keys. */
  public List<String> decryptOnlyKids() {
    return decryptOnly.stream().map(MasterKey::kid).toList();
  }

  /** Whether secrets can be stored now. */
  public boolean available() {
    return current != null;
  }

  /** Overwrites the keys with zeros at shutdown (16 §8.2 "메모리"). */
  @Override
  public void destroy() {
    MasterKey c = current;
    if (c != null) {
      c.close();
    }
    for (MasterKey k : decryptOnly) {
      k.close();
    }
  }
}
