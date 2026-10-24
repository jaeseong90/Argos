/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.clickhouse.MemoryBudget;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * The ClickHouse server a run talks to: a container of the bundled image (optionally on a named
 * data volume, which allows restarts for cold runs), or an external installation (the in-house
 * argos-storage machine) reached with the three account passwords from the environment.
 */
public interface Server extends AutoCloseable {

  /** Client for one of the Argos accounts. */
  ClickHouseHttp client(Account account);

  /** One line for the report (image, memory limit, URL). */
  String describe();

  /** HTTP endpoint of the server (changes when a container restarts). */
  URI uri();

  /** True when {@link #restart()} is supported. */
  boolean canRestart();

  /** Restarts the server process (empties the mark cache and every in-memory cache). */
  void restart();

  /** Drops the page cache of the Docker host (privileged helper container); optional. */
  void dropOsPageCache();

  @Override
  void close();

  /** A container started by this server object; {@code volume} null means an anonymous volume. */
  static Server container(String volume, long memoryLimitBytes) {
    return new ContainerServer(volume, memoryLimitBytes, null);
  }

  /** Wraps a running container that the caller owns (tests); not restartable. */
  static Server wrap(ArgosClickHouse running) {
    return new ContainerServer(null, running.memoryBudget().limitBytes(), running);
  }

  /**
   * An external server. Passwords come from {@code ARGOS_CH_READER_PASSWORD}, {@code
   * ARGOS_CH_WRITER_PASSWORD} and {@code ARGOS_CH_MIGRATOR_PASSWORD}.
   */
  static Server external(URI url, Map<String, String> environment) {
    return new ExternalServer(url, environment);
  }

  /** {@link Server} on the bundled image with the repository configuration. */
  final class ContainerServer implements Server {
    private final String volume;
    private final long memoryLimitBytes;
    private final boolean owned;
    private ArgosClickHouse clickHouse;

    ContainerServer(String volume, long memoryLimitBytes, ArgosClickHouse running) {
      this.volume = volume;
      this.memoryLimitBytes = memoryLimitBytes;
      this.owned = running == null;
      this.clickHouse = running == null ? start() : running;
    }

    private ArgosClickHouse start() {
      ArgosClickHouse.Builder builder = ArgosClickHouse.builder().memoryLimit(memoryLimitBytes);
      if (volume != null) {
        builder.dataVolume(volume);
      }
      return builder.start();
    }

    @Override
    public ClickHouseHttp client(Account account) {
      return clickHouse.client(account);
    }

    @Override
    public URI uri() {
      return clickHouse.httpUri();
    }

    @Override
    public String describe() {
      MemoryBudget b = clickHouse.memoryBudget();
      return clickHouse.image().image()
          + ", container memory limit "
          + b.limitBytes()
          + " B (max_server_memory_usage "
          + b.serverBytes()
          + " B, reader "
          + b.readerQueryBytes()
          + " B per query)"
          + (volume == null ? "" : ", data volume " + volume);
    }

    @Override
    public boolean canRestart() {
      return owned && volume != null;
    }

    @Override
    public void restart() {
      if (!canRestart()) {
        throw new UnsupportedOperationException("Restart needs a server started on a named volume");
      }
      clickHouse.close();
      clickHouse = start();
    }

    @Override
    @SuppressWarnings("resource") // one-shot helper, stopped below
    public void dropOsPageCache() {
      GenericContainer<?> helper =
          new GenericContainer<>(DockerImageName.parse(clickHouse.image().image()))
              .withPrivilegedMode(true)
              .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
              .withCommand("-c", "sync && echo 3 > /proc/sys/vm/drop_caches")
              .withStartupCheckStrategy(new OneShotStartupCheckStrategy());
      try {
        helper.start();
      } finally {
        helper.stop();
      }
    }

    @Override
    public void close() {
      if (owned) {
        clickHouse.close();
      }
    }
  }

  /** {@link Server} at a URL with passwords from the environment. */
  final class ExternalServer implements Server {
    private final URI url;
    private final Map<String, String> environment;

    ExternalServer(URI url, Map<String, String> environment) {
      this.url = Objects.requireNonNull(url);
      this.environment = Map.copyOf(environment);
    }

    @Override
    public ClickHouseHttp client(Account account) {
      String variable =
          switch (account) {
            case READER -> "ARGOS_CH_READER_PASSWORD";
            case WRITER -> "ARGOS_CH_WRITER_PASSWORD";
            case MIGRATOR -> "ARGOS_CH_MIGRATOR_PASSWORD";
          };
      String password = environment.get(variable);
      if (password == null || password.isEmpty()) {
        throw new IllegalStateException("Set " + variable + " for " + account.user());
      }
      return new ClickHouseHttp(url, account.user(), password);
    }

    @Override
    public URI uri() {
      return url;
    }

    @Override
    public String describe() {
      return "external server " + url;
    }

    @Override
    public boolean canRestart() {
      return false;
    }

    @Override
    public void restart() {
      throw new UnsupportedOperationException("Cannot restart an external server");
    }

    @Override
    public void dropOsPageCache() {
      throw new UnsupportedOperationException("Use --cold command:<...> on an external server");
    }

    @Override
    public void close() {}
  }
}
