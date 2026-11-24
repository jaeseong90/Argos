/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.secrets;

import io.argos.server.auth.token.PrivateFiles;
import io.argos.server.config.ArgosProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Reads the master keys of the configuration (16 §8.2, ADR-0010 C-226 (2)) for the {@code KEYS}
 * start phase ({@code io.argos.server.bootstrap.MasterKeyPhase}) and for {@code argos-admin secrets
 * rekey}:
 *
 * <ol>
 *   <li>the current key: {@code argos.master-key} ({@code ARGOS_MASTER_KEY}) when set, else the
 *       file {@code argos.master-key-file} (default {@code conf/master.key}). Neither: refused.
 *   <li>the previous key of a rotation (decryption only): {@code
 *       argos.security.master-key.previous}, else {@code argos.security.master-key.previous-file};
 *       optional, but a configured previous key that cannot be used is refused too.
 * </ol>
 *
 * A key is 32 bytes as 64 hex characters or base64 (base64url, padding optional). A key file that
 * group or others may read or write is refused unless {@code argos.security.master-key.permissive}
 * is true (then a warning). Where the file system has no POSIX permissions (Windows) the check is
 * not possible: a note says so and the file is used (restrict it with the operating system's
 * tools).
 *
 * <p>Every message names the setting, never a key, a key file's content or its path (the path is
 * the operator's own configuration value); a key appears only as its id.
 */
public final class MasterKeyLoader {

  /** A key file holds one line; anything much larger is not a key. */
  public static final int MAX_FILE_BYTES = 1024;

  /** The default of {@code argos.master-key-file}. */
  public static final String DEFAULT_FILE = "conf/master.key";

  private static final Set<PosixFilePermission> GROUP_OR_OTHERS =
      EnumSet.of(
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_WRITE,
          PosixFilePermission.OTHERS_EXECUTE);

  /** The POSIX permissions of a file, or empty where the file system has none. */
  @FunctionalInterface
  public interface PermissionProbe {

    /**
     * The permissions of {@code file}.
     *
     * @return empty when the file system does not keep POSIX permissions
     * @throws IOException when they cannot be read
     */
    Optional<Set<PosixFilePermission>> permissions(Path file) throws IOException;
  }

  /** The file system's own permissions. */
  public static final PermissionProbe FILE_SYSTEM =
      file -> {
        PosixFileAttributeView view =
            Files.getFileAttributeView(file, PosixFileAttributeView.class);
        return view == null ? Optional.empty() : Optional.of(view.readAttributes().permissions());
      };

  /** A key that cannot be used; the message is one line with setting names only. */
  public static final class MasterKeyException extends Exception {

    private static final long serialVersionUID = 1L;

    MasterKeyException(String message) {
      super(message);
    }
  }

  /**
   * The keys of a configuration.
   *
   * @param current the key that encrypts
   * @param currentSource the setting it came from
   * @param previous the decrypt-only key of a rotation, or null
   * @param previousSource the setting of the previous key, or null
   * @param warnings lines for a WARN each (a permissive key file)
   * @param notes lines for an INFO each (permissions not checkable, previous key = current key)
   */
  public record Loaded(
      MasterKey current,
      String currentSource,
      @Nullable MasterKey previous,
      @Nullable String previousSource,
      List<String> warnings,
      List<String> notes) {

    /** Copies the lists. */
    public Loaded {
      Objects.requireNonNull(current, "current");
      warnings = List.copyOf(warnings);
      notes = List.copyOf(notes);
    }
  }

  private MasterKeyLoader() {}

