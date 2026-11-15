/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.server.auth.AuthTestProperties;
import io.argos.server.bootstrap.StartupPhaseException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The signing key and its {@code KEYS} start phase (16 §7.1, DSN-12-15): text forms, the file
 * created at the first start, {@code ARGOS_JWT_KEY}, the previous key of a rotation. Every key is
 * generated at run time.
 */
@Tag("req:SEC-40")
class JwtKeyTest {

  private static final byte[] PROBE = "probe".getBytes(StandardCharsets.US_ASCII);

  @TempDir Path dir;

  private JwtKeyRing run(Map<String, Object> settings) throws StartupPhaseException {
    JwtKeyRing ring = new JwtKeyRing();
    new JwtKeyPhase(AuthTestProperties.of(settings), ring).run();
    return ring;
  }

  @Test
  @DisplayName("T-SEC-40a a PEM key parses back to the same key: same kid, signatures verify")
  void pemRoundTrip() {
    JwtKey key = JwtKey.generate();
    JwtKey parsed = JwtKey.parse(key.toPem(), false);
    assertThat(parsed.kid()).isEqualTo(key.kid()).hasSize(16);
    assertThat(parsed.canSign()).isTrue();
    assertThat(key.verify(PROBE, parsed.sign(PROBE))).isTrue();
    assertThat(key.toString()).contains(key.kid()).doesNotContain(key.toPem());
  }

  @Test
  @DisplayName("T-SEC-40a the base64 of a raw 32-byte seed (installer, 16 §10.6) is a valid key")
  void rawSeed() {
    byte[] seed = new byte[32];
    new SecureRandom().nextBytes(seed);
    String text = Base64.getEncoder().encodeToString(seed);
    JwtKey one = JwtKey.parse(text, false);
    JwtKey two = JwtKey.parse(text, false);
    assertThat(one.canSign()).isTrue();
    assertThat(one.kid()).isEqualTo(two.kid());
    assertThat(two.verify(PROBE, one.sign(PROBE))).isTrue();
  }

  @Test
  @DisplayName("T-SEC-40a a public key verifies only, and is accepted only for the previous key")
  void publicKeyOnly() throws Exception {
    var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String pem =
        "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----\n";
    JwtKey key = JwtKey.parse(pem, true);
    assertThat(key.canSign()).isFalse();
    assertThatThrownBy(() -> key.sign(PROBE)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> JwtKey.parse(pem, false)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("T-SEC-40a garbage is refused and the message holds no key material")
  void garbage() {
    String secret = "x".repeat(40);
    assertThatThrownBy(() -> JwtKey.parse("", false)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JwtKey.parse(secret, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(secret);
    assertThatThrownBy(() -> JwtKey.parse("A".repeat(20_000), false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JwtKey.parse(Base64.getEncoder().encodeToString(new byte[31]), false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "T-SEC-40a the first start creates conf/jwt.key without group or others access; the next start"
          + " uses the same key")
  void createdFile() throws Exception {
    Path file = dir.resolve("conf").resolve("jwt.key");
    JwtKeyRing first = run(Map.of("argos.jwt-key-file", file.toString()));
    assertThat(file).exists();
    assertThat(PrivateFiles.loosePermissions(file)).isEmpty();
    assertThat(Files.readString(file)).startsWith("-----BEGIN PRIVATE KEY-----");
    JwtKeyRing second = run(Map.of("argos.jwt-key-file", file.toString()));
    assertThat(second.signingKey().kid()).isEqualTo(first.signingKey().kid());
    assertThat(second.loaded()).isTrue();
  }

  @Test
  @DisplayName(
      "T-SEC-40a on POSIX the first start creates conf/jwt.key rw------- in a new rwx------ conf"
          + " directory (skipped where the file system has no POSIX permissions)")
  void createdFileOwnerOnlyOnPosix() throws Exception {
    Path file = dir.resolve("conf").resolve("jwt.key");
    assumeTrue(
        PrivateFiles.posix(file), "the file system has no POSIX permissions (e.g. Windows NTFS)");
    run(Map.of("argos.jwt-key-file", file.toString()));
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        .isEqualTo("rw-------");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())))
        .isEqualTo("rwx------");
  }

  @Test
  @DisplayName("T-SEC-40a an unusable key file stops the start and is not overwritten")
  void brokenFile() throws Exception {
    Path file = dir.resolve("jwt.key");
    Files.writeString(file, "not a key");
    assertThatThrownBy(() -> run(Map.of("argos.jwt-key-file", file.toString())))
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageContaining("argos.jwt-key-file")
        .hasMessageNotContaining("not a key");
    assertThat(Files.readString(file)).isEqualTo("not a key");
  }

  @Test
  @DisplayName("T-SEC-40a ARGOS_JWT_KEY wins over the file; a previous key is verify-only")
  void valueAndPrevious() throws Exception {
    JwtKey value = JwtKey.generate();
    JwtKey previous = JwtKey.generate();
    Path file = dir.resolve("untouched.key");
    JwtKeyRing ring =
        run(
            Map.of(
                "argos.jwt-key",
                value.toPem(),
                "argos.jwt-key-file",
                file.toString(),
                "argos.jwt-previous-key",
                previous.toPem()));
    assertThat(ring.signingKey().kid()).isEqualTo(value.kid());
    assertThat(ring.verificationKey(previous.kid())).isPresent();
    assertThat(ring.verificationKey("unknown")).isEmpty();
    assertThat(file).doesNotExist();
  }

  @Test
  @DisplayName(
      "T-SEC-40a before the KEYS phase the ring refuses to sign; the same key twice is wrong")
  void ringStates() {
    JwtKeyRing ring = new JwtKeyRing();
    assertThat(ring.loaded()).isFalse();
    assertThatThrownBy(ring::signingKey).isInstanceOf(IllegalStateException.class);
    JwtKey key = JwtKey.generate();
    assertThatThrownBy(() -> ring.install(key, JwtKey.parse(key.toPem(), true)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
