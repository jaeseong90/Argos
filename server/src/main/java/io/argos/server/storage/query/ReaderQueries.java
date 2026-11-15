/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import io.argos.server.config.ArgosProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The SQL templates of the query layer and their rendering (13 §6.3 as the SQL reference, the style
 * of {@code test/storage/queries}).
 *
 * <ul>
 *   <li>Templates are classpath resources {@code sql/<path>.sql}, named by {@code <path>} (e.g.
 *       {@code dashboard/app_series.raw}). All of them are read and checked ({@link
 *       ReaderQuery#parse}) when the bean is created, so a broken template fails the server start.
 *   <li>Rendering substitutes the identifiers only: {@code {db}} = {@code
 *       argos.clickhouse.database}, {@code {p}} = {@code argos.clickhouse.table-prefix}, {@code
 *       {rollup:<t>}} = the physical table of {@link RollupAliases}. Values stay {@code
 *       {name:Type}} parameters, bound by the executor; no request value is ever formatted into the
 *       SQL text.
 * </ul>
 */
@Component
public class ReaderQueries {

  /** The classpath directory of the templates. */
  public static final String ROOT = "sql";

  private static final Pattern SEGMENT_BOUND = Pattern.compile("\\{(from|to):");
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]*");
  private static final Pattern IDENTIFIER_PLACEHOLDER =
      Pattern.compile("\\{(db|p|rollup:[A-Za-z0-9_]+)\\}");

  private final Map<String, ReaderQuery> templates;
  private final String database;
  private final String prefix;
  private final UnaryOperator<String> rollupTable;

  /** The templates below {@value #ROOT} of the server's classpath. */
  @Autowired
  public ReaderQueries(ArgosProperties properties, RollupAliases aliases) {
    this(
        load(ROOT, ReaderQueries.class.getClassLoader()),
        properties.clickhouse().database(),
        properties.clickhouse().tablePrefix(),
        aliases::physical);
  }

  /**
   * Explicit templates (tests, other catalog roots).
   *
   * @param templates name to template
   * @param database the {@code {db}} identifier
   * @param prefix the {@code {p}} identifier
   * @param rollupTable the physical table of a logical rollup table ({@link
   *     RollupAliases#physical(String)})
   */
  public ReaderQueries(
      Map<String, ReaderQuery> templates,
      String database,
      String prefix,
      UnaryOperator<String> rollupTable) {
    this.templates = Collections.unmodifiableMap(new TreeMap<>(templates));
    this.database = requireIdentifier(database, "database");
    this.prefix = requireIdentifier(prefix == null ? "" : prefix, "table prefix");
    this.rollupTable = Objects.requireNonNull(rollupTable, "rollupTable");
    if (this.database.isEmpty()) {
      throw new IllegalArgumentException("The ClickHouse database name is empty");
    }
  }

  /**
   * Reads and checks every {@code <root>/**}{@code /*.sql} resource of {@code loader}.
   *
   * @throws IllegalArgumentException when a template breaks a rule of {@link ReaderQuery}
   */
  public static Map<String, ReaderQuery> load(String root, ClassLoader loader) {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(loader);
    Map<String, ReaderQuery> out = new TreeMap<>();
    Resource[] resources;
    try {
      resources = resolver.getResources("classpath*:" + root + "/**/*.sql");
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot list the SQL templates of " + root, e);
    }
    for (Resource r : resources) {
      String name = name(root, r);
      String text;
      try (InputStream in = r.getInputStream()) {
        text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read SQL template " + name, e);
      }
      ReaderQuery query = ReaderQuery.parse(name, text);
      if (out.putIfAbsent(name, query) != null) {
        throw new IllegalArgumentException("SQL template " + name + " exists twice");
      }
    }
    return out;
  }

  /** The template {@code name}; {@link IllegalArgumentException} when there is none. */
  public ReaderQuery get(String name) {
    ReaderQuery q = templates.get(name);
    if (q == null) {
      throw new IllegalArgumentException("No SQL template " + name);
    }
    return q;
  }

  /** A segmented query of an outer template and one fragment per level. */
  public SegmentedQuery segmented(String outer, Map<TableLevel, String> fragments) {
    EnumMap<TableLevel, ReaderQuery> parts = new EnumMap<>(TableLevel.class);
    fragments.forEach((level, name) -> parts.put(level, get(name)));
    return new SegmentedQuery(get(outer), parts);
  }

  /** Every template, by name. */
  public Map<String, ReaderQuery> templates() {
    return templates;
  }

  /** The SQL of {@code query} with its identifiers substituted. */
  public String render(ReaderQuery query) {
    if (query.segmentsPlaceholder()) {
      throw new IllegalArgumentException(
          "Template " + query.name() + " is an outer template: render it with its segments");
    }
    return identifiers(query.sql());
  }

  /**
   * The statement of a segmented query: one fragment per segment in a {@code UNION ALL} inside the
   * outer template, the segment bounds bound as {@code from_s<i>}, {@code to_s<i>}.
   *
   * @param params the shared parameters; a copy with the segment bounds is returned
   */
  public Rendered render(SegmentedQuery query, List<TimeSegment> segments, QueryParams params) {
    if (segments.isEmpty()) {
      throw new IllegalArgumentException("No segment for " + query.outer().name());
    }
    QueryParams bound = params.copy();
    List<String> parts = new ArrayList<>(segments.size());
    for (int i = 0; i < segments.size(); i++) {
      TimeSegment s = segments.get(i);
      ReaderQuery fragment = query.fragment(s.level());
      String suffix = "_s" + i;
      Matcher m = SEGMENT_BOUND.matcher(fragment.sql());
      String sql = m.replaceAll(r -> "{" + r.group(1) + suffix + ":");
      bindBound(bound, "from" + suffix, fragment.parameters().get("from"), s.from());
      bindBound(bound, "to" + suffix, fragment.parameters().get("to"), s.to());
      parts.add("SELECT * FROM (\n" + sql + "\n)");
    }
    String union = String.join("\nUNION ALL\n", parts);
    String sql = query.outer().sql().replace(ReaderQuery.SEGMENTS, union);
    return new Rendered(identifiers(sql), bound);
  }

  /** A rendered segmented statement and its parameters. */
  public record Rendered(String sql, QueryParams params) {}

  private static void bindBound(QueryParams params, String name, String type, Instant value) {
    if (type != null && type.startsWith("DateTime64")) {
      params.dateTime64(name, value);
    } else if (type != null && type.startsWith("DateTime")) {
      params.dateTime(name, value);
    } else {
      throw new IllegalArgumentException(
          "Segment bound " + name + " must be DateTime or DateTime64, not " + type);
    }
  }

  private String identifiers(String sql) {
    StringBuilder out = new StringBuilder(sql.length() + 32);
    Matcher m = IDENTIFIER_PLACEHOLDER.matcher(sql);
    while (m.find()) {
      String token = m.group(1);
      String value;
      if (token.equals("db")) {
        value = database;
      } else if (token.equals("p")) {
        value = prefix;
      } else {
        value = rollupTable.apply(token.substring("rollup:".length()));
        requireIdentifier(value, "rollup table");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static String requireIdentifier(String value, String what) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid " + what + " name");
    }
    return value;
  }

  private static String name(String root, Resource resource) {
    String path;
    try {
      URL url = resource.getURL();
      path = url.getPath();
    } catch (IOException e) {
      path = Objects.requireNonNullElse(resource.getFilename(), "unknown.sql");
    }
    path = path.replace('\\', '/');
    String marker = "/" + root + "/";
    int at = path.lastIndexOf(marker);
    String relative = at >= 0 ? path.substring(at + marker.length()) : resource.getFilename();
    if (relative == null) {
      relative = "unknown.sql";
    }
    return relative.endsWith(".sql") ? relative.substring(0, relative.length() - 4) : relative;
  }
}
