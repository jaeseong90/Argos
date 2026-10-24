/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.load;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Writes every block once to {@code <dir>/<table>/<sequence>.rowbinary}
 * (RowBinaryWithNamesAndTypes, loadable with {@code clickhouse-client --query "INSERT INTO ...
 * FORMAT RowBinaryWithNamesAndTypes" < file}) and lists the blocks with their tokens in {@code
 * <dir>/blocks.tsv}. A re-sent block (same token) is listed again but not rewritten.
 */
public final class FileInserter implements Inserter {

  private final Path dir;
  private final Writer index;
  private final Set<String> written = new HashSet<>();

  public FileInserter(Path dir) {
    this.dir = dir;
    try {
      Files.createDirectories(dir);
      this.index = Files.newBufferedWriter(dir.resolve("blocks.tsv"), StandardCharsets.UTF_8);
      index.write("table\tsequence\ttoken\trows\tbytes\tfile\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public long insert(Block block) {
    String file =
        block.table().name()
            + "/"
            + String.format(Locale.ROOT, "%08d", block.sequence())
            + ".rowbinary";
    try {
      boolean first;
      synchronized (written) {
        first = written.add(block.token());
      }
      if (first) {
        Path path = dir.resolve(file);
        Files.createDirectories(path.getParent());
        Files.write(path, block.data());
      }
      synchronized (index) {
        index.write(
            block.table().name()
                + '\t'
                + block.sequence()
                + '\t'
                + block.token()
                + '\t'
                + block.rows()
                + '\t'
                + block.data().length
                + '\t'
                + file
                + '\n');
      }
      return first ? block.rows() : 0;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public String describe() {
    return "file " + dir;
  }

  @Override
  public void close() {
    try {
      synchronized (index) {
        index.close();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
