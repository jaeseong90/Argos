/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.diagnostics;

import io.argos.server.config.ArgosProperties;
import io.argos.server.config.EffectiveServerConfig;
import io.argos.server.config.ServerBuild;
import io.argos.server.storage.clickhouse.StorageDiagnostics;
import io.argos.server.storage.postgres.ops.AdminCommandDatabase;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.ConfigurableEnvironment;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The diagnostic bundle of {@code argos-admin bundle} (12 §9.5 {@code DiagnosticBundle}, 17 §4.4,
 * SEC-06 server part): one {@code argos-diag-<host>-<yyyyMMdd'T'HHmmss'Z'>.tar.gz}, owner-only
 * (0600 on POSIX), for a support request. Every text in it passes {@link BundleMasking}; it holds:
 *
 * <ul>
 *   <li>{@code manifest.json}: when, which host, which period, each section's status, what was
 *       masked (rules and how many configured secrets, never a value);
 *   <li>{@code version.json}: server version, commit, protocol, Java, operating system;
 *   <li>{@code config/effective.json}: every {@code argos.*} setting with value and source ({@link
 *       EffectiveServerConfig}, secrets {@code ****});
 *   <li>{@code logs/argos-server.log[.N]}: the server log lines of the period ({@code --since},
 *       default 24 hours, at most 7 days), at most {@value #MAX_LOG_BYTES} bytes in all, newest
 *       file first;
 *   <li>{@code postgres/ch_schema_history.json}, {@code postgres/server.json}: ClickHouse migration
 *       history, PostgreSQL version and database size;
 *   <li>{@code clickhouse/*.jsonl}: the storage state ({@link StorageDiagnostics}: disks, parts per
 *       table, busiest partitions, merges, mutations, insert counters);
 *   <li>{@code inventory/instances.json}, {@code inventory/tokens.json}: instances (application,
 *       name, host, versions, status, last heartbeat) and agent tokens (prefix only);
 *   <li>{@code system/info.json}: operating system, JVM, processors, memory, the file systems of
 *       the data, WAL and log directories.
 * </ul>
 *
 * Not included at all: the master key, database passwords, notification channel settings, the
 * metrics token, agent tokens, audit rows and collected data (transactions, SQL, users). A section
 * whose source is unavailable (database down) is recorded as failed in the manifest and the bundle
 * is still written: it is most needed when something is broken.
 */
public final class DiagnosticBundle {

  /** Longest period of {@code --since}. */
  public static final Duration MAX_SINCE = Duration.ofDays(7);

  /** Default period. */
  public static final Duration DEFAULT_SINCE = Duration.ofHours(24);

  /** Most bytes of copied log lines. */
  public static final long MAX_LOG_BYTES = 256L * 1024 * 1024;

  /** Default log directory of the server ({@code log4j2-spring.xml}). */
  public static final String DEFAULT_LOG_DIR = "logs/server";

  /** The server log file name. */
  public static final String LOG_FILE = "argos-server.log";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
  private static final Pattern TS = Pattern.compile("\"ts\"\\s*:\\s*\"([^\"]{10,40})\"");
  private static final Pattern HOST = Pattern.compile("[^A-Za-z0-9.-]");

  /**
   * What to bundle.
   *
   * @param since the period of the log
   * @param outDir where the archive is written (created owner-only when missing)
   * @param logDir the server log directory, or null for {@code logging.file.path} or {@value
   *     #DEFAULT_LOG_DIR}
   */
  public record Options(Duration since, Path outDir, @Nullable Path logDir) {
    public Options {
      Objects.requireNonNull(since, "since");
      Objects.requireNonNull(outDir, "outDir");
      if (since.isNegative() || since.isZero() || since.compareTo(MAX_SINCE) > 0) {
        throw new IllegalArgumentException("since must be more than 0 and at most 7 days");
      }
    }
  }

  /**
   * One section of the bundle.
   *
   * @param name e.g. {@code logs}, {@code clickhouse}
   * @param ok whether it was collected
   * @param detail a count or the error (one line, masked)
   */
  public record Section(String name, boolean ok, String detail) {}

  /**
   * The written bundle.
   *
   * @param file the archive
   * @param bytes its size
   * @param entries the entry names, in order
   * @param sections the sections
   */
  public record Result(Path file, long bytes, List<String> entries, List<Section> sections) {}

  private final ArgosProperties properties;
  private final ConfigurableEnvironment environment;
  private final ServerBuild build;
  private final Clock clock;
  private final BundleMasking masking;

  /**
   * @param properties the server configuration
   * @param environment its environment (sources of the effective configuration, the log path)
   * @param build the server build
   * @param clock UTC clock
   */
  public DiagnosticBundle(
      ArgosProperties properties,
      ConfigurableEnvironment environment,
      ServerBuild build,
      Clock clock) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.environment = Objects.requireNonNull(environment, "environment");
    this.build = Objects.requireNonNull(build, "build");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.masking = BundleMasking.of(properties);
  }

  /**
   * Writes the bundle.
   *
   * @throws IOException when the archive cannot be written
   */
  public Result write(Options options) throws IOException {
    Instant now = clock.instant();
    String host = hostName();
    String base = "argos-diag-" + host + "-" + STAMP.format(now);
    Path outDir = options.outDir().toAbsolutePath().normalize();
    createPrivateDirectories(outDir);
    Path work = createPrivateTempDirectory(outDir, ".argos-diag-");
    Path archive = outDir.resolve(base + ".tar.gz");
    List<String> entries = new ArrayList<>();
    List<Section> sections = new ArrayList<>();
    Map<String, byte[]> texts = new LinkedHashMap<>();
    Map<String, Path> files = new LinkedHashMap<>();
    try {
      texts.put("version.json", json(version()));
      section(
          sections,
          "config",
          () -> {
            texts.put("config/effective.json", json(effectiveConfig()));
            return "ok";
          });
      section(sections, "logs", () -> copyLogs(options, now, work, files));
      section(sections, "postgres", () -> postgres(texts));
      section(sections, "clickhouse", () -> clickhouse(texts));
      section(
          sections,
          "system",
          () -> {
            texts.put("system/info.json", json(systemInfo(options)));
            return "ok";
          });
      ObjectNode manifest = JSON.createObjectNode();
      manifest.put("format", "argos-diag/1");
      manifest.put("created_at", now.toString());
      manifest.put("host", host);
      manifest.put("server_version", build.version());
      manifest.put("since", options.since().toString());
      manifest.put("from", now.minus(options.since()).toString());
      ArrayNode s = manifest.putArray("sections");
      for (Section section : sections) {
        ObjectNode o = s.addObject();
        o.put("name", section.name());
        o.put("ok", section.ok());
        o.put("detail", section.detail());
      }
      ObjectNode m = manifest.putObject("masking");
      m.put(
          "rules",
          "agent tokens -> argos_****; credentials in URLs, Authorization, cookies, JWTs, PEM keys,"
              + " key=value secrets -> ****; URL paths and queries -> /****; the setup token line;"
              + " every configured secret value -> ****");
      m.put("configured_secrets", masking.literalCount());
      ArrayNode excluded = m.putArray("excluded");
      for (String e :
          List.of(
              "master key",
              "database passwords",
              "notification channel settings",
              "metrics token",
              "agent tokens",
              "audit log",
              "collected data")) {
        excluded.add(e);
      }
      createOwnerOnly(archive);
      try (OutputStream raw = Files.newOutputStream(archive, StandardOpenOption.TRUNCATE_EXISTING);
          TarGzWriter tar = new TarGzWriter(raw, now)) {
        add(tar, base, "manifest.json", masked(json(manifest)), entries);
        for (Map.Entry<String, byte[]> e : texts.entrySet()) {
          add(tar, base, e.getKey(), masked(e.getValue()), entries);
        }
        for (Map.Entry<String, Path> e : files.entrySet()) {
          tar.add(base + "/" + e.getKey(), e.getValue());
          entries.add(base + "/" + e.getKey());
        }
      } catch (IOException | RuntimeException e) {
        Files.deleteIfExists(archive);
        throw e;
      }
    } finally {
      deleteRecursively(work);
    }
    return new Result(archive, Files.size(archive), List.copyOf(entries), List.copyOf(sections));
  }

  private static void add(
      TarGzWriter tar, String base, String name, byte[] data, List<String> entries)
      throws IOException {
    tar.add(base + "/" + name, data);
    entries.add(base + "/" + name);
  }

  private byte[] masked(byte[] utf8) {
    return masking.mask(new String(utf8, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
  }

  @FunctionalInterface
  private interface SectionBody {
    String collect() throws Exception;
  }

  private void section(List<Section> sections, String name, SectionBody body) {
    try {
      sections.add(new Section(name, true, body.collect()));
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      message = masking.mask(message.replaceAll("[\\r\\n\\t]+", " "));
      sections.add(
          new Section(
              name, false, message.length() > 300 ? message.substring(0, 297) + "..." : message));
    }
  }

  // --- sections --------------------------------------------------------------------------------

  private ObjectNode version() {
    ObjectNode v = JSON.createObjectNode();
    v.put("version", build.version());
    v.put("commit", build.commit());
    v.put("protocol", build.protocol());
    v.put("release", build.isRelease());
    v.put("java_version", System.getProperty("java.version", "?"));
    v.put("java_vendor", System.getProperty("java.vendor", "?"));
    v.put("java_vm", System.getProperty("java.vm.name", "?"));
    return v;
  }

  private ArrayNode effectiveConfig() {
    ArrayNode a = JSON.createArrayNode();
    for (EffectiveServerConfig.Entry e :
        new EffectiveServerConfig(environment, properties).entries()) {
      ObjectNode o = a.addObject();
      o.put("key", e.key());
      o.put("value", e.value());
      o.put("source", e.source());
    }
    return a;
  }

  /** The log directory: the option, {@code logging.file.path}, or {@value #DEFAULT_LOG_DIR}. */
  Path logDirectory(Options options) {
    if (options.logDir() != null) {
      return options.logDir();
    }
    String configured = environment.getProperty("logging.file.path");
    return Path.of(
        configured == null || configured.isBlank() ? DEFAULT_LOG_DIR : configured.strip());
  }

  private String copyLogs(Options options, Instant now, Path work, Map<String, Path> files)
      throws IOException {
    Path dir = logDirectory(options).toAbsolutePath().normalize();
    if (!Files.isDirectory(dir)) {
      throw new IOException("No log directory " + dir);
    }
    Instant from = now.minus(options.since());
    List<Path> logs = new ArrayList<>();
    Path active = dir.resolve(LOG_FILE);
    if (Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS)) {
      logs.add(active);
    }
    for (int i = 1; i <= 99; i++) {
      Path rolled = dir.resolve(LOG_FILE + "." + i);
      if (!Files.isRegularFile(rolled, LinkOption.NOFOLLOW_LINKS)) {
        break;
      }
      logs.add(rolled);
    }
    long budget = MAX_LOG_BYTES;
    long lines = 0;
    boolean truncated = false;
    for (Path log : logs) {
      if (Files.getLastModifiedTime(log).toInstant().isBefore(from)) {
        break; // rolled files only get older
      }
      Path copy = work.resolve(log.getFileName().toString());
      long written = 0;
      try (BufferedReader in = Files.newBufferedReader(log, StandardCharsets.UTF_8);
          BufferedWriter out = Files.newBufferedWriter(copy, StandardCharsets.UTF_8)) {
        String line;
        while ((line = readLine(in)) != null) {
          if (!inPeriod(line, from)) {
            continue;
          }
          String m = masking.mask(line);
          long size = m.getBytes(StandardCharsets.UTF_8).length + 1L;
          if (written + size > budget) {
            truncated = true;
            break;
          }
          out.write(m);
          out.write('\n');
          written += size;
          lines++;
        }
      }
      budget -= written;
      files.put("logs/" + log.getFileName(), copy);
      if (truncated) {
        break;
      }
    }
    return lines
        + " lines from "
        + files.size()
        + " files"
        + (truncated ? " (cut at 256 MiB)" : "");
  }

  /** One line of at most 1 MiB (a longer one is cut). */
  private static String readLine(BufferedReader in) throws IOException {
    String line = in.readLine();
    if (line != null && line.length() > 1024 * 1024) {
      return line.substring(0, 1024 * 1024);
    }
    return line;
  }

  /** Whether a JSON log line's {@code ts} is at or after {@code from}; lines without one count. */
  static boolean inPeriod(String line, Instant from) {
    Matcher m = TS.matcher(line.length() > 200 ? line.substring(0, 200) : line);
    if (!m.find()) {
      return true;
    }
    try {
      return !Instant.parse(m.group(1)).isBefore(from);
    } catch (DateTimeParseException e) {
      return true;
    }
  }

  private String postgres(Map<String, byte[]> texts) throws Exception {
    try (AdminCommandDatabase db = AdminCommandDatabase.open(properties.postgres())) {
      List<Map<String, Object>> history = db.clickHouseSchemaHistory();
      texts.put("postgres/ch_schema_history.json", JSON.writeValueAsBytes(history));
      texts.put("postgres/server.json", JSON.writeValueAsBytes(db.serverInfo()));
      List<Map<String, Object>> instances = db.instanceSummary();
      texts.put("inventory/instances.json", JSON.writeValueAsBytes(instances));
      List<Map<String, Object>> tokens = db.tokenSummary();
      texts.put("inventory/tokens.json", JSON.writeValueAsBytes(tokens));
      return history.size()
          + " schema versions, "
          + instances.size()
          + " instances, "
          + tokens.size()
          + " tokens";
    }
  }

  private String clickhouse(Map<String, byte[]> texts) {
    int ok = 0;
    List<String> failed = new ArrayList<>();
    try (StorageDiagnostics ch = StorageDiagnostics.open(properties.clickhouse())) {
      for (Map.Entry<String, String> q : ch.queries().entrySet()) {
        try {
          texts.put(
              "clickhouse/" + q.getKey() + ".jsonl",
              ch.run(q.getValue()).getBytes(StandardCharsets.UTF_8));
          ok++;
        } catch (IllegalStateException e) {
          failed.add(q.getKey() + ": " + e.getMessage());
        }
      }
    }
    if (ok == 0 && !failed.isEmpty()) {
      throw new IllegalStateException(String.join("; ", failed));
    }
    return ok + " queries" + (failed.isEmpty() ? "" : ", failed: " + String.join("; ", failed));
  }

  private ObjectNode systemInfo(Options options) {
    ObjectNode s = JSON.createObjectNode();
    s.put("os_name", System.getProperty("os.name", "?"));
    s.put("os_version", System.getProperty("os.version", "?"));
    s.put("os_arch", System.getProperty("os.arch", "?"));
    s.put("processors", Runtime.getRuntime().availableProcessors());
    s.put("jvm_max_heap_bytes", Runtime.getRuntime().maxMemory());
    var os = ManagementFactory.getOperatingSystemMXBean();
    s.put("load_average", os.getSystemLoadAverage());
    if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
      s.put("memory_total_bytes", sun.getTotalMemorySize());
      s.put("memory_free_bytes", sun.getFreeMemorySize());
    }
    ArrayNode disks = s.putArray("file_systems");
    Map<String, Path> dirs = new LinkedHashMap<>();
    dirs.put("wal", Path.of(properties.wal().dir()));
    dirs.put("logs", logDirectory(options));
    dirs.put("bundle", options.outDir());
    for (Map.Entry<String, Path> d : dirs.entrySet()) {
      ObjectNode o = disks.addObject();
      o.put("use", d.getKey());
      o.put("path", d.getValue().toAbsolutePath().normalize().toString());
      try {
        FileStore store = Files.getFileStore(existing(d.getValue().toAbsolutePath()));
        o.put("total_bytes", store.getTotalSpace());
        o.put("usable_bytes", store.getUsableSpace());
      } catch (IOException | RuntimeException e) {
        o.put("error", e.getClass().getSimpleName());
      }
    }
    return s;
  }

  private static Path existing(Path p) {
    Path cur = p;
    while (cur != null && !Files.exists(cur)) {
      cur = cur.getParent();
    }
    return cur == null ? p : cur;
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static byte[] json(Object node) {
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
  }

  /** The host name for the file name: letters, digits, dot and dash only. */
  static String hostName() {
    String name = null;
    try {
      name = InetAddress.getLocalHost().getHostName();
    } catch (IOException | RuntimeException e) {
      name = System.getenv("HOSTNAME");
    }
    if (name == null || name.isBlank()) {
      name = "unknown";
    }
    String clean = HOST.matcher(name).replaceAll("-");
    return clean.length() > 64 ? clean.substring(0, 64) : clean;
  }

  private static boolean posix(Path p) {
    return p.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  private static void createPrivateDirectories(Path dir) throws IOException {
    if (Files.isDirectory(dir)) {
      return;
    }
    if (posix(dir)) {
      Files.createDirectories(
          dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } else {
      Files.createDirectories(dir);
    }
  }

  private static Path createPrivateTempDirectory(Path parent, String prefix) throws IOException {
    if (posix(parent)) {
      return Files.createTempDirectory(
          parent,
          prefix,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    return Files.createTempDirectory(parent, prefix);
  }

  /** Creates {@code file} empty and owner-only (0600 on POSIX) before anything is written to it. */
  private static void createOwnerOnly(Path file) throws IOException {
    Files.deleteIfExists(file);
    if (posix(file)) {
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } else {
      Files.createFile(file);
    }
  }

  private static void deleteRecursively(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
        Files.deleteIfExists(p);
      }
    } catch (IOException e) {
      // best effort: the work directory is owner-only and holds masked copies only
    }
  }
}
