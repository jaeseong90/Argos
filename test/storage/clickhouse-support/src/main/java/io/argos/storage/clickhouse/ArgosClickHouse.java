/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.clickhouse;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Ulimit;
import com.github.dockerjava.api.model.Volume;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A ClickHouse server in Docker (Testcontainers) configured exactly like an Argos installation:
 * pinned image, {@code deploy/clickhouse/config.d/argos.xml} and {@code users.d/argos.xml}, the
 * {@code default} user removed, fresh CSPRNG passwords for the three accounts (only their SHA-256
 * reaches the container, SEC-74) and the RAM substitution variables of a {@link MemoryBudget}. The
 * HTTP port is published on a random port of the loopback interface only (127.0.0.1) when the
 * Docker daemon runs on this machine, never on the LAN addresses of the host (SEC-75).
 *
 * <pre>{@code
 * try (ArgosClickHouse ch = ArgosClickHouse.builder().memoryLimit(5 * MemoryBudget.GIB).start()) {
 *   SchemaApplier.apply(ch.client(Account.MIGRATOR), SchemaParameters.defaults());
 *   ch.client(Account.WRITER).insert("INSERT INTO argos.xlog (...) FORMAT TSV", rows, settings);
 * }
 * }</pre>
 */
public final class ArgosClickHouse implements AutoCloseable {

  /** HTTP port inside the container. */
  public static final int HTTP_PORT = 8123;

  /** Host address the HTTP port is published on when the Docker daemon is local. */
  public static final String LOOPBACK = "127.0.0.1";

  private static final String CONFIG_ROOT = "/etc/clickhouse-server/";
  private static final String DATA_DIR = "/var/lib/clickhouse";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final System.Logger LOG = System.getLogger(ArgosClickHouse.class.getName());

  private final GenericContainer<?> container;
  private final ClickHouseImage image;
  private final MemoryBudget budget;
  private final Map<Account, String> passwords;

  private ArgosClickHouse(
      GenericContainer<?> container,
      ClickHouseImage image,
      MemoryBudget budget,
      Map<Account, String> passwords) {
    this.container = container;
    this.image = image;
    this.budget = budget;
    this.passwords = passwords;
  }

  /** A builder with the bundled image, a 2 GiB memory limit and the repository configuration. */
  public static Builder builder() {
    return new Builder();
  }

  /** Client of one of the Argos accounts. */
  public ClickHouseHttp client(Account account) {
    return new ClickHouseHttp(httpUri(), account.user(), passwords.get(account));
  }

  /** Client for an arbitrary user and password (negative tests, e.g. the removed default user). */
  public ClickHouseHttp client(String user, String password) {
    return new ClickHouseHttp(httpUri(), user, password);
  }

