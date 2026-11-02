/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.overhead;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** File helpers: hashes, copies, log excerpts, JSON files. */
final class FileTools {
  private FileTools() {}

  static String sha256(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[1 << 16];
      int n;
      while ((n = in.read(buffer)) > 0) {
        digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Copies {@code file} into {@code dir} (same name) and returns the copy. */
  static Path copyInto(Path file, Path dir) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new UsageException("missing file " + file + " (build it first, see README.md)");
    }
    Files.createDirectories(dir);
    return Files.copy(
        file, dir.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Renames {@code from} to {@code to} (a run directory of a discarded attempt), retrying for up to
   * 30 s: on Windows a file that the just-stopped app or a virus scanner still holds makes the
   * rename fail with {@link java.nio.file.AccessDeniedException} for a moment (seen in a startup
   * set right after the app process exited).
   */
  static void moveDirectory(Path from, Path to) throws IOException, InterruptedException {
    FileSystemException last = null;
    for (int attempt = 0; attempt < 60; attempt++) {
      try {
        Files.move(from, to);
        return;
      } catch (FileAlreadyExistsException e) {
        throw e;
      } catch (FileSystemException e) {
        last = e;
        Thread.sleep(500);
      }
    }
    throw last;
  }

  static void writeJson(Path file, Object value) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    Files.writeString(file, Json.write(value), StandardCharsets.UTF_8);
  }

  static Object readJson(Path file) throws IOException {
    return Json.parse(Files.readString(file, StandardCharsets.UTF_8));
  }

  /** The file's text, "" when absent; malformed UTF-8 is replaced, not fatal. */
  static String readLenient(Path file) {
    try {
      if (!Files.isRegularFile(file)) {
        return "";
      }
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPLACE)
          .onUnmappableCharacter(CodingErrorAction.REPLACE)
          .decode(ByteBuffer.wrap(Files.readAllBytes(file)))
          .toString();
    } catch (IOException e) {
      return "";
    }
  }

  /**
   * Writes the first {@code head} and the last {@code tail} lines of {@code log} to {@code excerpt}
   * (with the total line count) and deletes {@code log} unless {@code keep}. The app logs one stack
   * trace per intended 500 (5% of the load), about 250 MB per 13-minute run.
   */
  static long excerpt(Path log, Path excerpt, int head, int tail, boolean keep) throws IOException {
    if (!Files.isRegularFile(log)) {
      return 0;
    }
    Excerpt lines = new Excerpt(head, tail);
    try (BufferedReader reader = utf8Reader(Files.newInputStream(log))) {
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        lines.accept(line);
      }
    }
    lines.write(
        excerpt,
        log.getFileName() + ": " + lines.lines() + " lines",
        keep ? " (kept)" : " (deleted after this excerpt)");
    if (!keep) {
      Files.delete(log);
    }
    return lines.lines();
  }

  /** A reader that replaces malformed UTF-8 instead of failing. */
  static BufferedReader utf8Reader(InputStream in) {
    return new BufferedReader(
        new InputStreamReader(
            in,
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)));
  }

  /**
   * The first {@code head} and last {@code tail} lines of a log and its WARN/ERROR counts, fed line
   * by line (from a file, or from the app's output pipe while it runs).
   */
  static final class Excerpt {
    private final int head;
    private final int tail;
    private final List<String> first = new ArrayList<>();
    private final Deque<String> last = new ArrayDeque<>();
    private final LogLevels levels = new LogLevels();
    private long lines;

    Excerpt(int head, int tail) {
      this.head = head;
      this.tail = tail;
    }

    synchronized void accept(String line) {
      lines++;
      levels.accept(line);
      if (first.size() < head) {
        first.add(line);
      } else {
        last.addLast(line);
        if (last.size() > tail) {
          last.removeFirst();
        }
      }
    }

    synchronized long lines() {
      return lines;
    }

    /** Writes the excerpt and, next to it, {@code app-log-levels.txt}. */
    synchronized void write(Path excerpt, String title, String note) throws IOException {
      StringBuilder out = new StringBuilder();
      out.append("# ").append(title).append(note).append('\n');
      first.forEach(l -> out.append(l).append('\n'));
      if (lines > first.size() + last.size()) {
        out.append("# ... ").append(lines - first.size() - last.size()).append(" lines ...\n");
      }
      last.forEach(l -> out.append(l).append('\n'));
      Files.writeString(excerpt, out.toString(), StandardCharsets.UTF_8);
      Files.writeString(
          excerpt.resolveSibling("app-log-levels.txt"), levels.report(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Counts WARN/ERROR log lines and exception lines of the app log, grouped by logger and message
   * with digits folded, so that the cause of unexpected answers survives the log's deletion.
   */
  static final class LogLevels {
    private static final Pattern LEVEL =
        Pattern.compile("^\\S+\\s+(ERROR|WARN)\\s.*?\\]\\s+(\\S+)\\s+:\\s+(.*)$");
    private static final Pattern EXCEPTION =
        Pattern.compile("^(?:Caused by: )?([\\w.$]+(?:Exception|Error))(?::\\s*(.*))?$");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final int MAX_KEYS = 500;

    private final Map<String, Long> counts = new LinkedHashMap<>();
    private long overflow;

    void accept(String line) {
      Matcher level = LEVEL.matcher(line);
      if (level.matches()) {
        add(level.group(1) + " " + level.group(2) + ": " + fold(level.group(3)));
        return;
      }
      Matcher exception = EXCEPTION.matcher(line.trim());
      if (exception.matches()) {
        add(
            "exception "
                + exception.group(1)
                + (exception.group(2) == null ? "" : ": " + fold(exception.group(2))));
      }
    }

    private static String fold(String message) {
      String m = DIGITS.matcher(message).replaceAll("#");
      return m.length() > 160 ? m.substring(0, 160) + "..." : m;
    }

    private void add(String key) {
      if (counts.containsKey(key) || counts.size() < MAX_KEYS) {
        counts.merge(key, 1L, Long::sum);
      } else {
        overflow++;
      }
    }

    String report() {
      StringBuilder out =
          new StringBuilder("# WARN/ERROR and exception lines of the app log, by count\n");
      counts.entrySet().stream()
          .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
          .forEach(e -> out.append(e.getValue()).append('\t').append(e.getKey()).append('\n'));
      if (overflow > 0) {
        out.append(overflow)
            .append("\tother lines (more than ")
            .append(MAX_KEYS)
            .append(" keys)\n");
      }
      return out.toString();
    }
  }
}
