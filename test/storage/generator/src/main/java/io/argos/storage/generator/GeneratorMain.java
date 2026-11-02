/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.clickhouse.ClickHouseImage;
import io.argos.storage.generator.load.ClickHouseInserter;
import io.argos.storage.generator.load.CountingInserter;
import io.argos.storage.generator.load.FileInserter;
import io.argos.storage.generator.load.Inserter;
import io.argos.storage.schema.SchemaApplier;
import io.argos.storage.schema.SchemaParameters;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Command line of the storage PoC generator ({@code storageGenerate} / {@code storageBench}).
 *
 * <pre>
 *   --target=none            generate and count only (dry run, generator throughput)
 *   --target=file:DIR        RowBinaryWithNamesAndTypes block files and blocks.tsv
 *   --target=docker          Testcontainers ClickHouse (bundled image, deploy/clickhouse config,
 *                            --docker-memory, optional --docker-volume to keep the data), schema
 *                            V001 applied as argos_migrator
 *   --target=url:http://H:8123/  an existing Argos ClickHouse; passwords from the environment:
 *                            ARGOS_CH_WRITER_PASSWORD (required), ARGOS_CH_READER_PASSWORD
 *                            (state sampling, storage summary), ARGOS_CH_MIGRATOR_PASSWORD
 *                            (--apply-schema=true)
 * </pre>
 *
 * Exit codes: 0 success, 1 failure, 2 invalid arguments.
 */
public final class GeneratorMain {

  private GeneratorMain() {}

  public static void main(String[] args) {
    int code;
    try {
      code = run(List.of(args));
    } catch (IllegalArgumentException e) {
      System.err.println("error: " + e.getMessage());
      code = 2;
    } catch (Exception e) {
      System.err.println("error: " + e);
      e.printStackTrace(System.err);
      code = 1;
    }
    System.exit(code);
  }

  /** Runs with {@code --key=value} arguments; returns the exit code. */
  public static int run(List<String> args) throws Exception {
    if (args.contains("--help") || args.contains("-h")) {
      System.out.println("Argos storage PoC generator (docs/13 §15, docs/18 §9)");
      System.out.println(GeneratorConfig.usage());
      return 0;
    }
    GeneratorConfig config = GeneratorConfig.parse(args);
    Path out = Path.of(config.out);
    String target = config.target;
    long now = System.currentTimeMillis();
    if (target.equals("none")) {
      CountingInserter inserter = new CountingInserter(false);
      new Generator(config, inserter, null, System.out).run(now, out);
      return 0;
    }
    if (target.startsWith("file:")) {
      try (FileInserter inserter = new FileInserter(Path.of(target.substring(5)))) {
        new Generator(config, inserter, null, System.out).run(now, out);
      }
      return 0;
    }
    if (target.equals("docker")) {
      return docker(config, out);
    }
    if (target.startsWith("url:")) {
      return url(config, out, URI.create(target.substring(4)));
    }
    throw new IllegalArgumentException("Unknown target: " + target);
  }

  private static int docker(GeneratorConfig config, Path out) throws Exception {
    ArgosClickHouse.Builder builder =
        ArgosClickHouse.builder()
            .memoryLimit(parseBytes(config.dockerMemory))
            .writerTablePrefixes(config.tablePrefix);
    if (!config.dockerVolume.isEmpty()) {
      builder.dataVolume(config.dockerVolume);
    }
    ClickHouseImage image = ClickHouseImage.bundled();
    System.out.println(
        "Starting ClickHouse "
            + image.image()
            + " (memory "
            + config.dockerMemory
            + (config.dockerVolume.isEmpty() ? "" : ", volume " + config.dockerVolume)
            + ")");
    try (ArgosClickHouse ch = builder.start()) {
      if (!config.applySchema.equals("false")) {
        SchemaApplier.apply(ch.client(Account.MIGRATOR), schema(config));
      }
      Inserter inserter =
          new ClickHouseInserter(ch.client(Account.WRITER), config.database, config.tablePrefix);
      new Generator(config, inserter, ch.client(Account.READER), System.out)
          .run(System.currentTimeMillis(), out);
      if (!config.dockerVolume.isEmpty()) {
        System.out.println(
            "Data kept in Docker volume "
                + config.dockerVolume
                + " (ArgosClickHouse.builder().dataVolume(\""
                + config.dockerVolume
                + "\"))");
      }
    }
    return 0;
  }

  private static int url(GeneratorConfig config, Path out, URI uri) throws Exception {
    ClickHouseHttp writer =
        new ClickHouseHttp(uri, Account.WRITER.user(), requiredEnv("ARGOS_CH_WRITER_PASSWORD"));
    String readerPassword = System.getenv("ARGOS_CH_READER_PASSWORD");
    ClickHouseHttp reader =
        readerPassword == null || readerPassword.isEmpty()
            ? null
            : new ClickHouseHttp(uri, Account.READER.user(), readerPassword);
    if (config.applySchema.equals("true")) {
      ClickHouseHttp migrator =
          new ClickHouseHttp(
              uri, Account.MIGRATOR.user(), requiredEnv("ARGOS_CH_MIGRATOR_PASSWORD"));
      SchemaApplier.apply(migrator, schema(config));
    }
    Inserter inserter = new ClickHouseInserter(writer, config.database, config.tablePrefix);
    new Generator(config, inserter, reader, System.out).run(System.currentTimeMillis(), out);
    return 0;
  }

  /** Schema parameters (database and prefix) of a configuration. */
  public static SchemaParameters schema(GeneratorConfig config) {
    return SchemaParameters.defaults()
        .withDatabase(config.database)
        .withTablePrefix(config.tablePrefix);
  }

  private static String requiredEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("Environment variable " + name + " is required");
    }
    return value;
  }

  /** Parses {@code 5g}, {@code 512m}, {@code 1073741824}. */
  static long parseBytes(String text) {
    String t = text.trim().toLowerCase(Locale.ROOT);
    long unit = 1;
    if (t.endsWith("g")) {
      unit = 1L << 30;
    } else if (t.endsWith("m")) {
      unit = 1L << 20;
    } else if (t.endsWith("k")) {
      unit = 1L << 10;
    }
    if (unit != 1) {
      t = t.substring(0, t.length() - 1);
    }
    return Math.round(Double.parseDouble(t) * unit);
  }
}
