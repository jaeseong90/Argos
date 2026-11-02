/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.attach;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * WO-02 agent attach tests: {@code example.trivial.TrivialApp} with {@code -javaagent} on each JDK
 * of {@code -Pagent.attach.jdks} (default 8 and 21).
 *
 * <ul>
 *   <li>T-BLD-006: no temporary file: the child's {@code java.io.tmpdir} and its system temp
 *       directory ({@code TMP}, {@code TEMP}, {@code TMPDIR}) point to a watched, empty directory
 *       that must see no event at all during the run ({@code -XX:-UsePerfData} keeps the JVM's own
 *       hsperfdata out of it).
 *   <li>T-AG-060 (PoC): stdout is exactly the application's line; stderr is empty, except for the
 *       JVM's CDS notice caused by appending the agent jar to the bootstrap search (10 §14 "CDS"),
 *       which a run with {@code -Xshare:off} shows to be the JVM's and not the agent's.
 *   <li>10 §4.2 isolation: bootstrap API visible to the application, agent core, instrumentation
 *       API, shaded ByteBuddy and the module index invisible.
 *   <li>Probe jar: transactions with SQL and ASYNC steps in JSONL, a helper defined in the
 *       application class loader, {@code ThreadPoolExecutor} (java.base) retransformed and its
 *       advice reaching the bootstrap API (JPMS read edge, P-6 mechanism), the transformed-class
 *       list.
 *   <li>Kill switches (T-AG-043), advice fault injection (T-AG-040 PoC), ByteBuddy's Unsafe code
 *       never loaded (F-A35, ADR-0003), JDK 23+ {@code --sun-misc-unsafe-memory-access=deny}.
 * </ul>
 */
@Tag("req:F-A31")
@Tag("req:F-A35")
class AgentAttachTest {
  static final String DONE = "trivial-app done";
  static final String MAIN = "example.trivial.TrivialApp";

  /** The JVM's CDS notice when a jar is appended to the bootstrap class path (not agent output). */
  static final Pattern CDS_NOTICE =
      Pattern.compile(
          "(OpenJDK|Java HotSpot\\(TM\\)) 64-Bit Server VM warning: Sharing is only supported for"
              + " boot loader classes because bootstrap classpath has been appended");

  static final List<String> UNSAFE_PATHS =
      List.of(
          "ClassInjector$UsingUnsafe",
          "ClassInjector$UsingReflection",
          "NexusAccessor",
          "LambdaMetafactoryFactory$Loader");

  @TempDir Path work;

  /** Result of one child JVM. */
  record Run(
      int jdk,
      Path dir,
      int exitCode,
      String stdout,
      String stderr,
      List<String> tempEvents,
      List<String> systemTempAdded) {

    Path logs() {
      return dir.resolve("logs");
    }

    Path out() {
      return dir.resolve("out");
    }

    Properties results() throws IOException {
      Properties properties = new Properties();
      try (Reader reader =
          Files.newBufferedReader(
              out().resolve("results.properties"), StandardCharsets.ISO_8859_1)) {
        properties.load(reader);
      }
      return properties;
    }

    String log() throws IOException {
      return Files.readString(logs().resolve("argos-agent.log"), StandardCharsets.UTF_8);
    }

    List<String> transactions() throws IOException {
      Path file = out().resolve("tx.jsonl");
      return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
    }

    List<String> transformed() throws IOException {
      return Files.readAllLines(out().resolve("transformed.txt"), StandardCharsets.UTF_8);
    }

    List<String> stderrLines() {
      return stderr.lines().filter(line -> !line.isBlank()).toList();
    }

    String describe() {
      return "JDK " + jdk + " in " + dir + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr;
    }
  }

  static List<Integer> jdks() {
    return Arrays.stream(System.getProperty("argos.test.jdks", "8,21").split(","))
        .map(String::trim)
        .map(Integer::parseInt)
        .toList();
  }