  /**
   * Loads the current and previous key of {@code properties}.
   *
   * @throws MasterKeyException when there is no current key, or a configured key is missing,
   *     malformed, unreadable or (not permissive) readable by group or others
   */
  public static Loaded load(ArgosProperties properties, PermissionProbe probe)
      throws MasterKeyException {
    boolean permissive = properties.security().masterKey().permissive();
    List<String> warnings = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    MasterKey current;
    String currentSource;
    if (properties.masterKey() != null) {
      current = parseValue("argos.master-key", properties.masterKey());
      currentSource = "argos.master-key";
    } else {
      String file = properties.masterKeyFile();
      if (file == null) {
        throw new MasterKeyException(
            "No master key: neither argos.master-key nor argos.master-key-file is set. The"
                + " installer creates the key file (32 random bytes as 64 hex characters, owner"
                + " only)");
      }
      Path path = path("argos.master-key-file", file);
      if (!Files.exists(path)) {
        throw new MasterKeyException(
            "No master key: argos.master-key is not set and the file of argos.master-key-file"
                + " does not exist. The installer creates it (32 random bytes as 64 hex"
                + " characters, owner only); keep a copy, without it the stored secrets cannot be"
                + " read");
      }
      current = loadFile("argos.master-key-file", path, permissive, probe, warnings, notes);
      currentSource = "argos.master-key-file";
    }
    MasterKey previous = null;
    String previousSource = null;
    ArgosProperties.Security.MasterKey settings = properties.security().masterKey();
    try {
      if (settings.previous() != null) {
        previous = parseValue("argos.security.master-key.previous", settings.previous());
        previousSource = "argos.security.master-key.previous";
      } else if (settings.previousFile() != null) {
        Path path = path("argos.security.master-key.previous-file", settings.previousFile());
        if (!Files.exists(path)) {
          throw new MasterKeyException(
              "argos.security.master-key.previous-file names a file that does not exist; remove"
                  + " the setting when the rotation is complete");
        }
        previous =
            loadFile(
                "argos.security.master-key.previous-file",
                path,
                permissive,
                probe,
                warnings,
                notes);
        previousSource = "argos.security.master-key.previous-file";
      }
    } catch (MasterKeyException e) {
      current.close();
      throw e;
    }
    if (previous != null && previous.kid().equals(current.kid())) {
      notes.add(
          previousSource
              + " is the current key (key id "
              + current.kid()
              + "); it is not loaded twice");
      previous.close();
      previous = null;
      previousSource = null;
    }
    return new Loaded(current, currentSource, previous, previousSource, warnings, notes);
  }

  /**
   * Reads one key file with the permission rule of this class.
   *
   * @param setting the setting that names the file (for the messages)
   * @param warnings receives the permissive warning
   * @param notes receives the "permissions cannot be checked" note
   * @throws MasterKeyException when the file is missing, unreadable, malformed or too open
   */
  public static MasterKey loadFile(
      String setting,
      Path file,
      boolean permissive,
      PermissionProbe probe,
      List<String> warnings,
      List<String> notes)
      throws MasterKeyException {
    if (!Files.isRegularFile(file)) {
      throw new MasterKeyException(setting + " does not name a readable regular file");
    }
    Optional<Set<PosixFilePermission>> permissions;
    try {
      permissions = probe.permissions(file);
    } catch (IOException e) {
      throw new MasterKeyException(
          setting + ": the file permissions cannot be read (" + e.getClass().getSimpleName() + ")");
    }
    if (permissions.isEmpty()) {
      notes.add(
          setting
              + ": file permissions cannot be checked on this file system (no POSIX permissions);"
              + " restrict the file to the server's user with the operating system's tools");
    } else {
      Set<PosixFilePermission> open = EnumSet.noneOf(PosixFilePermission.class);
      open.addAll(permissions.get());
      open.retainAll(GROUP_OR_OTHERS);
      if (!open.isEmpty()) {
        String mode = PosixFilePermissions.toString(permissions.get());
        if (!permissive) {
          throw new MasterKeyException(
              setting
                  + ": the key file is accessible to group or others ("
                  + mode
                  + "); make it owner-only (chmod 600) or set"
                  + " argos.security.master-key.permissive=true");
        }
        warnings.add(
            setting
                + ": the key file is accessible to group or others ("
                + mode
                + "); accepted because argos.security.master-key.permissive=true. Make it"
                + " owner-only (chmod 600)");
      }
    }
    String text;
    try {
      text = PrivateFiles.read(file, MAX_FILE_BYTES);
    } catch (IOException e) {
      throw new MasterKeyException(
          setting + ": the key file cannot be read (" + e.getClass().getSimpleName() + ")");
    }
    try {
      return MasterKey.parse(text);
    } catch (IllegalArgumentException e) {
      throw new MasterKeyException(setting + ": " + e.getMessage());
    }
  }

  private static MasterKey parseValue(String setting, String value) throws MasterKeyException {
    try {
      return MasterKey.parse(value);
    } catch (IllegalArgumentException e) {
      throw new MasterKeyException(setting + ": " + e.getMessage());
    }
  }

  private static Path path(String setting, String value) throws MasterKeyException {
    try {
      return Path.of(value.strip());
    } catch (InvalidPathException e) {
      throw new MasterKeyException(setting + " is not a valid file name");
    }
  }
}
