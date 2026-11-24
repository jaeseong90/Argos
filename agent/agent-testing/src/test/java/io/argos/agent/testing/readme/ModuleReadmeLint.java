/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.readme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lint of the instrumentation module READMEs (03 §3.1: the target library version range in the
 * module README, a test at each boundary version, a witness checked before the module is enabled;
 * proposed test ID T-BLD-017).
 *
 * <p>Every module that {@code settings.gradle.kts} lists in {@code instrumentationModules}, except
 * {@link #EXCLUDED}, needs {@code agent/agent-instrumentation/<module>/README.md} with:
 *
 * <ul>
 *   <li>a level-2 heading that starts with {@value #SECTION};
 *   <li>inside it the level-3 heading {@value #SUBSECTION} with a table whose header has the
 *       columns {@code Library}, {@code Minimum verified}, {@code Maximum verified} and {@code
 *       Boundary tests}: at least one row, both versions in version form ({@code 4.5.14}, {@code
 *       JDK 21}, {@code 12.10.2.jre11}), minimum not above maximum, and every boundary cell naming
 *       at least one existing test class;
 *   <li>a bullet {@code - Witness...} that names, in backticks, every class the module's {@code
 *       witness()} checks (read from the module source), or starts with {@code - Witness: none}
 *       when the module keeps the default witness (every class loader);
 *   <li>at least one Gradle task of the module itself ({@code
 *       :agent:agent-instrumentation:<m>:<task>}); every task path named in the subsection must
 *       exist.
 * </ul>
 */
final class ModuleReadmeLint {
  static final String SECTION = "Supported library versions";
  static final String SUBSECTION = "Version range and witness";
  static final String INSTRUMENTATION = ":agent:agent-instrumentation";

  /** Modules of {@code instrumentationModules} that are not instrumentation, with the reason. */
  static final Map<String, String> EXCLUDED =
      Map.of(
          "api",
          "the framework every module compiles against (InstrumentationModule, Witness,"
              + " SafeMatchers): it instruments no library, so it has no version range or witness");

  /** Test tasks every Java project of the build has (argos.java-common, argos.integration-test). */
  static final Set<String> STANDARD_TASKS = Set.of("test", "check", "integrationTest", "archTest");

  static final List<String> COLUMNS =
      List.of("Library", "Minimum verified", "Maximum verified", "Boundary tests");

  private static final Pattern VERSION =
      Pattern.compile("JDK (\\d+(?:\\.\\d+)*)|(\\d+(?:\\.\\d+)+)(?:[.-][0-9A-Za-z]{2,})*");
  private static final Pattern TEST_CLASS =
      Pattern.compile("`(?:[a-z][a-z0-9]*\\.)*([A-Z][A-Za-z0-9]*Test)`");
  private static final Pattern TASK_PATH =
      Pattern.compile("(:agent(?::[a-z0-9-]+)+):([a-z][A-Za-z0-9]*)");
  private static final Pattern WITNESS_METHOD =
      Pattern.compile("ElementMatcher<ClassLoader>\\s+witness\\s*\\(\\s*\\)\\s*\\{");
  private static final Pattern NEW_WITNESS = Pattern.compile("new\\s+([A-Z][A-Za-z0-9]*)\\s*\\(");
  private static final Pattern IDENTIFIER =
      Pattern.compile("(?:([A-Z][A-Za-z0-9]*)\\.)?([A-Z][A-Z0-9_]*)");
  private static final String HAS_CLASSES_NAMED = "hasClassesNamed(";

  private ModuleReadmeLint() {}

  /** What the lint needs to know about the repository beyond the README text. */
  interface Repository {
    /** Whether a test class of this simple name exists in the agent sources. */
    boolean testClassExists(String simpleName);

    /** Whether the Gradle project exists and has the task. */
    boolean taskExists(String projectPath, String task);
  }

  /**
   * The classes a module's {@code witness()} checks.
   *
   * @param declared whether the module overrides {@code witness()} (otherwise every class loader)
   * @param classNames the binary class names it checks, in source order
   * @param unresolved argument expressions the lint could not evaluate
   */
  record Witness(boolean declared, Set<String> classNames, List<String> unresolved) {
    static Witness none() {
      return new Witness(false, Set.of(), List.of());
    }
  }

  /**
   * The module names of {@code val instrumentationModules = listOf(...)} in settings.gradle.kts.
   */
  static List<String> instrumentationModules(String settings) {
    int start = settings.indexOf("val instrumentationModules");
    if (start < 0) {
      throw new IllegalArgumentException("settings.gradle.kts has no instrumentationModules list");
    }
    int open = settings.indexOf("listOf(", start);
    int close = matching(settings, open + "listOf".length(), '(', ')');
    List<String> modules = new ArrayList<>();
    for (String line : settings.substring(open, close).split("\n", -1)) {
      String code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
      Matcher m = Pattern.compile("\"([a-z0-9-]+)\"").matcher(code);
      while (m.find()) {
        modules.add(m.group(1));
      }
    }
    return modules;
  }

  /** The repository root: the nearest ancestor of the working directory with the Argos settings. */
  static Path repositoryRoot() throws IOException {
    String override = System.getProperty("argos.repoRoot");
    if (override != null && !override.isBlank()) {
      return Path.of(override).toAbsolutePath().normalize();
    }
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path settings = dir.resolve("settings.gradle.kts");
      if (Files.isRegularFile(settings)
          && Files.readString(settings, StandardCharsets.UTF_8)
              .contains("rootProject.name = \"argos\"")) {
        return dir;
      }
    }
    throw new IllegalStateException(
        "no Argos repository root above " + Path.of("").toAbsolutePath());
  }

  /** Lints every instrumentation module of the repository; the result lists the linted modules. */
  static Result lint(Path root) throws IOException {
    String settings = Files.readString(root.resolve("settings.gradle.kts"), StandardCharsets.UTF_8);
    Repository repository = new FileRepository(root);
    List<String> linted = new ArrayList<>();
    List<String> violations = new ArrayList<>();
    for (String module : instrumentationModules(settings)) {
      if (EXCLUDED.containsKey(module)) {
        continue;
      }
      linted.add(module);
      Path dir = root.resolve("agent/agent-instrumentation").resolve(module);
      violations.addAll(lintModule(module, dir, repository));
    }
    return new Result(linted, violations);
  }

  /** The modules the lint checked and every violation found ({@code <module>: <message>}). */
  record Result(List<String> linted, List<String> violations) {}

  /** Lints one module directory: its README against its main sources. */
  static List<String> lintModule(String module, Path dir, Repository repository)
      throws IOException {
    Path readme = dir.resolve("README.md");
    if (!Files.isRegularFile(readme)) {
      return List.of(module + ": README.md is missing");
    }
    Map<String, String> sources = new LinkedHashMap<>();
    Path main = dir.resolve("src/main/java");
    if (Files.isDirectory(main)) {
      try (Stream<Path> files = Files.walk(main)) {
        for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
          String name = file.getFileName().toString();
          sources.put(
              name.substring(0, name.length() - ".java".length()),
              Files.readString(file, StandardCharsets.UTF_8));
        }
      }
    }
    return lintReadme(
        module, Files.readString(readme, StandardCharsets.UTF_8), witnessOf(sources), repository);
  }

  /** Lints a README text of {@code module} against the witness its source declares. */
  static List<String> lintReadme(
      String module, String readme, Witness witness, Repository repository) {
    List<String> violations = new ArrayList<>();
    List<String> lines = List.of(readme.replace("\r\n", "\n").split("\n", -1));
    List<String> section = block(lines, "## ", SECTION, true);
    if (section == null) {
      violations.add(module + ": no '## " + SECTION + "' section");
      return violations;
    }
    List<String> sub = block(section, "### ", SUBSECTION, false);
    if (sub == null) {
      violations.add(module + ": no '### " + SUBSECTION + "' subsection in '## " + SECTION + "'");
      return violations;
    }
    lintTable(module, sub, repository, violations);
    lintWitness(module, sub, witness, violations);
    lintTasks(module, String.join("\n", sub), repository, violations);
    return violations;
  }

  private static void lintTable(
      String module, List<String> sub, Repository repository, List<String> violations) {
    int header = -1;
    for (int i = 0; i < sub.size(); i++) {
      if (sub.get(i).startsWith("|")) {
        header = i;
        break;
      }
    }
    List<String> headerCells = header < 0 ? List.of() : cells(sub.get(header));
    Map<String, Integer> index = new HashMap<>();
    for (String column : COLUMNS) {
      index.put(column, headerCells.indexOf(column));
    }
    if (header < 0 || index.containsValue(-1)) {
      violations.add(module + ": no version table with the columns " + String.join(", ", COLUMNS));
      return;
    }
    List<List<String>> rows = new ArrayList<>();
    for (int i = header + 2; i < sub.size() && sub.get(i).startsWith("|"); i++) {
      rows.add(cells(sub.get(i)));
    }
    if (rows.isEmpty()) {
      violations.add(module + ": version table has no rows");
      return;
    }
    for (List<String> row : rows) {
      String library = cell(row, index.get("Library"));
      String min = cell(row, index.get("Minimum verified"));
      String max = cell(row, index.get("Maximum verified"));
      String prefix = module + ": row '" + library + "': ";
      int[] minVersion = version(min);
      int[] maxVersion = version(max);
      if (minVersion == null) {
        violations.add(prefix + "minimum verified '" + min + "' is not a version");
      }
      if (maxVersion == null) {
        violations.add(prefix + "maximum verified '" + max + "' is not a version");
      }
      if (minVersion != null && maxVersion != null && compare(minVersion, maxVersion) > 0) {
        violations.add(prefix + "minimum verified " + min + " is above maximum verified " + max);
      }
      String tests = cell(row, index.get("Boundary tests"));
      Matcher m = TEST_CLASS.matcher(tests);
      boolean any = false;
      while (m.find()) {
        any = true;
        if (!repository.testClassExists(m.group(1))) {
          violations.add(prefix + "unknown test class '" + m.group(1) + "'");
        }
      }
      if (!any) {
        violations.add(prefix + "boundary tests name no test class");
      }
    }
  }

  private static void lintWitness(
      String module, List<String> sub, Witness witness, List<String> violations) {
    StringBuilder text = null;
    for (String line : sub) {
      if (text == null) {
        if (line.startsWith("- Witness")) {
          text = new StringBuilder(line);
        }
      } else if (line.startsWith("  ") && !line.isBlank()) {
        text.append(' ').append(line.strip());
      } else {
        break;
      }
    }
    if (text == null) {
      violations.add(module + ": no '- Witness' line");
      return;
    }
    boolean none = text.toString().startsWith("- Witness: none");
    if (!witness.declared()) {
      if (!none) {
        violations.add(
            module
                + ": the module keeps the default witness (every class loader), so the"
                + " witness line must start with '- Witness: none'");
      }
      return;
    }
    if (none) {
      violations.add(module + ": witness line says none but the module's witness() checks classes");
      return;
    }
    for (String unresolved : witness.unresolved()) {
      violations.add(module + ": cannot resolve witness argument '" + unresolved + "'");
    }
    for (String name : witness.classNames()) {
      if (!text.toString().contains("`" + name + "`")) {
        violations.add(module + ": witness line does not name `" + name + "`");
      }
    }
  }

  private static void lintTasks(
      String module, String text, Repository repository, List<String> violations) {
    String own = INSTRUMENTATION + ":" + module;
    boolean ownTask = false;
    Set<String> seen = new HashSet<>();
    Matcher m = TASK_PATH.matcher(text);
    while (m.find()) {
      String path = m.group(1) + ":" + m.group(2);
      if (!seen.add(path)) {
        continue;
      }
      if (!repository.taskExists(m.group(1), m.group(2))) {
        violations.add(module + ": unknown Gradle task '" + path + "'");
      } else if (m.group(1).equals(own)) {
        ownTask = true;
      }
    }
    if (!ownTask) {
      violations.add(module + ": the subsection names no task of " + own);
    }
  }

  /**
   * The lines below the heading {@code level + title} (prefix match when {@code prefix}) up to the
   * next heading of the same or a higher level, outside code fences; null when there is none.
   */
  private static List<String> block(
      List<String> lines, String level, String title, boolean prefix) {
    boolean fence = false;
    int start = -1;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.startsWith("```")) {
        fence = !fence;
        continue;
      }
      if (fence || !line.startsWith("#")) {
        continue;
      }
      if (start >= 0) {
        String hashes =
            line.substring(0, line.indexOf(' ') < 0 ? line.length() : line.indexOf(' '));
        if (hashes.chars().allMatch(c -> c == '#') && hashes.length() <= level.length() - 1) {
          return lines.subList(start, i);
        }
      } else if (line.startsWith(level)) {
        String heading = line.substring(level.length()).strip();
        if (prefix ? heading.startsWith(title) : heading.equals(title)) {
          start = i + 1;
        }
      }
    }
    return start < 0 ? null : lines.subList(start, lines.size());
  }

  private static List<String> cells(String row) {
    String trimmed = row.strip();
    if (trimmed.startsWith("|")) {
      trimmed = trimmed.substring(1);
    }
    if (trimmed.endsWith("|")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    List<String> cells = new ArrayList<>();
    for (String cell : trimmed.split("\\|", -1)) {
      cells.add(cell.strip());
    }
    return cells;
  }

  private static String cell(List<String> row, int index) {
    return index < row.size() ? row.get(index) : "";
  }

  /** The numeric components of a version cell ({@code `4.5.14`}, {@code JDK 21}), or null. */
  static int[] version(String cell) {
    String text = cell.replace("`", "").strip();
    Matcher m = VERSION.matcher(text);
    if (!m.matches()) {
      return null;
    }
    String[] parts = (m.group(1) != null ? m.group(1) : m.group(2)).split("\\.", -1);
    int[] numbers = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      try {
        numbers[i] = Integer.parseInt(parts[i]);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return numbers;
  }

  static int compare(int[] a, int[] b) {
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int x = i < a.length ? a[i] : 0;
      int y = i < b.length ? b[i] : 0;
      if (x != y) {
        return Integer.compare(x, y);
      }
    }
    return 0;
  }

  /**
   * The witness of a module from its main sources (simple class name to source text): the {@code
   * hasClassesNamed(...)} arguments of the {@code InstrumentationModule}'s {@code witness()}, or of
   * the class it returns with {@code new X()}; string constants of the same sources are resolved.
   */
  static Witness witnessOf(Map<String, String> sources) {
    String moduleClass = null;
    for (Map.Entry<String, String> source : sources.entrySet()) {
      if (source.getValue().contains("implements InstrumentationModule")) {
        moduleClass = source.getKey();
        break;
      }
    }
    if (moduleClass == null) {
      return Witness.none();
    }
    String text = sources.get(moduleClass);
    Matcher method = WITNESS_METHOD.matcher(text);
    if (!method.find()) {
      return Witness.none();
    }
    int open = method.end() - 1;
    String body = text.substring(open, matching(text, open, '{', '}'));
    String owner = moduleClass;
    if (!body.contains(HAS_CLASSES_NAMED)) {
      Matcher delegate = NEW_WITNESS.matcher(body);
      if (delegate.find() && sources.containsKey(delegate.group(1))) {
        owner = delegate.group(1);
        body = sources.get(owner);
      }
    }
    Set<String> names = new LinkedHashSet<>();
    List<String> unresolved = new ArrayList<>();
    int from = 0;
    while ((from = body.indexOf(HAS_CLASSES_NAMED, from)) >= 0) {
      int argsOpen = from + HAS_CLASSES_NAMED.length() - 1;
      int argsClose = matching(body, argsOpen, '(', ')');
      for (String argument : split(body.substring(argsOpen + 1, argsClose - 1), ',')) {
        String value = evaluate(argument, owner, sources, 0);
        if (value == null) {
          unresolved.add(argument.strip());
        } else {
          names.add(value);
        }
      }
      from = argsClose;
    }
    if (names.isEmpty() && unresolved.isEmpty()) {
      unresolved.add("witness() of " + moduleClass);
    }
    return new Witness(true, names, unresolved);
  }

  /** Evaluates a string expression of literals and constants joined by {@code +}, or null. */
  private static String evaluate(
      String expression, String owner, Map<String, String> sources, int depth) {
    if (depth > 5) {
      return null;
    }
    StringBuilder value = new StringBuilder();
    for (String term : split(expression, '+')) {
      String t = term.strip();
      if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
        value.append(t, 1, t.length() - 1);
        continue;
      }
      Matcher id = IDENTIFIER.matcher(t);
      if (!id.matches()) {
        return null;
      }
      String cls = id.group(1) == null ? owner : id.group(1);
      String source = sources.get(cls);
      if (source == null) {
        return null;
      }
      Matcher constant =
          Pattern.compile(
                  "static\\s+final\\s+String\\s+"
                      + Pattern.quote(id.group(2))
                      + "\\s*=\\s*([^;]+);")
              .matcher(source);
      if (!constant.find()) {
        return null;
      }
      String resolved = evaluate(constant.group(1), cls, sources, depth + 1);
      if (resolved == null) {
        return null;
      }
      value.append(resolved);
    }
    return value.toString();
  }

  /** Splits on {@code separator} outside string literals and parentheses. */
  private static List<String> split(String text, char separator) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    boolean string = false;
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
        string = !string;
      } else if (!string && c == '(') {
        depth++;
      } else if (!string && c == ')') {
        depth--;
      } else if (!string && depth == 0 && c == separator) {
        parts.add(text.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(text.substring(start));
    parts.removeIf(String::isBlank);
    return parts;
  }

  /** The index after the bracket that closes the one at {@code open}. */
  private static int matching(String text, int open, char opening, char closing) {
    if (open < 0 || open >= text.length() || text.charAt(open) != opening) {
      throw new IllegalArgumentException("no '" + opening + "' at " + open);
    }
    int depth = 0;
    boolean string = false;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' && text.charAt(i - 1) != '\\') {
        string = !string;
      } else if (!string && c == opening) {
        depth++;
      } else if (!string && c == closing && --depth == 0) {
        return i + 1;
      }
    }
    throw new IllegalArgumentException("unbalanced '" + opening + "' at " + open);
  }

  /** The repository on disk: test classes of the agent sources, tasks from the build scripts. */
  static final class FileRepository implements Repository {
    private final Path root;
    private Set<String> testClasses;

    FileRepository(Path root) {
      this.root = root;
    }

    @Override
    public boolean testClassExists(String simpleName) {
      if (testClasses == null) {
        testClasses = new HashSet<>();
        try (Stream<Path> files = Files.walk(root.resolve("agent"))) {
          files
              .filter(f -> f.toString().endsWith(".java"))
              .filter(f -> !isGenerated(root.relativize(f)))
              .forEach(f -> testClasses.add(f.getFileName().toString().replace(".java", "")));
        } catch (IOException e) {
          throw new IllegalStateException("cannot list the agent sources", e);
        }
      }
      return testClasses.contains(simpleName);
    }

    private static boolean isGenerated(Path relative) {
      for (Path part : relative) {
        String name = part.toString();
        if (name.equals("build") || name.equals(".gradle") || name.equals("node_modules")) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean taskExists(String projectPath, String task) {
      Path build =
          root.resolve(projectPath.substring(1).replace(':', '/')).resolve("build.gradle.kts");
      if (!Files.isRegularFile(build)) {
        return false;
      }
      if (STANDARD_TASKS.contains(task)) {
        return true;
      }
      String script;
      try {
        script = Files.readString(build, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new IllegalStateException("cannot read " + build, e);
      }
      if (script.contains("\"" + task + "\"")) {
        return true;
      }
      // Tasks registered per JDK from a template, such as "testJdk$jdk" for testJdk11.
      String stem = task.replaceAll("[0-9]+$", "");
      return !stem.equals(task) && script.contains("\"" + stem + "$");
    }
  }
}
