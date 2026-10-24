/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.RepositoryLayout;
import io.argos.storage.queries.QueryFile.Kind;
import io.argos.storage.queries.QueryFile.Section;
import io.argos.storage.queries.QueryFile.SectionType;
import io.argos.storage.schema.SchemaParameters;
import io.argos.storage.schema.SqlScript;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The query files of {@code test/storage/queries} (13 §6.3, §15, 18 §9): {@code params.sql} and
 * {@code Q01.sql}..{@code Q20.sql}.
 *
 * <p>File format (plain SQL with directive comments, so every file can be read and run by hand):
 *
 * <pre>
 * -- SPDX-License-Identifier: Apache-2.0
 * -- free description ...
 * -- &#64;id        Q01
 * -- &#64;title     ...
 * -- &#64;source    docs/13 §6.3 (1), §15 Q1
 * -- &#64;kind      timing | consistency | procedure
 * -- &#64;account   reader | migrator
 * -- &#64;criterion warm_ms &lt;= 1000          (or: rows == 0, record, procedure metrics)
 * -- &#64;settings  max_execution_time=10, ...
 *
 * -- &#64;probe name                          one row; its column aliases become parameters
 * SELECT ... AS end_minute ...
 *
 * -- &#64;variant name key=value ...          literal parameters of this variant
 * -- &#64;criterion / &#64;expect / &#64;settings / &#64;account   optional, before the SQL
 * -- &#64;optional reason                    probes only: the data may have no value (then the
 *                                         variants needing it are not applicable)
 * SELECT ... {end_minute:DateTime} ... {db}.{p}xlog ...
 *
 * -- &#64;step name                           a statement of a procedure
 * </pre>
 *
 * Plain comment lines inside a section are dropped from the statement.
 */
public final class QueryCatalog {

  /** Location of the query files in the repository. */
  public static final String DIRECTORY = "test/storage/queries";

  private static final Pattern FILE_NAME = Pattern.compile("Q(0[1-9]|1[0-9]|20)\\.sql");
  private static final Pattern DIRECTIVE = Pattern.compile("--\\s*@([a-z]+)\\s*(.*)");
  private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
  private static final Pattern PARAM = Pattern.compile("([a-z][a-z0-9_]*)=(\\S+)");
  private static final Pattern ALIAS = Pattern.compile("(?i)\\bAS\\s+([a-z_][a-z0-9_]*)");
  private static final Set<String> FILE_DIRECTIVES =
      Set.of("id", "title", "source", "kind", "account", "criterion", "settings", "uses");
  private static final Set<String> SECTION_DIRECTIVES =
      Set.of("account", "criterion", "expect", "settings", "optional");

  private final QueryFile parameters;
  private final Map<String, QueryFile> queries;

  private QueryCatalog(QueryFile parameters, Map<String, QueryFile> queries) {
    this.parameters = parameters;
    this.queries = queries;
  }

  /** Loads the catalog of the repository. */
  public static QueryCatalog fromRepository() {
    return load(RepositoryLayout.root().resolve(DIRECTORY));
  }

