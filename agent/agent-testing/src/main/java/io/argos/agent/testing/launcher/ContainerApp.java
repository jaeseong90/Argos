/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.JsonlCollected;
import io.argos.agent.testing.jvm.JcmdClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A {@link RunningApp} in a Docker container (18 §5.2 {@code container(inDocker, cpus, memory)},
 * Testcontainers {@code GenericContainer}): image {@code eclipse-temurin:<jdk>-jdk} pinned by
 * digest ({@link #IMAGES}; the JDK variant, so that {@code jcmd} exists), the app jar at {@code
 * /argos/app/app.jar}, the run's work directory bind-mounted at {@code /argos/run} (agent log,
 * JSONL, transform files stay readable on the host), port 8080 published to a random host port,
 * {@code --cpus}/{@code --memory} from the launcher (memory defaults to 4g: the shared development
 * machine caps containers at 5 GiB). With {@link AppLauncher#collector(FakeCollector)} the agent
 * sends to {@code host.docker.internal:<port>} ({@code --add-host
 * host.docker.internal:host-gateway}); on a native Linux Docker host the collector must listen
 * beyond the loopback ({@link FakeCollector.Builder#bindAddress(String)}, tests only).
 *
 * <p>Differences from {@link ProcessApp}: {@link #pid()} is -1 ({@link #jcmd()} runs {@code jcmd 1}
 * inside the container), the temp directory is not watched live ({@link #tempCreated()} is the tree
 * at the time of the call) and {@link #startupTime()} ends at readiness (Testcontainers' HTTP
 * wait).
 */
public final class ContainerApp implements RunningApp {
  static final String RUN_DIR = "/argos/run";
  static final String APP_JAR = "/argos/app/app.jar";
  static final int APP_PORT = 8080;
  static final String DEFAULT_MEMORY = "4g";

  /**
   * The token file inside the container with {@link AppLauncher#tokenFileMode(String)}: a copy of
   * the run's {@code collector/token} outside the bind mount, so its mode is what {@code chmod}
   * set.
   */
  public static final String TOKEN_FILE = "/argos/secret/token";

  /** System property prefix of an image override: {@code argos.test.container.image.<jdk>}. */
  static final String IMAGE_PROPERTY = "argos.test.container.image.";

  /**
   * The JDK images, pinned by the digest of their multi-arch index (06 SEC-73, like {@code
   * test/bench} LoadSet and the Ryuk image of {@code testcontainers.properties}). Looked up on
   * 2026-11-03 with {@code docker buildx imagetools inspect eclipse-temurin:<jdk>-jdk} (Digest
   * line); refresh them the same way. A JDK without an entry fails instead of pulling a floating
   * tag; {@code -Pagent.container.image.<jdk>=<image@digest>} (system property {@value
   * #IMAGE_PROPERTY}{@code <jdk>}) overrides an entry or adds one.
   */
  static final Map<Integer, String> IMAGES =
      Map.of(
          8,
          "eclipse-temurin:8-jdk@sha256:e9255b50040337b0dec91027868dca1952ef5441bf0c8f878c5f790eaedbfea2",
          11,
          "eclipse-temurin:11-jdk@sha256:810d03ef723872693a38e5c1367cc88a0bee58edcf06a2ab868ccc048888db73",
          17,
          "eclipse-temurin:17-jdk@sha256:91011320978c10ec51d2cde1eb6f616db6d2a739a0e521096c343dd3fed9abe6",
          21,
          "eclipse-temurin:21-jdk@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a",
          25,
          "eclipse-temurin:25-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507");

  private final SampleApp app;
  private final int jdk;
  private final Path dir;
  private final List<String> command;
  private final GenericContainer<?> container;
  private final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final Duration startup;
  private final FakeCollector collector;

  private ContainerApp(
      SampleApp app,
      int jdk,
      Path dir,
      List<String> command,
      GenericContainer<?> container,
      Duration startup,
      FakeCollector collector) {
    this.app = app;
    this.jdk = jdk;
    this.dir = dir;
    this.command = List.copyOf(command);
    this.container = container;
    this.startup = startup;
    this.collector = collector;
  }

  static ContainerApp start(AppLauncher launcher, Path dir, Duration timeout) {
    SampleApp app = launcher.app();
    int jdk = launcher.jdk().feature();
    List<String> command = containerCommand(launcher.command(dir), dir, app);
    if (launcher.tokenFileMode() != null) {
      command = withTokenFileCopy(command, launcher.tokenFileMode());
    }
    String memory = launcher.memory() != null ? launcher.memory() : DEFAULT_MEMORY;
    String cpus = launcher.cpus();
    GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse(image(jdk)))
            .withCopyFileToContainer(MountableFile.forHostPath(app.artifact()), APP_JAR)
            .withFileSystemBind(dir.toString(), RUN_DIR, BindMode.READ_WRITE)
            .withExposedPorts(APP_PORT)
            .withEnv("SERVER_PORT", Integer.toString(APP_PORT))
            .withEnv("SERVER_ADDRESS", "0.0.0.0")
            .withEnv("SPRING_MAIN_BANNER_MODE", "off")
            .withEnv("TMP", RUN_DIR + "/tmp")
            .withEnv("TEMP", RUN_DIR + "/tmp")
            .withEnv("TMPDIR", RUN_DIR + "/tmp")
            .withCommand(command.toArray(String[]::new))
            .withCreateContainerCmdModifier(
                cmd -> {
                  var host = cmd.getHostConfig();
                  if (host != null) {
                    host.withMemory(bytes(memory));
                    if (cpus != null) {
                      host.withNanoCPUs((long) (Double.parseDouble(cpus) * 1_000_000_000L));
                    }
                  }
                })
            .waitingFor(
                Wait.forHttp(app.readyPath())
                    .forPort(APP_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(timeout));
    launcher.environment().forEach(container::withEnv);
    FakeCollector collector = launcher.collector();
    if (collector != null) {
      // The agent reaches the collector on the Docker host (FakeCollector#containerAddress()).
      container.withExtraHost(FakeCollector.DOCKER_HOST, "host-gateway");
    }
    long spawn = System.nanoTime();
    container.start();
    return new ContainerApp(
        app, jdk, dir, command, container, Duration.ofNanos(System.nanoTime() - spawn), collector);
  }

  /**
   * The pinned image of {@code jdk}: the system property {@value #IMAGE_PROPERTY}{@code <jdk>},
   * else {@link #IMAGES}; it must name a digest ({@code @sha256:}).
   */
  static String image(int jdk) {
    String override = System.getProperty(IMAGE_PROPERTY + jdk);
    String image = override != null && !override.isBlank() ? override.strip() : IMAGES.get(jdk);
    if (image == null) {
      throw new IllegalStateException(
          "no pinned container image for JDK "
              + jdk
              + " (pinned: "
              + new TreeSet<>(IMAGES.keySet())
              + "); pass -Pagent.container.image."
              + jdk
              + "=eclipse-temurin:"
              + jdk
              + "-jdk@sha256:<index digest>");
    }
    if (!image.contains("@sha256:")) {
      throw new IllegalStateException(
          "container image for JDK " + jdk + " is not pinned by a digest: " + image);
    }
    return image;
  }

  /** The host command of {@link AppLauncher#command} with container paths. */
  static List<String> containerCommand(List<String> host, Path dir, SampleApp app) {
    String hostDir = dir.toString();
    String hostJar = app.artifact().toAbsolutePath().toString();
    List<String> command = new ArrayList<>();
    command.add("java");
    for (String argument : host.subList(1, host.size())) {
      String mapped = argument.replace(hostJar, APP_JAR);
      int at = mapped.indexOf(hostDir);
      if (at >= 0) {
        String rest = mapped.substring(at + hostDir.length()).replace('\\', '/');
        mapped = mapped.substring(0, at) + RUN_DIR + rest;
      }
      command.add(mapped);
    }
    return command;
  }

  /**
   * {@code command} behind a shell step that copies the run's token file to {@link #TOKEN_FILE},
   * sets {@code mode} on the copy and then {@code exec}s the command unchanged (its arguments are
   * passed as {@code "$@"}, never parsed by the shell). The token value is not on the command line.
   */
  static List<String> withTokenFileCopy(List<String> command, String mode) {
    if (!mode.matches("[0-7]{3,4}")) {
      throw new IllegalArgumentException("token file mode must be octal: " + mode);
    }
    String directory = TOKEN_FILE.substring(0, TOKEN_FILE.lastIndexOf('/'));
    List<String> wrapped = new ArrayList<>();
    wrapped.add("sh");
    wrapped.add("-c");
    wrapped.add(
        "mkdir -p "
            + directory
            + " && cp "
            + RUN_DIR
            + "/"
            + AppLauncher.COLLECTOR_DIR
            + "/token "
            + TOKEN_FILE
            + " && chmod "
            + mode
            + " "
            + TOKEN_FILE
            + " && exec \"$@\"");
    wrapped.add("argos-token-copy"); // $0 of the script
    wrapped.addAll(command);
    return wrapped;
  }

  /** {@code 512m}, {@code 4g}, {@code 1073741824} → bytes. */
  static long bytes(String size) {
    String value = size.trim().toLowerCase(Locale.ROOT);
    long unit = 1;
    if (value.endsWith("g")) {
      unit = 1024L * 1024 * 1024;
    } else if (value.endsWith("m")) {
      unit = 1024L * 1024;
    } else if (value.endsWith("k")) {
      unit = 1024L;
    }
    String digits = unit == 1 ? value : value.substring(0, value.length() - 1);
    return Long.parseLong(digits) * unit;
  }

  @Override
  public SampleApp app() {
    return app;
  }

  @Override
  public int jdk() {
    return jdk;
  }

  @Override
  public int port() {
    return container.getMappedPort(APP_PORT);
  }

  @Override
  public long pid() {
    return -1;
  }

  @Override
  public boolean isAlive() {
    return container.isRunning();
  }

  @Override
  public Path workDir() {
    return dir;
  }

  @Override
  public List<String> command() {
    return command;
  }

  @Override
  public HttpResult call(String method, String path, Map<String, String> headers)
      throws IOException, InterruptedException {
    return ProcessApp.Http.call(client, baseUrl(), method, path, headers);
  }

  @Override
  public LoadResult load(int rps, Duration duration, String... paths) throws InterruptedException {
    return ProcessApp.Http.load(baseUrl(), rps, duration, paths);
  }

  @Override
  public Collected collected() {
    return collector != null ? collector.collected() : new JsonlCollected(transactions());
  }

  @Override
  public JcmdClient jcmd() {
    return new JcmdClient(
        1,
        arguments -> {
          List<String> line = new ArrayList<>();
          line.add("jcmd");
          line.addAll(arguments);
          try {
            Container.ExecResult result = container.execInContainer(line.toArray(String[]::new));
            if (result.getExitCode() != 0) {
              throw new IllegalStateException(
                  String.join(" ", line)
                      + " exited with "
                      + result.getExitCode()
                      + ":\n"
                      + result.getStdout()
                      + result.getStderr());
            }
            return result.getStdout();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted: " + line, e);
          }
        });
  }

  @Override
  public SelfDiagnostics selfDiagnostics() {
    return readSelfDiagnostics();
  }

  @Override
  public Duration startupTime() {
    return startup;
  }

  @Override
  public Duration readyTime() {
    return startup;
  }

  @Override
  public void kill9() {
    container
        .getDockerClient()
        .killContainerCmd(container.getContainerId())
        .withSignal("KILL")
        .exec();
  }

  @Override
  public void gracefulStop() {
    container.getDockerClient().stopContainerCmd(container.getContainerId()).withTimeout(15).exec();
  }

  @Override
  public List<String> stdoutLines() {
    return container.getLogs(OutputFrame.OutputType.STDOUT).lines().toList();
  }

  @Override
  public List<String> stderrLines() {
    return container.getLogs(OutputFrame.OutputType.STDERR).lines().toList();
  }

  @Override
  public String agentLog() {
    Path log = agentLogDir().resolve("argos-agent.log");
    try {
      return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Set<String> tempCreated() {
    Path tmp = dir.resolve("tmp");
    Set<String> names = new TreeSet<>();
    if (!Files.isDirectory(tmp)) {
      return names;
    }
    try {
      Files.walkFileTree(
          tmp,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
              if (!path.equals(tmp)) {
                names.add(tmp.relativize(path).toString().replace('\\', '/'));
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
              names.add(tmp.relativize(path).toString().replace('\\', '/'));
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return names;
  }

  @Override
  public Map<String, Long> tempEvents() {
    return Map.of();
  }

  @Override
  public String describe() {
    return "app "
        + app.name()
        + " on JDK "
        + jdk
        + " in container "
        + container.getContainerId()
        + " ("
        + dir
        + ")\ncommand: "
        + String.join(" ", command)
        + "\nlogs:\n"
        + container.getLogs();
  }

  @Override
  public void close() {
    container.stop();
  }
}
