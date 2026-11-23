/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.alert;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * A master key generated at run time for the tests (WO-25; nothing is committed, gitleaks 0): 32
 * CSPRNG bytes as 64 hex characters (the {@code argos-env-init.sh} form, 17 §3), written to an
 * owner-only file ({@code 0600} where POSIX permissions exist) for {@code argos.master-key-file}.
 *
 * @param file the key file
 * @param hex the key as 64 hex characters (to derive the key id or decrypt in a test)
 */
public record TestMasterKey(Path file, String hex) {

  private static final SecureRandom RANDOM = new SecureRandom();

  /** A new key in a file under {@code directory}. */
  public static TestMasterKey create(Path directory) {
    byte[] key = new byte[32];
    RANDOM.nextBytes(key);
    String hex = HexFormat.of().formatHex(key);
    try {
      Files.createDirectories(directory);
      Path file = Files.createTempFile(directory, "master", ".key");
      if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      }
      Files.writeString(file, hex + "\n", StandardCharsets.US_ASCII);
      return new TestMasterKey(file, hex);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The Spring property that makes a server use this key. */
  public String property() {
    return "argos.master-key-file";
  }

  @Override
  public String toString() {
    return "TestMasterKey[" + file + "]";
  }
}