  /** Loads {@code params.sql} and every {@code Qnn.sql} of a directory. */
  public static QueryCatalog load(Path dir) {
    QueryFile params = parse(dir.resolve("params.sql"));
    Map<String, QueryFile> queries = new LinkedHashMap<>();
    try (Stream<Path> files = Files.list(dir)) {
      files
          .filter(f -> FILE_NAME.matcher(f.getFileName().toString()).matches())
          .sorted()
          .forEach(f -> queries.put(f.getFileName().toString().replace(".sql", ""), parse(f)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new QueryCatalog(params, queries);
  }

  /** The shared parameter probes. */
  public QueryFile parameters() {
    return parameters;
  }

  /** The query file with id {@code Q01}..{@code Q20}. */
  public QueryFile query(String id) {
    QueryFile f = queries.get(id);
    if (f == null) {
      throw new NoSuchElementException("No query file " + id);
    }
    return f;
  }

  /** All query files in id order. */
  public List<QueryFile> queries() {
    return List.copyOf(queries.values());
  }

  /**
   * Checks the catalog rules: Q01..Q20 all present with their directives, ids match file names,
   * variants of timing and consistency files exist, every typed parameter of a probe or variant is
   * produced by an earlier probe or the variant line, textual placeholders of probes and variants
   * are schema placeholders only, {@code @uses} references exist.
   *
   * @return problems found (empty when the catalog is valid)
   */
  public List<String> validate() {
    List<String> problems = new ArrayList<>();
    for (int i = 1; i <= 20; i++) {
      String id = String.format(Locale.ROOT, "Q%02d", i);
      if (!queries.containsKey(id)) {
        problems.add("missing " + id + ".sql");
      }
    }
    Set<String> schemaPlaceholders = SchemaParameters.defaults().placeholderValues().keySet();
    Set<String> shared = new LinkedHashSet<>();
    for (Section probe : parameters.probes()) {
      checkProbe(parameters, probe, shared, schemaPlaceholders, problems);
      shared.addAll(probe.outputs());
    }
    for (QueryFile f : queries.values()) {
      String expectedId = f.path().getFileName().toString().replace(".sql", "");
      if (!f.id().equals(expectedId)) {
        problems.add(f.path().getFileName() + ": @id " + f.id() + " does not match the file name");
      }
      if (f.title().isBlank() || f.source().isBlank()) {
        problems.add(f.id() + ": @title and @source are required");
      }
      if ((f.kind() == Kind.TIMING || f.kind() == Kind.CONSISTENCY) && f.variants().isEmpty()) {
        problems.add(f.id() + ": a " + f.kind() + " file needs at least one @variant");
      }
      Set<String> known = new LinkedHashSet<>(shared);
      for (Section probe : f.probes()) {
        checkProbe(f, probe, known, schemaPlaceholders, problems);
        known.addAll(probe.outputs());
      }
      for (Section v : f.variants()) {
        Set<String> available = new HashSet<>(known);
        available.addAll(v.params().keySet());
        for (String name : SqlTemplate.typedPlaceholders(v.sql()).keySet()) {
          if (!available.contains(name)) {
            problems.add(f.id() + "/" + v.name() + ": parameter {" + name + "} has no source");
          }
        }
        for (String name : SqlTemplate.textualPlaceholders(v.sql())) {
          if (!schemaPlaceholders.contains(name)) {
            problems.add(f.id() + "/" + v.name() + ": unknown placeholder {" + name + "}");
          }
        }
      }
      for (String use : f.uses()) {
        String[] parts = use.split("/", -1);
        if (parts.length != 2 || !queries.containsKey(parts[0])) {
          problems.add(f.id() + ": @uses " + use + " is not Qnn/variant");
        } else if (queries.get(parts[0]).variants().stream()
            .noneMatch(v -> v.name().equals(parts[1]))) {
          problems.add(f.id() + ": @uses " + use + " names no variant");
        }
      }
    }
    return problems;
  }

  private static void checkProbe(
      QueryFile f,
      Section probe,
      Set<String> known,
      Set<String> schemaPlaceholders,
      List<String> problems) {
    if (probe.outputs().isEmpty()) {
      problems.add(f.id() + "/" + probe.name() + ": a probe needs column aliases (AS name)");
    }
    for (String name : SqlTemplate.typedPlaceholders(probe.sql()).keySet()) {
      if (!known.contains(name)) {
        problems.add(f.id() + "/" + probe.name() + ": parameter {" + name + "} has no source");
      }
    }
    for (String name : SqlTemplate.textualPlaceholders(probe.sql())) {
      if (!schemaPlaceholders.contains(name)) {
        problems.add(f.id() + "/" + probe.name() + ": unknown placeholder {" + name + "}");
      }
    }
  }

  /** Parses one file. */
  public static QueryFile parse(Path path) {
    try {
      return parse(path, Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + path, e);
    }
  }

  /** Parses file content (exposed for tests). */
  static QueryFile parse(Path path, String text) {
    String file = path.getFileName().toString();
    if (!text.startsWith("-- SPDX-License-Identifier: Apache-2.0")) {
      throw new IllegalArgumentException(file + ": missing SPDX header");
    }
    Map<String, String> header = new LinkedHashMap<>();
    StringBuilder description = new StringBuilder();
    List<SectionBuilder> sections = new ArrayList<>();
    SectionBuilder current = null;
    int lineNo = 0;
    for (String raw : text.split("\n", -1)) {
      lineNo++;
      String line = raw.stripTrailing();
      String trimmed = line.strip();
      Matcher d = DIRECTIVE.matcher(trimmed);
      if (d.matches()) {
        String key = d.group(1);
        String value = d.group(2).strip();
        if (key.equals("probe") || key.equals("variant") || key.equals("step")) {
          current =
              new SectionBuilder(
                  file, lineNo, SectionType.valueOf(key.toUpperCase(Locale.ROOT)), value);
          sections.add(current);
        } else if (current == null) {
          if (!FILE_DIRECTIVES.contains(key)) {
            throw new IllegalArgumentException(file + ":" + lineNo + ": unknown directive @" + key);
          }
          if (header.put(key, value) != null) {
            throw new IllegalArgumentException(file + ":" + lineNo + ": duplicate @" + key);
          }
        } else {
          current.attribute(key, value, lineNo);
        }
      } else if (trimmed.startsWith("--")) {
        if (current == null && lineNo > 1) {
          description.append(trimmed.replaceFirst("^--\\s?", "")).append('\n');
        }
      } else if (current == null) {
        if (!trimmed.isEmpty()) {
          throw new IllegalArgumentException(file + ":" + lineNo + ": SQL outside a section");
        }
      } else {
        current.line(line);
      }
    }
    String id = header.getOrDefault("id", file.equals("params.sql") ? "params" : "");
    Kind kind =
        file.equals("params.sql")
            ? Kind.PARAMETERS
            : Kind.valueOf(require(header, "kind", file).toUpperCase(Locale.ROOT));
    Account account = account(header.getOrDefault("account", "reader"), file);
    Criterion criterion =
        kind == Kind.PARAMETERS
            ? Criterion.RECORD
            : Criterion.parse(require(header, "criterion", file));
    List<String> uses = new ArrayList<>();
    if (header.containsKey("uses")) {
      for (String u : header.get("uses").split(",", -1)) {
        uses.add(u.strip());
      }
    }
    List<Section> probes = new ArrayList<>();
    List<Section> variants = new ArrayList<>();
    List<Section> steps = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (SectionBuilder b : sections) {
      Section s = b.build();
      if (!names.add(s.type() + "/" + s.name())) {
        throw new IllegalArgumentException(file + ": duplicate " + s.type() + " " + s.name());
      }
      switch (s.type()) {
        case PROBE -> probes.add(s);
        case VARIANT -> variants.add(s);
        case STEP -> steps.add(s);
      }
    }
    return new QueryFile(
        path,
        id,
        header.getOrDefault("title", ""),
        header.getOrDefault("source", ""),
        kind,
        account,
        criterion,
        settings(header.getOrDefault("settings", ""), file),
        uses,
        description.toString().strip(),
        probes,
        variants,
        steps);
  }

  private static String require(Map<String, String> header, String key, String file) {
    String v = header.get(key);
    if (v == null || v.isBlank()) {
      throw new IllegalArgumentException(file + ": @" + key + " is required");
    }
    return v;
  }

  static Account account(String name, String file) {
    return switch (name) {
      case "reader" -> Account.READER;
      case "migrator" -> Account.MIGRATOR;
      case "writer" -> Account.WRITER;
      default -> throw new IllegalArgumentException(file + ": unknown account " + name);
    };
  }

  static Map<String, String> settings(String text, String file) {
    Map<String, String> settings = new LinkedHashMap<>();
    if (text.isBlank()) {
      return settings;
    }
    for (String part : text.split(",", -1)) {
      Matcher m = PARAM.matcher(part.strip());
      if (!m.matches()) {
        throw new IllegalArgumentException(file + ": invalid setting '" + part.strip() + "'");
      }
      settings.put(m.group(1), m.group(2));
    }
    return settings;
  }

  /** Column aliases at nesting depth 0 of a SELECT (the parameters a probe produces). */
  static List<String> topLevelAliases(String sql) {
    StringBuilder depthZero = new StringBuilder();
    int depth = 0;
    boolean quoted = false;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (quoted) {
        if (c == '\\') {
          i++;
        } else if (c == '\'') {
          quoted = false;
        }
        depthZero.append(' ');
        continue;
      }
      if (c == '\'') {
        quoted = true;
        depthZero.append(' ');
      } else if (c == '(') {
        depth++;
        depthZero.append(' ');
      } else if (c == ')') {
        depth--;
        depthZero.append(' ');
      } else {
        depthZero.append(depth == 0 ? c : ' ');
      }
    }
    List<String> aliases = new ArrayList<>();
    Matcher m = ALIAS.matcher(depthZero);
    while (m.find()) {
      aliases.add(m.group(1));
    }
    return aliases;
  }

  private static final class SectionBuilder {
    private final String file;
    private final int line;
    private final SectionType type;
    private final String name;
    private final Map<String, String> params = new LinkedHashMap<>();
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private final List<String> sql = new ArrayList<>();

    SectionBuilder(String file, int line, SectionType type, String value) {
      this.file = file;
      this.line = line;
      this.type = type;
      String[] tokens = value.split("\\s+", -1);
      if (tokens.length == 0 || !NAME.matcher(tokens[0]).matches()) {
        throw new IllegalArgumentException(
            file + ":" + line + ": invalid section name '" + value + "'");
      }
      this.name = tokens[0];
      for (int i = 1; i < tokens.length; i++) {
        if (tokens[i].isEmpty()) {
          continue;
        }
        Matcher m = PARAM.matcher(tokens[i]);
        if (!m.matches() || type != SectionType.VARIANT) {
          throw new IllegalArgumentException(
              file + ":" + line + ": invalid section parameter '" + tokens[i] + "'");
        }
        params.put(m.group(1), m.group(2));
      }
    }

    void attribute(String key, String value, int lineNo) {
      if (!SECTION_DIRECTIVES.contains(key)) {
        throw new IllegalArgumentException(
            file + ":" + lineNo + ": unknown section directive @" + key);
      }
      if (key.equals("optional") && (type != SectionType.PROBE || value.isBlank())) {
        throw new IllegalArgumentException(
            file + ":" + lineNo + ": @optional needs a reason and is for probes only");
      }
      if (sql.stream().anyMatch(s -> !s.isBlank())) {
        throw new IllegalArgumentException(
            file + ":" + lineNo + ": @" + key + " after the SQL of " + name);
      }
      attributes.put(key, value);
    }

    void line(String text) {
      sql.add(text);
    }

    Section build() {
      String statement = String.join("\n", sql).strip();
      if (statement.endsWith(";")) {
        statement = statement.substring(0, statement.length() - 1).strip();
      }
      if (statement.isEmpty()) {
        throw new IllegalArgumentException(file + ":" + line + ": section " + name + " has no SQL");
      }
      if (SqlScript.split(statement).size() != 1) {
        throw new IllegalArgumentException(
            file + ":" + line + ": section " + name + " has several statements");
      }
      List<String> outputs = type == SectionType.PROBE ? topLevelAliases(statement) : List.of();
      return new Section(
          type,
          name,
          params,
          outputs,
          attributes.containsKey("account") ? account(attributes.get("account"), file) : null,
          attributes.containsKey("criterion") ? Criterion.parse(attributes.get("criterion")) : null,
          attributes.containsKey("expect") ? Criterion.parse(attributes.get("expect")) : null,
          settings(attributes.getOrDefault("settings", ""), file),
          attributes.get("optional"),
          statement);
    }
  }
}
