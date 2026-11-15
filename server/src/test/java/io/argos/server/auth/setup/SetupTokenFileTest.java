/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.server.auth.AuthTestProperties;
import io.argos.server.auth.token.PrivateFiles;
import io.argos.server.storage.postgres.auth.UserRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * The setup token file that {@link SetupTokenBootstrap} writes when no administrator exists (16
 * §7.8 {@code conf/setup-token}, SEC-47): its content, its permissions and its end. Only the token
 * path of the start phase runs here: a repository without a database answers "no enabled
 * administrator" and nothing else is called; the database side, on a real PostgreSQL, is {@code
 * SetupBootstrapSecurityTest}'s. The exact {@code rw-------} is checked only where the file system
 * has POSIX permissions, and the test reports SKIPPED elsewhere (Windows), so a green run on such a
 * machine never claims it.
 */
@Tag("req:SEC-47")
class SetupTokenFileTest {

  private static final String NO_POSIX =
      "the file system has no POSIX permissions (e.g. Windows NTFS)";

  @TempDir Path dir;

  /** A repository without a database (no connection is ever opened): no administrator exists. */
  private static final class NoAdministrator extends UserRepository {

    NoAdministrator() {
      super(new SimpleDriverDataSource());
    }

    @Override
    public boolean enabledAdminExists() {
      return false;
    }
  }

  private static SetupTokenBootstrap bootstrap(Path file) {
    return new SetupTokenBootstrap(
        AuthTestProperties.of(Map.of("argos.auth.setup-token-file", file.toString())),
        new StandardEnvironment(),
        new NoAdministrator(),
        null,
        null,
        null,
        null,
        null,
        Clock.systemUTC());
  }

  private static String permissions(Path path) throws Exception {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
  }

  @Test
  @DisplayName(
      "T-SEC-47a without an administrator the start writes conf/setup-token: the token in force on"
          + " one line, no group or others access, no temporary file left; consuming deletes it")
  void tokenFile() throws Exception {
    Path file = dir.resolve("conf").resolve("setup-token");
    SetupTokenBootstrap bootstrap = bootstrap(file);
    bootstrap.run();

    String text = Files.readString(file, StandardCharsets.UTF_8);
    assertThat(text).endsWith("\n");
    String token = text.strip();
    assertThat(token).matches("[A-Za-z0-9_-]{43}");
    assertThat(bootstrap.isValid(token)).as("the file holds the token in force").isTrue();
    assertThat(PrivateFiles.loosePermissions(file)).isEmpty();
    try (Stream<Path> entries = Files.list(file.getParent())) {
      assertThat(entries).as("the atomic write leaves no temporary file").containsExactly(file);
    }

    bootstrap.consume();
    assertThat(file).doesNotExist();
    assertThat(bootstrap.isValid(token)).isFalse();
  }

  @Test
  @DisplayName(
      "T-SEC-47a on POSIX the setup token file is rw------- in a new rwx------ conf directory"
          + " (skipped where the file system has no POSIX permissions)")
  void ownerOnlyOnPosix() throws Exception {
    Path file = dir.resolve("conf").resolve("setup-token");
    assumeTrue(PrivateFiles.posix(file), NO_POSIX);
    bootstrap(file).run();
    assertThat(permissions(file)).isEqualTo("rw-------");
    assertThat(permissions(file.getParent())).isEqualTo("rwx------");
  }

  @Test
  @DisplayName(
      "T-SEC-47a on POSIX a group- and world-readable file of an earlier start is replaced by a"
          + " rw------- file with the new token (skipped where there are no POSIX permissions)")
  void replacesLooseFileOnPosix() throws Exception {
    Path file = dir.resolve("setup-token");
    assumeTrue(PrivateFiles.posix(file), NO_POSIX);
    Files.writeString(file, "stale\n", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

    SetupTokenBootstrap bootstrap = bootstrap(file);
    bootstrap.run();
    assertThat(permissions(file)).isEqualTo("rw-------");
    String token = Files.readString(file, StandardCharsets.UTF_8).strip();
    assertThat(token).isNotEqualTo("stale");
    assertThat(bootstrap.isValid(token)).isTrue();
  }
}
