/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.server.config.ArgosProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

@Tag("req:SEC-52")
class MasterKeyTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  @TempDir Path dir;

  private static byte[] random32() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return b;
  }

  @Test
  @DisplayName(
      "T-SEC-52a the master key is 32 bytes as 64 hex characters (env-init, 17 §3) or base64 and"
          + " base64url, padded or not (16 §8.2)")
  void forms() {
    byte[] bytes = random32();
    String kid = MasterKey.of(bytes).kid();
    assertThat(MasterKey.parse(HexFormat.of().formatHex(bytes)).kid()).isEqualTo(kid);
    assertThat(MasterKey.parse(HexFormat.of().withUpperCase().formatHex(bytes) + "\n").kid())
        .isEqualTo(kid);
    assertThat(MasterKey.parse(Base64.getEncoder().encodeToString(bytes)).kid()).isEqualTo(kid);
    assertThat(MasterKey.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)).kid())
        .isEqualTo(kid);
    assertThat(MasterKey.parse("  " + Base64.getUrlEncoder().encodeToString(bytes) + "  ").kid())
        .isEqualTo(kid);
  }

  @Test
  @DisplayName("T-SEC-52a a key of another length or form is refused without echoing it")
  void refused() {
    String shortKey = HexFormat.of().formatHex(new byte[16]);
    assertThatThrownBy(() -> MasterKey.parse(shortKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(shortKey);
    assertThatThrownBy(() -> MasterKey.parse("changeme"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MasterKey.parse("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MasterKey.parse(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("T-SEC-52a toString shows the key id only")
  void toStringHidesKey() {
    byte[] bytes = random32();
    MasterKey key = MasterKey.of(bytes);
    assertThat(key.toString())
        .isEqualTo("MasterKey[kid=" + key.kid() + "]")
        .doesNotContain(HexFormat.of().formatHex(bytes));
  }

  @Test
  @DisplayName(
      "T-SEC-52a the ring loads argos.master-key-file and the previous key; the value key wins")
  void ring() throws Exception {
    byte[] current = random32();
    byte[] previous = random32();
    Path file = ownerOnly(HexFormat.of().formatHex(current) + "\n");
    MasterKeyRing ring =
        new MasterKeyRing(
            properties(
                Map.of(
                    "argos.master-key-file", file.toString(),
                    "argos.security.master-key.previous",
                        Base64.getEncoder().encodeToString(previous))));
    assertThat(ring.available()).isTrue();
    assertThat(ring.current().orElseThrow().kid()).isEqualTo(MasterKey.of(current).kid());
    assertThat(ring.find(MasterKey.of(previous).kid())).isPresent();

    byte[] value = random32();
    MasterKeyRing valueWins =
        new MasterKeyRing(
            properties(
                Map.of(
                    "argos.master-key-file", file.toString(),
                    "argos.master-key", HexFormat.of().formatHex(value))));
    assertThat(valueWins.current().orElseThrow().kid()).isEqualTo(MasterKey.of(value).kid());
  }

  @Test
  @DisplayName(
      "T-SEC-52a a missing or malformed key leaves the ring empty: the server starts, secrets are"
          + " unavailable (WO-25; the start refusal is WO-28's)")
  void missing() throws Exception {
    assertThat(new MasterKeyRing(properties(Map.of())).available()).isFalse();
    Path bad = ownerOnly("not-a-key");
    assertThat(
            new MasterKeyRing(properties(Map.of("argos.master-key-file", bad.toString())))
                .available())
        .isFalse();
    assertThat(
            new MasterKeyRing(
                    properties(
                        Map.of("argos.master-key-file", dir.resolve("absent.key").toString())))
                .available())
        .isFalse();
  }

  @Test
  @DisplayName(
      "T-SEC-52a a key file readable by group or others is ignored unless permissive (16 §8.2,"
          + " POSIX)")
  void loosePermissions() throws Exception {
    assumeTrue(
        dir.getFileSystem().supportedFileAttributeViews().contains("posix"),
        "POSIX permissions only");
    Path file = ownerOnly(HexFormat.of().formatHex(random32()));
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    assertThat(
            new MasterKeyRing(properties(Map.of("argos.master-key-file", file.toString())))
                .available())
        .isFalse();
    assertThat(
            new MasterKeyRing(
                    properties(
                        Map.of(
                            "argos.master-key-file",
                            file.toString(),
                            "argos.security.master-key.permissive",
                            "true")))
                .available())
        .isTrue();
  }

  private Path ownerOnly(String content) throws Exception {
    Path file = Files.createTempFile(dir, "master", ".key");
    if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
    Files.writeString(file, content, StandardCharsets.US_ASCII);
    return file;
  }

  static ArgosProperties properties(Map<String, String> extra) {
    Map<String, String> source = new LinkedHashMap<>();
    source.put("argos.web.public-url", "https://argos.example.com");
    source.put("argos.master-key-file", "");
    source.putAll(extra);
    return new Binder(new MapConfigurationPropertySource(source))
        .bindOrCreate("argos", ArgosProperties.class);
  }
}
