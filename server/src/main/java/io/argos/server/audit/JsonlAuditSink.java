/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;

/**
 * The JSON Lines audit sink (12 §8.1 {@code file:<path>}, 16 §11.4) and the fallback file of 16
 * §11.3 ({@code argos.audit.fallback-file}): one {@link AuditRecordJson} line per record, appended
 * and forced to the device ({@code fsync} of the data) before {@link #write} returns.
 *
 * <p>Size rolling ({@code argos.audit.file.max-bytes}, {@code max-files}): a line that would take
 * the file past {@code maxBytes} first rolls it: {@code <file>.<max-files - 1>} is deleted, {@code
 * <file>.<n>} becomes {@code <file>.<n + 1>}, the file becomes {@code <file>.1}, and a new file is
 * started. At most {@code maxFiles} files exist per sink, the current one included. Nothing is
 * deleted by age: an operator's log shipping collects the rolled files.
 *
 * <p>Permissions (16 §11.3, the records are security data): on a POSIX file system the directory is
 * created {@code 0700} and every file {@code 0600}; an existing directory keeps its mode. On a file
 * system without POSIX attributes (Windows) the files inherit the access list of their directory:
 * the operator restricts the directory (docs/ops: server log and audit directories). Writes are
 * serialised by the sink's lock; one write failure does not close the sink, the next write opens
 * the file again.
 */
public final class JsonlAuditSink implements AuditSink {

  private static final boolean POSIX =
      FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  private static final Set<PosixFilePermission> FILE_MODE =
      PosixFilePermissions.fromString("rw-------");
  private static final Set<PosixFilePermission> DIR_MODE =
      PosixFilePermissions.fromString("rwx------");

  private final Path file;
  private final long maxBytes;
  private final int maxFiles;
  private final Object lock = new Object();
  private FileChannel channel;
  private long size;

  /**
   * @param file the file (relative paths resolve against the working directory)
   * @param maxBytes size of one file before it rolls (at least 1 KiB)
   * @param maxFiles files kept, the current one included (at least 2)
   */
  public JsonlAuditSink(Path file, long maxBytes, int maxFiles) {
    this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    if (maxBytes < 1024) {
      throw new IllegalArgumentException("argos.audit.file.max-bytes must be at least 1KB");
    }
    if (maxFiles < 2) {
      throw new IllegalArgumentException("argos.audit.file.max-files must be at least 2");
    }
    this.maxBytes = maxBytes;
    this.maxFiles = maxFiles;
  }

  /** The file written now. */
  public Path file() {
    return file;
  }

  /** Whether the file system applies the POSIX modes of the class comment. */
  public static boolean posix() {
    return POSIX;
  }

  @Override
  public void write(AuditRecord record) {
    byte[] line = (AuditRecordJson.line(record) + "\n").getBytes(StandardCharsets.UTF_8);
    synchronized (lock) {
      try {
        if (channel == null) {
          open();
        }
        if (size > 0 && size + line.length > maxBytes) {
          roll();
        }
        ByteBuffer buffer = ByteBuffer.wrap(line);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(false);
        size += line.length;
      } catch (IOException e) {
        closeQuietly();
        throw new UncheckedIOException("Cannot write the audit file " + file.getFileName(), e);
      }
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      closeQuietly();
    }
  }

  private void open() throws IOException {
    Path dir = file.getParent();
    if (dir != null && !Files.isDirectory(dir)) {
      if (POSIX) {
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_MODE));
      } else {
        Files.createDirectories(dir);
      }
    }
    FileAttribute<?>[] attributes =
        POSIX
            ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE_MODE)}
            : new FileAttribute<?>[0];
    boolean existed = Files.exists(file);
    channel =
        FileChannel.open(
            file,
            Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND),
            attributes);
    if (POSIX && existed) {
      // The attribute applies only to a new file: an existing one (made by hand or restored from a
      // backup) keeps its mode, so it is tightened here.
      Files.setPosixFilePermissions(file, FILE_MODE);
    }
    size = channel.size();
  }

  private void roll() throws IOException {
    closeQuietly();
    Files.deleteIfExists(rolled(maxFiles - 1));
    for (int n = maxFiles - 2; n >= 1; n--) {
      Path from = rolled(n);
      if (Files.exists(from)) {
        Files.move(from, rolled(n + 1), StandardCopyOption.REPLACE_EXISTING);
      }
    }
    Files.move(file, rolled(1), StandardCopyOption.REPLACE_EXISTING);
    open();
  }

  /** {@code <file>.<n>}. */
  Path rolled(int n) {
    return file.resolveSibling(file.getFileName() + "." + n);
  }

  private void closeQuietly() {
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException e) {
        // nothing to do: the next write opens the file again
      }
      channel = null;
    }
  }
}
