/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * toxiproxy between the agent and the collector (18 §11.1): one container, labelled {@code
 * io.argos.test=chaos} and named {@code argos-chaos-toxiproxy-<run>}, with one proxy {@value
 * #PROXY} whose upstream is the server's gRPC collector on the host.
 *
 * <ul>
 *   <li>{@link Network#BRIDGE} (Docker Desktop; and a Linux run with the netem variant): the API
 *       and the proxy port are published on 127.0.0.1 with random host ports, the upstream is
 *       {@code host.docker.internal:<collector port>} ({@code host-gateway}). Docker Desktop
 *       reaches the host's loopback listeners that way.
 *   <li>{@link Network#HOST} (Linux Docker Engine): toxiproxy runs in the host network on 127.0.0.1
 *       with free ports, the upstream is {@code 127.0.0.1:<collector port>}; the collector keeps
 *       listening on loopback only.
 * </ul>
 *
 * The image is pinned by digest ({@code toxiproxy-image.properties}, MIT,
 * docs/licenses/dev-only.md).
 */
public final class Toxiproxy implements AutoCloseable {

  /** The proxy's name. */
  public static final String PROXY = "collector";

  /** The API port inside the container (bridge mode). */
  static final int API_PORT = 8474;

  /** The proxy's listen port inside the container (bridge mode). */
  static final int LISTEN_PORT = 26000;

  /**
   * toxiproxy's network alias on the run's Docker network (bridge mode): containers of the run
   * (T-CH-005) reach the proxy as {@code argos-chaos-toxiproxy:26000}; the collector certificate of
   * the run names it ({@link ChaosStack}).
   */
  public static final String ALIAS = "argos-chaos-toxiproxy";

  /** Memory limit of the container. */
  static final long MEMORY_BYTES = 128L * 1024 * 1024;

  /** Where toxiproxy runs. */
  public enum Network {
    HOST,
    BRIDGE
  }

  private final GenericContainer<?> container;
  private final Network network;
  private final String apiAddress;
  private final String proxyAddress;
  private final ToxiproxyClient client;

  private final boolean onDockerNetwork;

  private Toxiproxy(
      GenericContainer<?> container,
      Network network,
      String apiAddress,
      String proxyAddress,
      boolean onDockerNetwork) {
    this.onDockerNetwork = onDockerNetwork;
    this.container = container;
    this.network = network;
    this.apiAddress = apiAddress;
    this.proxyAddress = proxyAddress;
    this.client = new ToxiproxyClient(URI.create("http://" + apiAddress));
  }

  /** The pinned image reference. */
  public static String image() {
    Properties p = new Properties();
    try (InputStream in = Toxiproxy.class.getResourceAsStream("toxiproxy-image.properties")) {
      p.load(Objects.requireNonNull(in, "toxiproxy-image.properties"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return p.getProperty("image").strip();
  }

  /**
   * The network of this Docker daemon: {@link Network#HOST} on a Linux Docker Engine, {@link
   * Network#BRIDGE} with Docker Desktop (any host OS) or when {@code needBridge} (netem).
   */
  public static Network defaultNetwork(
      boolean linuxHost, boolean dockerDesktop, boolean needBridge) {
    return linuxHost && !dockerDesktop && !needBridge ? Network.HOST : Network.BRIDGE;
  }

  /** Whether the daemon is Docker Desktop. */
  public static boolean dockerDesktop() {
    String os = DockerClientFactory.instance().getInfo().getOperatingSystem();
    return os != null && os.toLowerCase(Locale.ROOT).contains("docker desktop");
  }

  /**
   * Starts toxiproxy and creates the proxy to the collector port {@code collectorPort} on the host.
   */
  @SuppressWarnings("resource") // closed by close()
  public static Toxiproxy start(Network network, String runId, int collectorPort)
      throws IOException {
    return start(network, runId, collectorPort, null);
  }

  /**
   * Starts toxiproxy and creates the proxy to the collector port {@code collectorPort} on the host;
   * in bridge mode with {@code dockerNetwork} the container also joins that network as {@link
   * #ALIAS} ({@link #containerAddress()}).
   */
  @SuppressWarnings("resource") // closed by close()
  public static Toxiproxy start(
      Network network,
      String runId,
      int collectorPort,
      org.testcontainers.containers.Network dockerNetwork)
      throws IOException {
    GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image()))
            .withLabel("io.argos.test", "chaos")
            .withStartupTimeout(Duration.ofMinutes(2));
    String name = "argos-chaos-toxiproxy-" + runId;
    String listen;
    String upstream;
    int hostApi = 0;
    int hostListen = 0;
    if (network == Network.HOST) {
      hostApi = freePort();
      hostListen = freePort();
      while (hostListen == hostApi) {
        hostListen = freePort();
      }
      c.withNetworkMode("host")
          .withCommand("-host=127.0.0.1", "-port=" + hostApi)
          .waitingFor(Wait.forLogMessage(".*Starting Toxiproxy HTTP server.*", 1));
      listen = "127.0.0.1:" + hostListen;
      upstream = "127.0.0.1:" + collectorPort;
    } else {
      c.withExposedPorts(API_PORT, LISTEN_PORT)
          .withExtraHost("host.docker.internal", "host-gateway")
          .waitingFor(Wait.forHttp("/version").forPort(API_PORT));
      if (dockerNetwork != null) {
        c.withNetwork(dockerNetwork).withNetworkAliases(ALIAS);
      }
      listen = "0.0.0.0:" + LISTEN_PORT;
      upstream = "host.docker.internal:" + collectorPort;
    }
    c.withCreateContainerCmdModifier(
        cmd -> {
          cmd.withName(name);
          HostConfig host = Objects.requireNonNull(cmd.getHostConfig());
          host.withMemory(MEMORY_BYTES).withMemorySwap(MEMORY_BYTES);
          if (network == Network.BRIDGE) {
            host.withPortBindings(
                new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(API_PORT)),
                new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(LISTEN_PORT)));
          }
        });
    c.start();
    String api =
        network == Network.HOST ? "127.0.0.1:" + hostApi : "127.0.0.1:" + c.getMappedPort(API_PORT);
    String proxy =
        network == Network.HOST
            ? "127.0.0.1:" + hostListen
            : "127.0.0.1:" + c.getMappedPort(LISTEN_PORT);
    Toxiproxy t = new Toxiproxy(c, network, api, proxy, dockerNetwork != null);
    try {
      t.client.createProxy(PROXY, listen, upstream);
    } catch (RuntimeException e) {
      t.close();
      throw e;
    }
    return t;
  }

  /** The API client. */
  public ToxiproxyClient client() {
    return client;
  }

  /** {@code 127.0.0.1:<port>}: the agent's {@code server.address}. */
  public String proxyAddress() {
    return proxyAddress;
  }

  /**
   * The proxy as containers on the run's Docker network reach it ({@code
   * argos-chaos-toxiproxy:26000}), or null when toxiproxy is not on such a network (host mode).
   */
  public String containerAddress() {
    return onDockerNetwork ? ALIAS + ":" + LISTEN_PORT : null;
  }

  /** {@code 127.0.0.1:<port>} of the API. */
  public String apiAddress() {
    return apiAddress;
  }

  /** The network mode. */
  public Network network() {
    return network;
  }

  /** The container id. */
  public String containerId() {
    return container.getContainerId();
  }

  /** The container's log lines with Docker timestamps ({@code docker logs --timestamps}). */
  public List<String> logLines() {
    StringBuilder out = new StringBuilder();
    try {
      DockerClientFactory.instance()
          .client()
          .logContainerCmd(containerId())
          .withStdOut(true)
          .withStdErr(true)
          .withTimestamps(true)
          .exec(
              new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                  out.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                }
              })
          .awaitCompletion(60, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    List<String> lines = new ArrayList<>();
    for (String line : out.toString().lines().toList()) {
      if (!line.isBlank()) {
        lines.add(line);
      }
    }
    return lines;
  }

  /** Stops and removes the container. */
  @Override
  public void close() {
    container.stop();
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
