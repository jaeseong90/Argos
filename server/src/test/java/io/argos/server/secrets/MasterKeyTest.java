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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
      "T-SEC-52a the loader reads argos.master-key-file and the previous key; the value key wins")
  void ring() throws Exception {
    byte[] current = random32();
    byte[] previous = random32();
    Path file = ownerOnly(HexFormat.of().formatHex(current) + "\n");
    MasterKeyLoader.Loaded loaded =
        MasterKeyLoader.load(
            properties(
                Map.of(
                    "argos.master-key-file", file.toString(),
                    "argos.security.master-key.previous",
                        Base64.getEncoder().encodeToString(previous))),
            OWNER_ONLY);
    MasterKeyRing ring = MasterKeyRing.of(loaded.current(), List.of(loaded.previous()));
    assertThat(ring.available()).isTrue();
    assertThat(ring.current().orElseThrow().kid()).isEqualTo(MasterKey.of(current).kid());
    assertThat(ring.find(MasterKey.of(previous).kid())).isPresent();
    assertThat(loaded.currentSource()).isEqualTo("argos.master-key-file");
    assertThat(loaded.previousSource()).isEqualTo("argos.security.master-key.previous");

    byte[] value = random32();
    MasterKeyLoader.Loaded valueWins =
        MasterKeyLoader.load(
            properties(
                Map.of(
                    "argos.master-key-file", file.toString(),
                    "argos.master-key", HexFormat.of().formatHex(value))),
            OWNER_ONLY);
    assertThat(valueWins.current().kid()).isEqualTo(MasterKey.of(value).kid());
  }

  @Test
  @DisplayName(
      "T-SEC-52c a missing or malformed key is refused with the setting name only (ADR-0010 C-226"
          + " (2): the start refusal replaces WO-25's empty ring)")
  void missing() throws Exception {
    assertThatThrownBy(() -> MasterKeyLoader.load(properties(Map.of()), OWNER_ONLY))
        .isInstanceOf(MasterKeyLoader.MasterKeyException.class)
        .hasMessageContaining("No master key");
    String badText = "not-a-key-" + HexFormat.of().formatHex(random32()).substring(0, 8);
    Path bad = ownerOnly(badText);
    assertThatThrownBy(
            () ->
                MasterKeyLoader.load(
                    properties(Map.of("argos.master-key-file", bad.toString())), OWNER_ONLY))
        .isInstanceOf(MasterKeyLoader.MasterKeyException.class)
        .hasMessageStartingWith("argos.master-key-file: the master key is not 32 bytes")
        .hasMessageNotContaining(badText)
        .hasMessageNotContaining(bad.toString());
    Path absent = dir.resolve("absent.key");
    assertThatThrownBy(
            () ->
                MasterKeyLoader.load(
                    properties(Map.of("argos.master-key-file", absent.toString())), OWNER_ONLY))
        .isInstanceOf(MasterKeyLoader.MasterKeyException.class)
        .hasMessageContaining("argos.master-key-file")
        .hasMessageNotContaining(absent.toString());
    String shortValue = HexFormat.of().formatHex(new byte[16]);
    assertThatThrownBy(
            () ->
                MasterKeyLoader.load(
                    properties(Map.of("argos.master-key", shortValue)), OWNER_ONLY))
        .isInstanceOf(MasterKeyLoader.MasterKeyException.class)
        .hasMessageStartingWith("argos.master-key: ")
        .hasMessageNotContaining(shortValue);
    Path current = ownerOnly(HexFormat.of().formatHex(random32()));
    assertThatThrownBy(
            () ->
                MasterKeyLoader.load(
                    properties(
                        Map.of(
                            "argos.master-key-file",
                            current.toString(),
                            "argos.security.master-key.previous-file",
                            absent.toString())),
                    OWNER_ONLY))
        .isInstanceOf(MasterKeyLoader.MasterKeyException.class)
        .hasMessageStartingWith("argos.security.master-key.previous-file");
  }

  @Test
  @DisplayName(
      "T-SEC-52c a key file accessible to group or others is refused unless permissive (warning);"
          + " without POSIX permissions a note, no refusal (16 §8.2)")
  void loosePermissions() throws Exception {
    Path file = ownerOnly(HexFormat.of().formatHex(random32()));
    MasterKeyLoader.PermissionProbe loose =
        f -> Optional.of(PosixFilePermissions.fromString("rw-r--r--"));
    assertThatThrownBy(
            () ->
                MasterKeyLoader.load(
                    properties(Map.of("argos.master-key-file", file.toString())), loose))
        .isInstanceOf(MasterKeyLoader.MasterKeyException.class)
        .hasMessage(
            "argos.master-key-file: the key file is accessible to group or others (rw-r--r--);"
                + " make it owner-only (chmod 600) or set"
                + " argos.security.master-key.permissive=true");
    MasterKeyLoader.Loaded permissive =
        MasterKeyLoader.load(
            properties(
                Map.of(
                    "argos.master-key-file",
                    file.toString(),
                    "argos.security.master-key.permissive",
                    "true")),
            loose);
    assertThat(permissive.warnings())
        .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .contains("accepted because argos.security.master-key.permissive=true");
    MasterKeyLoader.Loaded noPosix =
        MasterKeyLoader.load(
            properties(Map.of("argos.master-key-file", file.toString())), f -> Optional.empty());
    assertThat(noPosix.warnings()).isEmpty();
    assertThat(noPosix.notes())
        .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .contains("file permissions cannot be checked on this file system");
  }

  @Test
  @DisplayName("T-SEC-52c the real POSIX permissions of a 0644 key file refuse it (POSIX only)")
  void loosePermissionsOnDisk() throws Exception {
    assumeTrue(
        dir.getFileSystem().supportedFileAttributeViews().contains("posix"),
        "POSIX permissions only (Windows: the loader notes that it cannot check them)");
    Path file = ownerOnly(HexFormat.of().formatHex(random32()));
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    assertThatThrownBy(
            () ->
                MasterKeyLoader.load(
                    properties(Map.of("argos.master-key-file", file.toString())),
                    MasterKeyLoader.FILE_SYSTEM))
        .isInstanceOf(MasterKeyLoader.MasterKeyException.class);
  }

  /** Every file owner-only, on any file system. */
  private static final MasterKeyLoader.PermissionProbe OWNER_ONLY =
      f -> Optional.of(PosixFilePermissions.fromString("rw-------"));

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