  /** HTTP endpoint mapped on the Docker host. */
  public URI httpUri() {
    return URI.create(
        "http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT) + "/");
  }

  /** The image this server runs. */
  public ClickHouseImage image() {
    return image;
  }

  /** The memory budget (container limit and substitution variables). */
  public MemoryBudget memoryBudget() {
    return budget;
  }

  /** Runs a command in the container as the {@code clickhouse} user (owner of the data dir). */
  public ExecResult execAsClickHouse(String... command) {
    try {
      return container.execInContainer(
          ExecConfig.builder().user("clickhouse").command(command).build());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Container logs so far (stdout and stderr). */
  public String logs() {
    return container.getLogs();
  }

  /**
   * Where Docker publishes the HTTP port ({@code docker inspect}): the host addresses requested in
   * {@code HostConfig.PortBindings} and the ones in effect in {@code NetworkSettings.Ports}.
   */
  public HttpPublication httpPublication() {
    ExposedPort http = ExposedPort.tcp(HTTP_PORT);
    InspectContainerResponse info = container.getContainerInfo();
    HostConfig hostConfig = Objects.requireNonNull(info.getHostConfig());
    return new HttpPublication(
        hostIps(Objects.requireNonNull(hostConfig.getPortBindings()), http),
        hostIps(info.getNetworkSettings().getPorts(), http));
  }

  private static List<String> hostIps(Ports ports, ExposedPort port) {
    Ports.Binding[] bindings = ports.getBindings().get(port);
    List<String> ips = new ArrayList<>();
    if (bindings != null) {
      Arrays.stream(bindings).map(Ports.Binding::getHostIp).forEach(ips::add);
    }
    return ips;
  }

  /**
   * Whether the HTTP port is published on the loopback interface only: true when the Docker daemon
   * runs on this machine (local socket, named pipe or a loopback TCP address). With a remote daemon
   * ({@code DOCKER_HOST=tcp://other-host}, Docker-in-Docker) a loopback binding would be
   * unreachable, so the port is published on all interfaces of that host and a warning is logged.
   */
  public static boolean publishesOnLoopback() {
    DockerStartup.ensureInitialised();
    String host = DockerClientFactory.instance().dockerHostIpAddress();
    if ("localhost".equalsIgnoreCase(host)) {
      return true;
    }
    try {
      return InetAddress.getByName(host).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }

  /** Stops and removes the container. A named data volume survives. */
  @Override
  public void close() {
    container.stop();
  }

  /** Removes a named Docker volume created by {@link Builder#dataVolume(String)}. */
  public static void removeVolume(String name) {
    DockerStartup.ensureInitialised();
    DockerClientFactory.instance().client().removeVolumeCmd(name).exec();
  }

  /** Builder for {@link ArgosClickHouse}. */
  public static final class Builder {

    private ClickHouseImage image = ClickHouseImage.bundled();
    private long memoryLimitBytes = 2 * MemoryBudget.GIB;
    private Path clickhouseDir = RepositoryLayout.clickhouseDir();
    private final Map<String, String> extraConfig = new LinkedHashMap<>();
    private final Map<String, String> environmentOverrides = new LinkedHashMap<>();
    private String dataVolume;
    private Duration startupTimeout = Duration.ofMinutes(3);

    private Builder() {}

    /** Image to run (default: the bundled LTS of image.properties). */
    public Builder image(ClickHouseImage value) {
      this.image = Objects.requireNonNull(value);
      return this;
    }

    /** Container memory limit; the RAM substitution variables follow it (MemoryBudget). */
    public Builder memoryLimit(long bytes) {
      this.memoryLimitBytes = bytes;
      return this;
    }

    /** Directory with config.d/ and users.d/ (default: deploy/clickhouse of the repository). */
    public Builder clickhouseDir(Path dir) {
      this.clickhouseDir = Objects.requireNonNull(dir);
      return this;
    }

    /**
     * Adds a test-only config file, e.g. {@code config.d/keeper.xml}, relative to the config root.
     */
    public Builder extraConfig(String relativePath, String content) {
      if (relativePath.startsWith("/") || relativePath.contains("..")) {
        throw new IllegalArgumentException("Relative path expected: " + relativePath);
      }
      extraConfig.put(relativePath, content);
      return this;
    }

    /**
     * Overrides an environment variable after the generated ones; an empty value removes the
     * variable (negative tests: ClickHouse must refuse to start).
     */
    public Builder environment(String name, String value) {
      environmentOverrides.put(name, value);
      return this;
    }

    /** Keeps {@code /var/lib/clickhouse} in a named Docker volume (restart, upgrade tests). */
    public Builder dataVolume(String name) {
      this.dataVolume = Objects.requireNonNull(name);
      return this;
    }

    /** Startup timeout (default 3 minutes). */
    public Builder startupTimeout(Duration value) {
      this.startupTimeout = Objects.requireNonNull(value);
      return this;
    }

    /** Starts the server and waits until {@code /ping} answers. */
    @SuppressWarnings("resource") // closed by ArgosClickHouse.close()
    public ArgosClickHouse start() {
      MemoryBudget budget = MemoryBudget.forContainerLimit(memoryLimitBytes);
      Map<Account, String> passwords = new EnumMap<>(Account.class);
      GenericContainer<?> container =
          container(budget, passwords)
              .waitingFor(
                  Wait.forHttp("/ping")
                      .forPort(HTTP_PORT)
                      .forStatusCode(200)
                      .withStartupTimeout(startupTimeout));
      container.start();
      return new ArgosClickHouse(container, image, budget, passwords);
    }

    /**
     * Starts the container without waiting for readiness and watches it for {@code timeout}: for
     * negative tests where ClickHouse must refuse to start (e.g. an unset substitution variable).
     * The container is removed afterwards.
     */
    public StartupOutcome startExpectingExit(Duration timeout) {
      GenericContainer<?> container =
          container(MemoryBudget.forContainerLimit(memoryLimitBytes), new EnumMap<>(Account.class))
              .withStartupCheckStrategy(
                  new StartupCheckStrategy() {
                    @Override
                    public StartupStatus checkStartupState(DockerClient client, String id) {
                      return StartupStatus.SUCCESSFUL;
                    }
                  })
              .waitingFor(
                  new AbstractWaitStrategy() {
                    @Override
                    protected void waitUntilReady() {}
                  });
      try {
        container.start();
        DockerClient client = DockerClientFactory.instance().client();
        Instant deadline = Instant.now().plus(timeout);
        InspectContainerResponse.ContainerState state;
        do {
          Thread.sleep(500);
          state = client.inspectContainerCmd(container.getContainerId()).exec().getState();
        } while (Boolean.TRUE.equals(state.getRunning()) && Instant.now().isBefore(deadline));
        boolean exited = !Boolean.TRUE.equals(state.getRunning());
        Long exitCode = state.getExitCodeLong();
        String errorLog;
        try {
          errorLog =
              container.copyFileFromContainer(
                  "/var/log/clickhouse-server/clickhouse-server.err.log",
                  in -> new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
          errorLog = "";
        }
        return new StartupOutcome(
            exited, exitCode == null ? -1 : exitCode, container.getLogs(), errorLog);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted", e);
      } finally {
        container.stop();
      }
    }

    private GenericContainer<?> container(MemoryBudget budget, Map<Account, String> passwords) {
      Map<String, String> env = new LinkedHashMap<>();
      env.put("CLICKHOUSE_SKIP_USER_SETUP", "1");
      env.putAll(budget.environment());
      for (Account account : Account.values()) {
        String password = newPassword();
        passwords.put(account, password);
        env.put(account.passwordHashVariable(), sha256Hex(password));
      }
      for (Map.Entry<String, String> e : environmentOverrides.entrySet()) {
        if (e.getValue().isEmpty()) {
          env.remove(e.getKey());
        } else {
          env.put(e.getKey(), e.getValue());
        }
      }
      long limit = memoryLimitBytes;
      String volume = dataVolume;
      boolean loopback = publishesOnLoopback();
      if (!loopback) {
        LOG.log(
            System.Logger.Level.WARNING,
            "Docker host {0} is not local: the ClickHouse HTTP port is published on all interfaces"
                + " of that host",
            DockerClientFactory.instance().dockerHostIpAddress());
      }
      GenericContainer<?> container =
          new GenericContainer<>(DockerImageName.parse(image.image()))
              .withExposedPorts(HTTP_PORT)
              .withEnv(env)
              .withCopyFileToContainer(
                  MountableFile.forHostPath(requireFile("config.d/argos.xml")),
                  CONFIG_ROOT + "config.d/argos.xml")
              .withCopyFileToContainer(
                  MountableFile.forHostPath(requireFile("users.d/argos.xml")),
                  CONFIG_ROOT + "users.d/argos.xml")
              .withCreateContainerCmdModifier(
                  cmd -> {
                    HostConfig host = Objects.requireNonNull(cmd.getHostConfig());
                    host.withMemory(limit)
                        .withMemorySwap(limit)
                        .withUlimits(new Ulimit[] {new Ulimit("nofile", 262144L, 262144L)});
                    if (volume != null) {
                      host.withBinds(new Bind(volume, new Volume(DATA_DIR)));
                    }
                    if (loopback) {
                      // A random host port on 127.0.0.1 only (Testcontainers publishes the exposed
                      // ports on all interfaces otherwise); getHost()/getMappedPort() still work.
                      host.withPortBindings(
                          new PortBinding(
                              Ports.Binding.bindIp(LOOPBACK), ExposedPort.tcp(HTTP_PORT)));
                    }
                  });
      for (Map.Entry<String, String> e : extraConfig.entrySet()) {
        container.withCopyToContainer(
            Transferable.of(e.getValue().getBytes(StandardCharsets.UTF_8)),
            CONFIG_ROOT + e.getKey());
      }
      return container;
    }

    private Path requireFile(String relative) {
      Path file = clickhouseDir.resolve(relative);
      if (!Files.isRegularFile(file)) {
        throw new IllegalStateException("Missing ClickHouse config file: " + file);
      }
      return file;
    }
  }

  /**
   * What happened to a container started with {@link Builder#startExpectingExit(Duration)}.
   *
   * @param exited the container stopped within the timeout
   * @param exitCode exit code (-1 if unknown)
   * @param logs container stdout and stderr
   * @param errorLog content of clickhouse-server.err.log ("" if absent)
   */
  public record StartupOutcome(boolean exited, long exitCode, String logs, String errorLog) {}

  /**
   * Host addresses of the HTTP port publication.
   *
   * @param configured {@code HostConfig.PortBindings} host IPs ("" = all interfaces)
   * @param effective {@code NetworkSettings.Ports} host IPs (what Docker actually bound)
   */
  public record HttpPublication(List<String> configured, List<String> effective) {}

  /** 32 random bytes, base64url without padding (the installer's format, 17 §3.7). */
  static String newPassword() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Lower-case hex SHA-256, the format of {@code password_sha256_hex}. */
  public static String sha256Hex(String password) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(password.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