  static Path java(int jdk) {
    return Path.of(required("argos.test.java" + jdk));
  }

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  @TestFactory
  Stream<DynamicNode> attachOnEveryJdk() {
    return jdks().stream()
        .map(
            jdk ->
                DynamicContainer.dynamicContainer(
                    "JDK " + jdk,
                    Stream.of(
                        DynamicTest.dynamicTest(
                            "T-BLD-006/T-AG-060 release jar: no temp file, no output, isolation",
                            () -> releaseJar(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-060 -Xshare:off: stderr is empty (the CDS notice is the JVM's)",
                            () -> noCdsNoStderr(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-010 (PoC) probe jar: transactions, steps, helper, JDK class",
                            () -> probeJar(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-070 (PoC) JDK classes loaded before premain are retransformed"
                                + " and their advice reaches the bootstrap API",
                            () -> preloadedJdkClasses(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-043 kill switch 2nd stage (argos.agent.disabled) installs nothing",
                            () -> killSwitchSecondStage(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-043 kill switch 1st stage (argos.enabled=false) records nothing",
                            () -> killSwitchFirstStage(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-040 (PoC) advice fault injection: application unaffected",
                            () -> adviceFault(jdk)),
                        DynamicTest.dynamicTest(
                            "F-A35 ByteBuddy Unsafe code paths are never loaded (-verbose:class)",
                            () -> unsafePathsNotLoaded(jdk)))));
  }

  void releaseJar(int jdk) throws Exception {
    Run run = launch(jdk, "release", jar("releaseAgentJar"), List.of(), true);
    assertCleanRun(run);
    assertIsolation(run.results(), "bootstrap");
    assertThat(run.log())
        .contains("Argos agent ")
        .doesNotContain(" ERROR ")
        .doesNotContain(" WARN ");
    // The release modules (servlet, spring-webmvc, ...) are loaded; the servlet and Spring MVC
    // witnesses do not match the trivial app: no application class is transformed and no
    // transaction is recorded. (JDK-class modules such as executor may transform JDK classes.)
    assertThat(modules(run.log())).contains("servlet", "spring-webmvc").noneMatch(isProbe());
    assertThat(run.transformed()).first().isEqualTo(TRANSFORM_LIST_HEADER);
    assertThat(run.transformed())
        .noneMatch(line -> line.startsWith("example.trivial."))
        .noneMatch(line -> line.contains(" servlet ") || line.contains(" spring-webmvc "));
    assertThat(run.transactions()).isEmpty();
    reportStartup(run);
  }

  void noCdsNoStderr(int jdk) throws Exception {
    Run run = launch(jdk, "xshare-off", jar("probeAgentJar"), List.of("-Xshare:off"), false);
    assertThat(run.exitCode()).as(run.describe()).isZero();
    assertThat(run.stdout().strip()).as(run.describe()).isEqualTo(DONE);
    assertThat(run.stderr()).as(run.describe()).isEmpty();
    assertThat(run.tempEvents()).as(run.describe()).isEmpty();
  }

  void probeJar(int jdk) throws Exception {
    Run run = launch(jdk, "probe", jar("probeAgentJar"), List.of(), true);
    assertCleanRun(run);
    Properties results = run.results();
    assertIsolation(results, "bootstrap");
    assertThat(modules(run.log())).contains("test-probe", "test-probe-jdk", "servlet");
    assertThat(run.log())
        .doesNotContain(" ERROR ")
        .doesNotContain(" WARN ")
        .doesNotContain("IllegalAccessError");
    List<String> transactions = run.transactions();
    assertThat(transactions).as(run.describe()).hasSize(3);
    for (int i = 0; i < 3; i++) {
      String tx = transactions.get(i);
      assertThat(tx)
          .contains("\"service\":\"GET /probe/orders-" + i + "\"")
          .contains("\"kind\":\"HTTP\"")
          .contains("\"status\":200")
          .contains("\"error\":false")
          .contains("\"type\":\"SQL\",\"name_id\"")
          .contains("\"name\":\"select * from orders where id = ?\"")
          // ThreadPoolExecutor.execute in java.base reached the bootstrap API (read edge, P-6).
          .contains("\"type\":\"ASYNC\"")
          .contains("\"name\":\"java.util.concurrent.ThreadPoolExecutor#execute\"");
      // The helper was defined in the application class loader, not in the agent's.
      Matcher detail =
          Pattern.compile("\"name\":\"probe helper loader\".*?\"detail\":\"([^\"]*)\"").matcher(tx);
      assertThat(detail.find()).as(tx).isTrue();
      assertThat(detail.group(1)).contains("AppClassLoader").doesNotContain("Argos");
    }
    assertThat(run.transformed()).first().asString().startsWith("# Argos transformed classes");
    assertThat(run.transformed())
        .contains(
            "example.trivial.TrivialApp$Handler test-probe app",
            "example.trivial.TrivialApp$Store test-probe app")
        .anySatisfy(
            // The release executor module transforms the same JDK class: modules are listed
            // together ("executor,test-probe-jdk").
            line ->
                assertThat(line)
                    .matches(
                        "java\\.util\\.concurrent\\.ThreadPoolExecutor (\\S+,)?test-probe-jdk(,\\S+)?"
                            + " bootstrap( retransformed)?"));
    reportStartup(run);
  }

  /**
   * T-AG-070 (PoC, P-6): another Java agent before Argos loads {@code ThreadPoolExecutor} and
   * {@code sun.net.www.protocol.http.HttpURLConnection}, so both are defined in java.base before
   * the Argos premain. Argos retransforms them (JDK allow-list, 10 §4.3 P6) and adds the read edge
   * java.base to its bootstrap module; the executor advice on the retransformed {@code
   * ThreadPoolExecutor} then propagates the probe's transaction to the pool thread ({@code
   * async.step=true}: an ASYNC step named after the task class) and the probe's own advice records
   * its step. No {@code IllegalAccessError}, no WARN.
   */
  @Tag("req:F-A31")
  void preloadedJdkClasses(int jdk) throws Exception {
    Run run =
        launch(
            jdk,
            "preload",
            jar("probeAgentJar"),
            List.of("-javaagent:" + jar("preloadAgentJar"), "-Dargos.async.step=true"),
            true);
    assertCleanRun(run);
    assertThat(run.log())
        .doesNotContain(" ERROR ")
        .doesNotContain(" WARN ")
        .doesNotContain("IllegalAccessError")
        .doesNotContain("advice error");
    assertThat(run.transformed())
        .anySatisfy(
            line ->
                assertThat(line)
                    .startsWith("java.util.concurrent.ThreadPoolExecutor ")
                    .contains("executor")
                    .contains("test-probe-jdk")
                    .endsWith(" bootstrap retransformed"))
        .anySatisfy(
            line ->
                assertThat(line)
                    .isEqualTo(
                        "sun.net.www.protocol.http.HttpURLConnection httpclient-jdk bootstrap"
                            + " retransformed"));
    List<String> transactions = run.transactions();
    assertThat(transactions).as(run.describe()).hasSize(3);
    for (String tx : transactions) {
      assertThat(tx)
          .contains("\"name\":\"java.util.concurrent.ThreadPoolExecutor#execute\"")
          .contains("\"type\":\"ASYNC\",\"name_id\"")
          .contains("\"name\":\"example.trivial.TrivialApp$Handler$1\"");
    }
    reportStartup(run);
  }

  void killSwitchSecondStage(int jdk) throws Exception {
    Run run =
        launch(
            jdk,
            "agent-disabled",
            jar("probeAgentJar"),
            List.of("-Dargos.agent.disabled=true"),
            true);
    assertThat(run.exitCode()).as(run.describe()).isZero();
    assertThat(run.stdout().strip()).isEqualTo(DONE);
    assertThat(run.stderr()).as(run.describe()).isEmpty();
    assertThat(run.tempEvents()).isEmpty();
    // Nothing installed: no log directory, no exporter file, no transformed-class list, and the
    // bootstrap classes are only reachable from the jar on the class path (not appended).
    assertThat(run.logs()).doesNotExist();
    assertThat(run.out().resolve("tx.jsonl")).doesNotExist();
    assertThat(run.out().resolve("transformed.txt")).doesNotExist();
    Properties results = run.results();
    assertThat(results.getProperty("bootstrapLoader")).isEqualTo("app");
    assertThat(results.getProperty("coreLoader")).isEqualTo("invisible");
    assertThat(results.getProperty("agentThreads")).isEmpty();
    assertThat(results.getProperty("handled")).isEqualTo("3");
  }

  void killSwitchFirstStage(int jdk) throws Exception {
    Run run = launch(jdk, "disabled", jar("probeAgentJar"), List.of("-Dargos.enabled=false"), true);
    assertCleanRun(run);
    assertThat(run.log()).contains("enabled=false");
    // Transformations stay installed (the switch can be turned back on), advice returns at once.
    assertThat(run.transformed()).contains("example.trivial.TrivialApp$Handler test-probe app");
    assertThat(run.transactions()).isEmpty();
    assertThat(run.results().getProperty("handled")).isEqualTo("3");
  }

  void adviceFault(int jdk) throws Exception {
    Run run =
        launch(
            jdk,
            "fault",
            jar("probeAgentJar"),
            List.of("-Dargos.test.faults=advice-throw:test-probe"),
            true);
    assertThat(run.exitCode()).as(run.describe()).isZero();
    assertThat(run.stdout().strip()).isEqualTo(DONE);
    assertThat(run.tempEvents()).isEmpty();
    Properties results = run.results();
    assertThat(results.getProperty("handled")).isEqualTo("3");
    assertThat(results.getProperty("asyncRuns")).isEqualTo("3");
    assertThat(run.transactions()).isEmpty();
    assertThat(run.log())
        .contains("argos.test.faults: advice of module test-probe throws")
        .contains("advice error in module 60");
  }

  void unsafePathsNotLoaded(int jdk) throws Exception {
    List<String> options = new ArrayList<>(List.of("-verbose:class"));
    if (jdk >= 23) {
      options.add("--sun-misc-unsafe-memory-access=deny");
    }
    Run run = launch(jdk, "verbose-class", jar("probeAgentJar"), options, true);
    assertThat(run.exitCode()).as(run.describe()).isZero();
    List<String> loaded =
        run.stdout().lines().filter(line -> line.contains("io.argos.agent.shaded.")).toList();
    assertThat(loaded).as("shaded classes loaded").isNotEmpty();
    for (String path : UNSAFE_PATHS) {
      assertThat(loaded).as(path).noneMatch(line -> line.contains(path));
    }
    assertThat(run.stderr()).doesNotContain("sun.misc.Unsafe").doesNotContain("Unsafe::");
    assertThat(run.log()).doesNotContain("NoClassDefFoundError").doesNotContain(" ERROR ");
    assertThat(run.transactions()).hasSize(3);
  }

  void assertCleanRun(Run run) {
    assertThat(run.exitCode()).as(run.describe()).isZero();
    assertThat(run.stdout().strip()).as(run.describe()).isEqualTo(DONE);
    assertThat(run.stdout().lines().count()).as(run.describe()).isEqualTo(1);
    assertThat(run.stderrLines())
        .as(run.describe())
        .allSatisfy(line -> assertThat(line).matches(CDS_NOTICE));
    assertThat(run.tempEvents()).as("events in the watched temp directory").isEmpty();
    assertThat(run.systemTempAdded())
        .as("agent-like entries added to the system temp dir")
        .isEmpty();
    try (Stream<Path> files = Files.list(run.dir().resolve("agent"))) {
      assertThat(files.map(p -> p.getFileName().toString()).toList())
          .as("nothing extracted next to the agent jar")
          .containsExactly("argos-agent.jar");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static void assertIsolation(Properties results, String bootstrapLoader) {
    assertThat(results.getProperty("bootstrapLoader")).isEqualTo(bootstrapLoader);
    assertThat(results.getProperty("coreLoader")).isEqualTo("invisible");
    assertThat(results.getProperty("apiLoader")).isEqualTo("invisible");
    assertThat(results.getProperty("shadedLoader")).isEqualTo("invisible");
    assertThat(results.getProperty("byteBuddyLoader")).isEqualTo("invisible");
    // Package-private in the bootstrap package: visible as a class, not instantiable.
    assertThat(results.getProperty("agentClassLoaderLoader")).isEqualTo("bootstrap");
    assertThat(results.getProperty("coreClassResource")).isEqualTo("false");
    assertThat(results.getProperty("moduleServiceFile")).isEqualTo("false");
    assertThat(results.getProperty("handled")).isEqualTo("3");
    assertThat(results.getProperty("asyncRuns")).isEqualTo("3");
    assertThat(results.getProperty("agentThreads")).contains("argos-logger");
  }

  static final String TRANSFORM_LIST_HEADER =
      "# Argos transformed classes: <class> <modules> <loader> [retransformed]";

  /** The module ids of the agent's start banner ({@code modules=[a,b]}). */
  static List<String> modules(String log) {
    Matcher matcher = Pattern.compile("modules=\\[([^\\]]*)\\]").matcher(log);
    assertThat(matcher.find()).as("start banner with modules=[...] in%n%s", log).isTrue();
    return matcher.group(1).isEmpty() ? List.of() : List.of(matcher.group(1).split(",", -1));
  }

  static java.util.function.Predicate<String> isProbe() {
    return module -> module.startsWith("test-probe");
  }

  static void reportStartup(Run run) throws IOException {
    Matcher started = Pattern.compile("started in ([0-9.]+) ms").matcher(run.log());
    if (started.find()) {
      System.out.println(
          "[attach] JDK "
              + run.jdk()
              + " "
              + run.dir().getFileName()
              + ": agent start "
              + started.group(1)
              + " ms");
    }
  }

  static Path jar(String property) {
    return Path.of(required("argos.test." + property));
  }

  Run launch(int jdk, String name, Path agentJar, List<String> options, boolean verification)
      throws Exception {
    Path dir = work.resolve("jdk" + jdk + "-" + name);
    Path tmp = Files.createDirectories(dir.resolve("tmp"));
    Path agent = Files.createDirectories(dir.resolve("agent")).resolve("argos-agent.jar");
    Files.copy(agentJar, agent);
    Files.createDirectories(dir.resolve("out"));

    List<String> command = new ArrayList<>();
    command.add(java(jdk).toString());
    command.add("-XX:-UsePerfData");
    if (verification) {
      // C-27: JDK 17+ need the unlock flag first; Java 8 accepts both.
      command.add("-XX:+UnlockDiagnosticVMOptions");
      command.add("-XX:+BytecodeVerificationLocal");
    }
    command.addAll(options);
    command.add("-Djava.io.tmpdir=" + tmp);
    command.add("-javaagent:" + agent);
    command.add("-Dargos.exporter=file");
    command.add("-Dargos.exporter.file.path=" + dir.resolve("out").resolve("tx.jsonl"));
    command.add("-Dargos.log.dir=" + dir.resolve("logs"));
    command.add("-Dargos.debug.transform-list=" + dir.resolve("out").resolve("transformed.txt"));
    command.add("-cp");
    command.add(required("argos.test.trivialAppClasses"));
    command.add(MAIN);
    command.add(dir.resolve("out").resolve("results.properties").toString());

    ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
    Map<String, String> environment = builder.environment();
    for (String variable : List.of("TMP", "TEMP", "TMPDIR")) {
      environment.put(variable, tmp.toString());
    }
    environment.keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("ARGOS_"));
    environment.remove("JAVA_TOOL_OPTIONS");
    environment.remove("_JAVA_OPTIONS");

    Path systemTemp = systemTemp();
    List<String> systemTempBefore = agentLikeEntries(systemTemp);
    List<String> events = new ArrayList<>();
    try (WatchService watcher = tmp.getFileSystem().newWatchService()) {
      WatchKey key =
          tmp.register(
              watcher,
              StandardWatchEventKinds.ENTRY_CREATE,
              StandardWatchEventKinds.ENTRY_MODIFY,
              StandardWatchEventKinds.ENTRY_DELETE);
      Process process = builder.start();
      CompletableFuture<String> stdout = read(process.getInputStream());
      CompletableFuture<String> stderr = read(process.getErrorStream());
      boolean exited = process.waitFor(120, TimeUnit.SECONDS);
      if (!exited) {
        process.destroyForcibly();
      }
      assertThat(exited).as("JDK %d %s finished within 120 s", jdk, name).isTrue();
      for (WatchEvent<?> event : key.pollEvents()) {
        events.add(event.kind().name() + " " + event.context());
      }
      try (Stream<Path> left = Files.list(tmp)) {
        left.forEach(path -> events.add("LEFT " + path.getFileName()));
      }
      List<String> added = new ArrayList<>(agentLikeEntries(systemTemp));
      added.removeAll(systemTempBefore);
      return new Run(
          jdk,
          dir,
          process.exitValue(),
          stdout.get(10, TimeUnit.SECONDS),
          stderr.get(10, TimeUnit.SECONDS),
          events,
          added);
    }
  }

  static CompletableFuture<String> read(InputStream stream) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  /** The temp directory of this (harness) process's environment, the agent's "system temp". */
  static Path systemTemp() {
    for (String variable : List.of("TMPDIR", "TEMP", "TMP")) {
      String value = System.getenv(variable);
      if (value != null && !value.isBlank() && Files.isDirectory(Path.of(value))) {
        return Path.of(value);
      }
    }
    return Path.of("/tmp");
  }

  /**
   * Entries whose names look like an agent's temporary files (jar extraction, ByteBuddy's {@code
   * byteBuddyJar*.jar}). The system temp directory is shared with other processes, so only these
   * names are compared; the repository's own check scripts ({@code argos-check-*}) are excluded.
   */
  static List<String> agentLikeEntries(Path directory) throws IOException {
    Pattern agentLike = Pattern.compile("(?i)^(argos|bytebuddy|byte-buddy).*");
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(directory)) {
      return entries
          .map(path -> path.getFileName().toString())
          .filter(entry -> agentLike.matcher(entry).matches())
          .filter(entry -> !entry.startsWith("argos-check-"))
          .collect(Collectors.toList());
    }
  }
}
