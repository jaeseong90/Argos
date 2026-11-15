/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.token;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Files that hold a secret of the server (the JWT signing key {@code conf/jwt.key}, the setup token
 * {@code conf/setup-token}, 16 §7.1, §7.8): created owner-only ({@code 0600}, directories {@code
 * 0700}) on POSIX file systems, written atomically (a temporary file in the same directory, then a
 * move), read with a size bound. On file systems without POSIX permissions (Windows) the files get
 * the directory's default access; the installation guide covers that case.
 */
public final class PrivateFiles {

  /** {@code rw-------}. */
  public static final Set<PosixFilePermission> OWNER_ONLY =
      EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
      EnumSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE);

  private static final Set<PosixFilePermission> GROUP_OR_OTHERS =
      EnumSet.of(
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_WRITE,
          PosixFilePermission.OTHERS_EXECUTE);

  private PrivateFiles() {}

  /** Whether the file system of {@code path} has POSIX permissions. */
  public static boolean posix(Path path) {
    return path.toAbsolutePath().getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  /**
   * Writes {@code content} (UTF-8) to {@code file}, replacing it, owner-only on POSIX. Missing
   * parent directories are created owner-only.
   */
  public static void writeOwnerOnly(Path file, String content) throws IOException {
    Path target = file.toAbsolutePath();
    Path dir = target.getParent();
    boolean posix = posix(target);
    if (dir != null && !Files.isDirectory(dir)) {
      if (posix) {
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
      } else {
        Files.createDirectories(dir);
      }
    }
    FileAttribute<?>[] attributes =
        posix
            ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY)}
            : new FileAttribute<?>[0];
    Path temp = Files.createTempFile(dir, "." + target.getFileName(), ".tmp", attributes);
    try {
      Files.writeString(temp, content, StandardCharsets.UTF_8);
      try {
        Files.move(
            temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
    if (posix) {
      // The move keeps the temporary file's permissions; set them again in case the file existed.
      Files.setPosixFilePermissions(target, OWNER_ONLY);
    }
  }

  /**
   * Group or others permissions of a file on POSIX, e.g. {@code rw-r--r--}; empty when the file is
   * owner-only or the file system has no POSIX permissions.
   */
  public static Optional<String> loosePermissions(Path file) {
    if (!posix(file)) {
      return Optional.empty();
    }
    try {
      PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
      if (view == null) {
        return Optional.empty();
      }
      Set<PosixFilePermission> permissions = view.readAttributes().permissions();
      for (PosixFilePermission p : permissions) {
        if (GROUP_OR_OTHERS.contains(p)) {
          return Optional.of(PosixFilePermissions.toString(permissions));
        }
      }
      return Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /**
   * Reads a small text file (UTF-8).
   *
   * @throws IOException when it cannot be read or is larger than {@code maxBytes}
   */
  public static String read(Path file, int maxBytes) throws IOException {
    byte[] bytes;
    try (InputStream in = Files.newInputStream(file)) {
      bytes = in.readNBytes(maxBytes + 1);
    }
    if (bytes.length > maxBytes) {
      throw new IOException("larger than " + maxBytes + " bytes");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
