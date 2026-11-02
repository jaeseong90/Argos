/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * {@code <log.dir>/argos-agent.log}, rolled at {@code log.max-file-mb} into {@code .1} ... {@code
 * .<max-files - 1>} (10 §14: 10 MB x 5). The directory is created with mode 0700 on POSIX file
 * systems.
 */
final class RollingFileSink implements LogSink {
  static final String FILE_NAME = "argos-agent.log";
  private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);

  private final File directory;
  private final File file;
  private final long maxBytes;
  private final int maxFiles;
  private OutputStream out;
  private long size;

  private RollingFileSink(File directory, long maxBytes, int maxFiles) {
    this.directory = directory;
    this.file = new File(directory, FILE_NAME);
    this.maxBytes = maxBytes;
    this.maxFiles = maxFiles;
  }

  /**
   * Opens (creating the directory if needed) the log file in {@code directory}.
   *
   * @throws IOException when the directory or the file cannot be created or written
   */
  static RollingFileSink open(File directory, long maxBytes, int maxFiles) throws IOException {
    createDirectory(directory);
    RollingFileSink sink = new RollingFileSink(directory, maxBytes, Math.max(1, maxFiles));
    sink.openFile();
    return sink;
  }

  /** Creates {@code directory} (mode 0700 where POSIX permissions exist) unless it exists. */
  static void createDirectory(File directory) throws IOException {
    if (directory.isDirectory()) {
      return;
    }
    Path path = directory.toPath();
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.createDirectories(
          path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } else {
      Files.createDirectories(path);
    }
  }

  private void openFile() throws IOException {
    out = new BufferedOutputStream(new FileOutputStream(file, true), 8192);
    size = file.length();
  }

  @Override
  public void write(LogRecord record, String line) throws IOException {
    byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
    if (size > 0 && size + bytes.length + 1 > maxBytes) {
      roll();
    }
    out.write(bytes);
    out.write(NEWLINE);
    size += bytes.length + 1;
  }

  private void roll() throws IOException {
    out.close();
    // argos-agent.log.(n-1) is dropped; .k -> .k+1; argos-agent.log -> .1
    File oldest = rolled(maxFiles - 1);
    if (maxFiles > 1 && oldest.exists() && !oldest.delete()) {
      throw new IOException("cannot delete " + oldest);
    }
    for (int i = maxFiles - 2; i >= 1; i--) {
      File from = rolled(i);
      if (from.exists() && !from.renameTo(rolled(i + 1))) {
        throw new IOException("cannot rename " + from);
      }
    }
    if (maxFiles > 1) {
      if (!file.renameTo(rolled(1))) {
        throw new IOException("cannot rename " + file);
      }
    } else if (!file.delete()) {
      throw new IOException("cannot delete " + file);
    }
    openFile();
  }

  private File rolled(int index) {
    return new File(directory, FILE_NAME + "." + index);
  }

  @Override
  public void flush() throws IOException {
    out.flush();
  }

  @Override
  public void close() {
    try {
      out.close();
    } catch (IOException ignored) {
      // closing at shutdown; nothing left to report to
    }
  }

  @Override
  public String describe() {
    return "file " + file.getAbsolutePath();
  }
}
