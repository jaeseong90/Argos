/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import io.argos.server.wal.api.WalIdentity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The WAL directory ({@code argos.wal.dir}, 12 §3.4): created with mode 0700 on POSIX file systems,
 * its segment files and the persistent WAL identity file {@value #IDENTITY_FILE} (ADR-0007 C-61).
 *
 * <p>Identity rules: a directory without the identity file gets a new random instance id and epoch
 * 1. A directory with the identity file but without any segment was emptied (re-initialized): the
 * epoch increases by one, because segment numbering and positions start again and the tokens of the
 * old blocks must never repeat. Segments are never all deleted by the WAL itself (the active
 * segment stays), so a restart of a running WAL keeps its epoch.
 */
public final class WalDirectory {

  /** Name of the identity file. */
  public static final String IDENTITY_FILE = "wal-identity.properties";

  private static final Logger LOG = LoggerFactory.getLogger(WalDirectory.class);
  private static final Set<PosixFilePermission> DIR_MODE =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_MODE =
      PosixFilePermissions.fromString("rw-------");

  private final Path dir;
  private final WalIdentity identity;

  private WalDirectory(Path dir, WalIdentity identity) {
    this.dir = dir;
    this.identity = identity;
  }

  /**
   * Opens (and creates) {@code dir}, loads or creates the identity.
   *
   * @throws IOException when the directory or the identity file cannot be created or read
   */
  public static WalDirectory open(Path dir) throws IOException {
    Path absolute = dir.toAbsolutePath().normalize();
    createDirectory(absolute);
    boolean hasSegments = !listSegmentIds(absolute).isEmpty();
    Path idFile = absolute.resolve(IDENTITY_FILE);
    WalIdentity identity;
    if (Files.exists(idFile)) {
      WalIdentity stored = readIdentity(idFile);
      if (hasSegments) {
        identity = stored;
      } else {
        identity = new WalIdentity(stored.walInstanceId(), stored.epoch() + 1);
        writeIdentity(absolute, identity);
        LOG.warn(
            "WAL directory {} has an identity but no segment: re-initialized, epoch {} -> {}",
            absolute,
            stored.epoch(),
            identity.epoch());
      }
    } else {
      identity = new WalIdentity(UUID.randomUUID().toString(), 1);
      writeIdentity(absolute, identity);
      if (hasSegments) {
        LOG.warn(
            "WAL directory {} has segments but no identity file: new WAL identity {}",
            absolute,
            identity.walInstanceId());
      }
    }
    return new WalDirectory(absolute, identity);
  }

  /** The directory. */
  public Path path() {
    return dir;
  }

  /** The identity of this WAL. */
  public WalIdentity identity() {
    return identity;
  }

  /** The file of segment {@code segmentId}. */
  public Path segmentFile(int segmentId) {
    return dir.resolve(WalFormat.segmentName(segmentId));
  }

  /** Ids of the segment files, ascending. */
  public List<Integer> segmentIds() throws IOException {
    return listSegmentIds(dir);
  }

  /** Creates a new segment file with mode 0600 on POSIX (the caller writes the header). */
  FileChannel createSegment(int segmentId) throws IOException {
    Path file = segmentFile(segmentId);
    FileChannel channel =
        FileChannel.open(
            file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ);
    if (isPosix()) {
      Files.setPosixFilePermissions(file, FILE_MODE);
    }
    return channel;
  }

  /** Deletes segment {@code segmentId}; a missing file is not an error. */
  void deleteSegment(int segmentId) throws IOException {
    Files.deleteIfExists(segmentFile(segmentId));
  }

  /**
   * Makes a new or deleted directory entry durable (POSIX; a no-op where directories cannot sync).
   */
  void syncDirectory() {
    syncDirectory(dir);
  }

  private static void syncDirectory(Path dir) {
    if (!isPosix()) {
      return;
    }
    try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException e) {
      LOG.debug("Directory sync of {} not supported: {}", dir, e.getClass().getSimpleName());
    }
  }

  private static List<Integer> listSegmentIds(Path dir) throws IOException {
    List<Integer> ids = new ArrayList<>();
    try (Stream<Path> files = Files.list(dir)) {
      files.forEach(
          p -> {
            OptionalInt id = WalFormat.segmentId(p.getFileName().toString());
            if (id.isPresent()) {
              ids.add(id.getAsInt());
            }
          });
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
    ids.sort(null);
    return ids;
  }

  private static void createDirectory(Path dir) throws IOException {
    if (isPosix()) {
      if (!Files.isDirectory(dir)) {
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_MODE));
      }
      // An existing directory is tightened too (12 §3.4: 0700).
      Files.setPosixFilePermissions(dir, DIR_MODE);
    } else {
      Files.createDirectories(dir);
    }
  }

  private static WalIdentity readIdentity(Path file) throws IOException {
    Properties props = new Properties();
    try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      props.load(reader);
    }
    String id = props.getProperty("wal-instance-id");
    String epoch = props.getProperty("epoch");
    try {
      return new WalIdentity(id, Long.parseLong(epoch == null ? "" : epoch.strip()));
    } catch (RuntimeException e) {
      throw new IOException(
          "Invalid WAL identity file " + file + " (wal-instance-id, epoch): " + e.getMessage(), e);
    }
  }

  private static void writeIdentity(Path dir, WalIdentity identity) throws IOException {
    Path target = dir.resolve(IDENTITY_FILE);
    Path tmp = dir.resolve(IDENTITY_FILE + ".tmp");
    String content =
        String.format(
            Locale.ROOT,
            "# Argos WAL identity (ADR-0007 C-61): the first two parts of every insert"
                + " deduplication token.%n# Do not edit or copy to another server.%n"
                + "wal-instance-id=%s%nepoch=%d%n",
            identity.walInstanceId(),
            identity.epoch());
    try (FileChannel channel =
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      channel.write(java.nio.ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
      channel.force(true);
    }
    if (isPosix()) {
      Files.setPosixFilePermissions(tmp, FILE_MODE);
    }
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    syncDirectory(dir);
  }

  static boolean isPosix() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }
}
