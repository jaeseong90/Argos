/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The readOnly=false / AssignReturned allow-list (10 §5.3 A-5, DSN-02-01, C-9): {@code module |
 * advice class | advice method | annotation | reason} per line, '#' starts a comment.
 */
public record AdviceAllowlist(List<Entry> entries) {

  /** One allowed writable advice parameter or AssignReturned method. */
  public record Entry(
      String module, String adviceClass, String method, String annotation, String reason) {}

  public AdviceAllowlist {
    entries = List.copyOf(entries);
  }

  public static AdviceAllowlist empty() {
    return new AdviceAllowlist(List.of());
  }

  public static AdviceAllowlist read(Path file) {
    try {
      return parse(Files.readAllLines(file, StandardCharsets.UTF_8), file.toString());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the advice allow-list " + file, e);
    }
  }

  public static AdviceAllowlist parse(List<String> lines, String source) {
    List<Entry> entries = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] columns = line.split("\\|", -1);
      if (columns.length != 5) {
        throw new IllegalArgumentException(
            source + ":" + (i + 1) + ": expected 'module | class | method | annotation | reason'");
      }
      for (int c = 0; c < columns.length; c++) {
        columns[c] = columns[c].strip();
        if (columns[c].isEmpty()) {
          throw new IllegalArgumentException(source + ":" + (i + 1) + ": empty column " + (c + 1));
        }
      }
      entries.add(new Entry(columns[0], columns[1], columns[2], columns[3], columns[4]));
    }
    return new AdviceAllowlist(entries);
  }

  /** True when {@code adviceClass.method} may use {@code annotation} as a writable advice value. */
  public boolean allows(String module, String adviceClass, String method, String annotation) {
    return entries.stream()
        .anyMatch(
            e ->
                e.module().equals(module)
                    && e.adviceClass().equals(adviceClass)
                    && e.method().equals(method)
                    && e.annotation().equals(annotation));
  }
}
