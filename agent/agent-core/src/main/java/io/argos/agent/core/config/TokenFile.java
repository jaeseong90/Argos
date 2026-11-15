/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * {@code server.token-file} (SEC-06, 16 §2.6): read once at start, trimmed, at most {@value
 * #MAX_BYTES} bytes; a file that group or others can read gives one WARN ({@code ST-06-3}, 0600
 * recommended). On a file system without POSIX permissions (Windows NTFS, FAT) the check is not
 * possible: that is recorded as a note, never as a WARN, so no false warning appears there.
 *
 * <p>Messages name the file and the problem, never its content.
 */
final class TokenFile {
  /** Largest accepted token file (the token itself is 49 characters, 16 §2.1). */
  static final int MAX_BYTES = 4096;

  /** Result of a permission check. */
  enum Access {
    /** Only the owner can read the file. */
    OWNER_ONLY,
    /** Group or others can read the file (WARN). */
    GROUP_OR_OTHERS,
    /** The file system has no POSIX permissions, or they could not be read. */
    UNKNOWN
  }

  /** Reads the permissions of a file; never throws. */
  interface PermissionProbe {
    Access check(Path path);
  }

  /** The probe of the running JVM: {@link PosixFileAttributeView} when the file system has it. */
  static final PermissionProbe POSIX =
      new PermissionProbe() {
        @Override
        public Access check(Path path) {
          try {
            PosixFileAttributeView view =
                Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (view == null) {
              return Access.UNKNOWN;
            }
            Set<PosixFilePermission> permissions = view.readAttributes().permissions();
            return permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                ? Access.GROUP_OR_OTHERS
                : Access.OWNER_ONLY;
          } catch (IOException | RuntimeException e) {
            return Access.UNKNOWN;
          }
        }
      };

  private TokenFile() {}

  /**
   * The trimmed token of {@code file}, or null when it cannot be used (problems added to {@code
   * warnings}); the permission result goes to {@code warnings} (group or others can read) or {@code
   * notes} (not checkable).
   */
  static String read(File file, PermissionProbe probe, List<String> warnings, List<String> notes) {
    String name = file.getPath();
    try {
      if (!file.isFile()) {
        warnings.add(
            "server.token-file " + name + " does not exist or is not a file; no token from it");
        return null;
      }
      if (file.length() > MAX_BYTES) {
        warnings.add(
            "server.token-file " + name + " is larger than " + MAX_BYTES + " bytes; ignored");
        return null;
      }
      byte[] bytes = readBounded(file);
      if (bytes == null) {
        warnings.add(
            "server.token-file " + name + " is larger than " + MAX_BYTES + " bytes; ignored");
        return null;
      }
      String token;
      try {
        token = new String(bytes, StandardCharsets.UTF_8).trim();
      } finally {
        Arrays.fill(bytes, (byte) 0);
      }
      if (token.isEmpty()) {
        warnings.add("server.token-file " + name + " is empty; no token from it");
        return null;
      }
      Access access = probe.check(file.toPath());
      if (access == Access.GROUP_OR_OTHERS) {
        warnings.add(
            "server.token-file "
                + name
                + " can be read by group or others; restrict it to the owner (chmod 600)");
      } else if (access == Access.UNKNOWN) {
        notes.add(
            "server.token-file permissions were not checked: the file system has no POSIX"
                + " permissions");
      }
      return token;
    } catch (IOException | RuntimeException e) {
      warnings.add(
          "server.token-file "
              + name
              + " cannot be read ("
              + e.getClass().getSimpleName()
              + "); no token from it");
      return null;
    }
  }

  /** At most {@link #MAX_BYTES} bytes, null when the file holds more. */
  private static byte[] readBounded(File file) throws IOException {
    byte[] buffer = new byte[MAX_BYTES + 1];
    int total = 0;
    try (InputStream in = new FileInputStream(file)) {
      while (total < buffer.length) {
        int read = in.read(buffer, total, buffer.length - total);
        if (read < 0) {
          break;
        }
        total += read;
      }
    }
    try {
      if (total > MAX_BYTES) {
        return null;
      }
      byte[] bytes = new byte[total];
      System.arraycopy(buffer, 0, bytes, 0, total);
      return bytes;
    } finally {
      Arrays.fill(buffer, (byte) 0); // the token does not stay in a second buffer
    }
  }
}
