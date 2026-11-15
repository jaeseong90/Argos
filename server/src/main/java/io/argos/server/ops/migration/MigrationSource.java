/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The ClickHouse migration files, read once into memory: {@code schema/V<NNN>__<desc>.sql}, {@code
 * rollups/<table>.sql} and {@code migrations/M<NNN>__<desc>.yaml} (13 §13.2), keyed by their path
 * relative to the source root (e.g. {@code schema/V001__init.sql}).
 *
 * <p>The single source of the files is {@code deploy/clickhouse} of the repository; the build
 * copies them into the server jar under {@value #CLASSPATH_ROOT} ({@code server/build.gradle.kts}),
 * which is what the server reads at start ({@link #fromClasspath}). Tests read the repository
 * directory and overlay fixtures ({@link #fromDirectory}, {@link #overlay}). {@code README.md}
 * files are documentation and never part of a source; any other file is checked by {@link
 * MigrationCatalog}.
 */
public final class MigrationSource {

  /** Location of the files in the server jar. */
  public static final String CLASSPATH_ROOT = "db/clickhouse";

  /** The three directories of a source. */
  public static final List<String> DIRECTORIES = List.of("schema", "rollups", "migrations");

  private static final String README = "README.md";

  private final String origin;
  private final SortedMap<String, String> files;

  private MigrationSource(String origin, SortedMap<String, String> files) {
    this.origin = origin;
    this.files = Collections.unmodifiableSortedMap(new TreeMap<>(files));
  }

  /**
   * The files bundled with the server: {@code
   * classpath*:db/clickhouse/{schema,rollups,migrations}/*} (UTF-8).
   *
   * @throws IllegalStateException when a path is found twice on the classpath
   */
  public static MigrationSource fromClasspath(ClassLoader classLoader) {
    PathMatchingResourcePatternResolver resolver =
        new PathMatchingResourcePatternResolver(classLoader);
    SortedMap<String, String> files = new TreeMap<>();
    for (String directory : DIRECTORIES) {
      Resource[] resources;
      try {
        resources = resolver.getResources("classpath*:" + CLASSPATH_ROOT + "/" + directory + "/*");
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot list " + CLASSPATH_ROOT + "/" + directory, e);
      }
      for (Resource resource : resources) {
        String name = resource.getFilename();
        if (name == null || name.isEmpty() || name.equals(README) || !resource.isReadable()) {
          continue;
        }
        String path = directory + "/" + name;
        String content = read(resource);
        String previous = files.put(path, content);
        if (previous != null) {
          throw new IllegalStateException(
              "ClickHouse migration file " + path + " found twice on the classpath");
        }
      }
    }
    return new MigrationSource("classpath:" + CLASSPATH_ROOT, files);
  }

  /**
   * The files of a directory with the layout of {@code deploy/clickhouse} (missing subdirectories
   * are empty; other subdirectories of the root are not read).
   */
  public static MigrationSource fromDirectory(Path root) {
    SortedMap<String, String> files = new TreeMap<>();
    for (String directory : DIRECTORIES) {
      Path dir = root.resolve(directory);
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (Stream<Path> entries = Files.list(dir)) {
        for (Path entry : entries.sorted().toList()) {
          String name = entry.getFileName().toString();
          if (name.equals(README)) {
            continue;
          }
          if (!Files.isRegularFile(entry)) {
            throw new IllegalStateException(
                "Unexpected entry in " + dir + ": " + name + " (no subdirectories)");
          }
          files.put(directory + "/" + name, Files.readString(entry, StandardCharsets.UTF_8));
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read " + dir, e);
      }
    }
    return new MigrationSource(root.toString(), files);
  }

  /** A source from explicit files (tests). */
  public static MigrationSource of(String origin, Map<String, String> files) {
    return new MigrationSource(origin, new TreeMap<>(files));
  }

  /**
   * This source with the files of {@code other} added; a file of {@code other} replaces the file
   * with the same path (tests: fixture scripts on top of the repository files).
   */
  public MigrationSource overlay(MigrationSource other) {
    SortedMap<String, String> merged = new TreeMap<>(files);
    merged.putAll(other.files);
    return new MigrationSource(origin + " + " + other.origin, merged);
  }

  /** Where the files come from (for messages). */
  public String origin() {
    return origin;
  }

  /** Every file, path to content, in path order. */
  public SortedMap<String, String> files() {
    return files;
  }

  /** File names in {@code directory} ({@code schema}, {@code rollups}, {@code migrations}). */
  public List<String> names(String directory) {
    String prefix = directory + "/";
    List<String> names = new ArrayList<>();
    for (String path : files.keySet()) {
      if (path.startsWith(prefix)) {
        names.add(path.substring(prefix.length()));
      }
    }
    return names;
  }

  /** The content of a file, if present. */
  public Optional<String> read(String path) {
    return Optional.ofNullable(files.get(path));
  }

  private static String read(Resource resource) {
    try (InputStream in = resource.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + resource.getDescription(), e);
    }
  }
}
