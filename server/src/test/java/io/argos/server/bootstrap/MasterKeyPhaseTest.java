/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.config.ArgosProperties;
import io.argos.server.secrets.MasterKey;
import io.argos.server.secrets.MasterKeyLoader;
import io.argos.server.secrets.MasterKeyRing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
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

/**
 * The master key step of the {@code KEYS} start phase (DSN-12-15, 16 §8.2, ADR-0010 C-226 (2)):
 * refusal with one line naming the setting, the ring filled on success, the permission rule with a
 * substituted permission source (so that it runs on Windows too) and through the start sequence.
 */
@Tag("req:SEC-52")
@Tag("req:C-226")
class MasterKeyPhaseTest {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final MasterKeyLoader.PermissionProbe OWNER_ONLY =
      f -> Optional.of(PosixFilePermissions.fromString("rw-------"));
  private static final MasterKeyLoader.PermissionProbe GROUP_READABLE =
      f -> Optional.of(PosixFilePermissions.fromString("rw-r-----"));

  @TempDir Path dir;

  private static String newHex() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }

  private Path keyFile(String content) throws Exception {
    Path file = Files.createTempFile(dir, "master", ".key");
    Files.writeString(file, content, StandardCharsets.US_ASCII);
    return file;
  }

  private static ArgosProperties properties(Map<String, String> extra) {
    Map<String, String> source = new LinkedHashMap<>();
    source.put("argos.web.public-url", "https://argos.example.com");
    source.putAll(extra);
    return new Binder(new MapConfigurationPropertySource(source))
        .bindOrCreate("argos", ArgosProperties.class);
  }

  @Test
  @DisplayName("T-SEC-52c the step belongs to KEYS, named master-key, and fills the ring")
  void loads() throws Exception {
    String hex = newHex();
    Path file = keyFile(hex + "\n");
    MasterKeyRing ring = new MasterKeyRing();
    MasterKeyPhase phase =
        new MasterKeyPhase(
            properties(Map.of("argos.master-key-file", file.toString())), ring, OWNER_ONLY);
    assertThat(phase.order()).isEqualTo(PhaseOrder.KEYS);
    assertThat(phase.name()).isEqualTo("master-key");
    assertThat(ring.available()).isFalse();
    phase.run();
    assertThat(ring.available()).isTrue();
    assertThat(ring.current().orElseThrow().kid())
        .isEqualTo(MasterKey.of(HexFormat.of().parseHex(hex)).kid());
  }

  @Test
  @DisplayName(
      "T-SEC-52c no key file, a malformed key and a group-readable file stop the start with one"
          + " line that names the setting, never the key or the file")
  void refusals() throws Exception {
    Path absent = dir.resolve("conf").resolve("master.key");
    Path malformed = keyFile("0123456789abcdef");
    String hex = newHex();
    Path readable = keyFile(hex);
    List<MasterKeyPhase> phases =
        List.of(
            new MasterKeyPhase(
                properties(Map.of("argos.master-key-file", absent.toString())),
                new MasterKeyRing(),
                OWNER_ONLY),
            new MasterKeyPhase(
                properties(Map.of("argos.master-key-file", malformed.toString())),
                new MasterKeyRing(),
                OWNER_ONLY),
            new MasterKeyPhase(
                properties(Map.of("argos.master-key-file", readable.toString())),
                new MasterKeyRing(),
                GROUP_READABLE));
    List<String> expected =
        List.of(
            "No master key: argos.master-key is not set and the file of argos.master-key-file",
            "argos.master-key-file: the master key is not 32 bytes",
            "argos.master-key-file: the key file is accessible to group or others (rw-r-----)");
    for (int i = 0; i < phases.size(); i++) {
      MasterKeyPhase phase = phases.get(i);
      assertThatThrownBy(phase::run)
          .isInstanceOf(StartupPhaseException.class)
          .hasMessageStartingWith(expected.get(i))
          .hasMessageNotContaining(hex)
          .hasMessageNotContaining(dir.toString())
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("\n"));
    }
  }

  @Test
  @DisplayName(
      "T-SEC-52c permissive=true accepts the group-readable file (the warning is logged each"
          + " start)")
  void permissive() throws Exception {
    Path file = keyFile(newHex());
    MasterKeyRing ring = new MasterKeyRing();
    new MasterKeyPhase(
            properties(
                Map.of(
                    "argos.master-key-file",
                    file.toString(),
                    "argos.security.master-key.permissive",
                    "true")),
            ring,
            GROUP_READABLE)
        .run();
    assertThat(ring.available()).isTrue();
  }

  @Test
  @DisplayName(
      "T-SEC-52c the WARN of permissive=true and, without POSIX permissions (Windows), one INFO"
          + " note instead of a check; neither names the key")
  void warningsAndNotes() throws Exception {
    String hex = newHex();
    Path file = keyFile(hex);
    MasterKeyLoader.Loaded permissive =
        MasterKeyLoader.load(
            properties(
                Map.of(
                    "argos.master-key-file",
                    file.toString(),
                    "argos.security.master-key.permissive",
                    "true")),
            GROUP_READABLE);
    assertThat(permissive.warnings())
        .singleElement()
        .asString()
        .contains("argos.master-key-file")
        .contains("accepted because argos.security.master-key.permissive=true")
        .doesNotContain(hex);
    assertThat(permissive.notes()).isEmpty();

    MasterKeyLoader.Loaded nonPosix =
        MasterKeyLoader.load(
            properties(Map.of("argos.master-key-file", file.toString())), f -> Optional.empty());
    assertThat(nonPosix.warnings()).isEmpty();
    assertThat(nonPosix.notes())
        .singleElement()
        .asString()
        .contains("file permissions cannot be checked on this file system")
        .doesNotContain(hex);
    assertThat(nonPosix.current().kid()).isEqualTo(MasterKey.parse(hex).kid());
  }

  @Test
  @DisplayName(
      "T-SEC-52c the previous key of a rotation: loaded decrypt-only, a missing previous file is"
          + " refused, the current key named again is loaded once")
  void previousKey() throws Exception {
    String currentHex = newHex();
    String previousHex = newHex();
    Path current = keyFile(currentHex);
    Path previous = keyFile(previousHex);
    MasterKeyRing ring = new MasterKeyRing();
    new MasterKeyPhase(
            properties(
                Map.of(
                    "argos.master-key-file",
                    current.toString(),
                    "argos.security.master-key.previous-file",
                    previous.toString())),
            ring,
            OWNER_ONLY)
        .run();
    assertThat(ring.current().orElseThrow().kid()).isEqualTo(MasterKey.parse(currentHex).kid());
    assertThat(ring.decryptOnlyKids()).containsExactly(MasterKey.parse(previousHex).kid());

    assertThatThrownBy(
            () ->
                new MasterKeyPhase(
                        properties(
                            Map.of(
                                "argos.master-key-file",
                                current.toString(),
                                "argos.security.master-key.previous-file",
                                dir.resolve("gone.key").toString())),
                        new MasterKeyRing(),
                        OWNER_ONLY)
                    .run())
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageStartingWith("argos.security.master-key.previous-file names a file that does");

    MasterKeyLoader.Loaded same =
        MasterKeyLoader.load(
            properties(
                Map.of(
                    "argos.master-key-file",
                    current.toString(),
                    "argos.security.master-key.previous-file",
                    current.toString())),
            OWNER_ONLY);
    assertThat(same.previous()).isNull();
    assertThat(same.notes()).anyMatch(n -> n.contains("is the current key"));
  }

  @Test
  @DisplayName(
      "T-SEC-52c through the start sequence: the failure is reported as KEYS master-key and no"
          + " later step runs")
  void sequence() {
    boolean[] later = {false};
    StartupPhase migration =
        new StartupPhase() {
          @Override
          public PhaseOrder order() {
            return PhaseOrder.POSTGRES_MIGRATION;
          }

          @Override
          public void run() {
            later[0] = true;
          }
        };
    MasterKeyPhase keys =
        new MasterKeyPhase(
            properties(Map.of("argos.master-key-file", dir.resolve("none.key").toString())),
            new MasterKeyRing(),
            OWNER_ONLY);
    StartupSequence sequence = new StartupSequence(List.of(migration, keys));
    assertThatThrownBy(sequence::run)
        .isInstanceOfSatisfying(
            StartupAbortedException.class,
            e -> {
              assertThat(e.phase()).isEqualTo(PhaseOrder.KEYS);
              assertThat(e.step()).isEqualTo("master-key");
            });
    assertThat(later[0]).isFalse();
  }
}
