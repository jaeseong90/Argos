/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.load;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.DockerClientFactory;

/**
 * Docker facts of the stack's containers: the container that publishes a host port, the memory use
 * of {@code docker stats --no-stream} (the docker CLI on the {@code PATH}; the same numbers Docker
 * Desktop shows) and {@code State.OOMKilled} of {@code docker inspect} (Testcontainers' client).
 */
public final class DockerContainers {

  private static final Pattern SIZE =
      Pattern.compile("^\\s*([0-9.]+)\\s*([KMGT]?i?B|[kKMGT]B|B)\\s*$", Pattern.CASE_INSENSITIVE);

  /**
   * One {@code docker stats} line.
   *
   * @param id the container id as printed (short)
   * @param memoryUsageBytes memory usage (cgroup usage without the page cache, as Docker reports
   *     it)
   * @param memoryLimitBytes memory limit
   * @param cpuPercent CPU in percent of one CPU ({@code 250.0} = 2.5 CPUs)
   */
  public record Stats(String id, long memoryUsageBytes, long memoryLimitBytes, double cpuPercent) {}

  private DockerContainers() {}

  /** The id of the running container that publishes host port {@code hostPort}. */
  public static Optional<String> publishing(int hostPort) {
    DockerClient docker = DockerClientFactory.instance().client();
    return publishing(docker.listContainersCmd().exec(), hostPort).map(Container::getId);
  }

  /**
   * The first of {@code containers} that publishes host port {@code hostPort}. A container without
   * port data ({@code Ports} missing: host network, containers of other tools) and a port entry
   * without a public port are skipped.
   */
  public static Optional<Container> publishing(Iterable<Container> containers, int hostPort) {
    for (Container c : containers) {
      ContainerPort[] ports = c.getPorts();
      if (ports == null) {
        continue;
      }
      for (ContainerPort p : ports) {
        if (p != null && p.getPublicPort() != null && p.getPublicPort() == hostPort) {
          return Optional.of(c);
        }
      }
    }
    return Optional.empty();
  }

  /** {@code State.OOMKilled} of a container (false when the field is missing). */
  public static boolean oomKilled(String containerId) {
    InspectContainerResponse inspect =
        DockerClientFactory.instance().client().inspectContainerCmd(containerId).exec();
    return inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getOOMKilled());
  }

  /**
   * One {@code docker stats --no-stream} of {@code containerIds}, keyed by the id given (matched by
   * prefix); empty when the docker CLI is not available.
   */
  public static Map<String, Stats> stats(List<String> containerIds) {
    if (containerIds.isEmpty()) {
      return Map.of();
    }
    List<String> command = new ArrayList<>();
    command.add("docker");
    command.add("stats");
    command.add("--no-stream");
    command.add("--format");
    command.add("{{.ID}}\t{{.MemUsage}}\t{{.CPUPerc}}");
    command.addAll(containerIds);
    String output;
    try {
      Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
      output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
        p.destroyForcibly();
        return Map.of();
      }
    } catch (IOException e) {
      return Map.of();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Map.of();
    }
    Map<String, Stats> byId = new LinkedHashMap<>();
    for (Stats s : parseStats(output)) {
      for (String id : containerIds) {
        if (id.startsWith(s.id()) || s.id().startsWith(id)) {
          byId.put(id, s);
        }
      }
    }
    return byId;
  }

  /** Parses the {@code {{.ID}}\t{{.MemUsage}}\t{{.CPUPerc}}} lines; other lines are skipped. */
  public static List<Stats> parseStats(String output) {
    List<Stats> stats = new ArrayList<>();
    for (String line : output.lines().toList()) {
      String[] cells = line.split("\t", -1);
      if (cells.length < 3) {
        continue;
      }
      int slash = cells[1].indexOf('/');
      if (slash < 0) {
        continue;
      }
      long usage = parseSize(cells[1].substring(0, slash));
      long limit = parseSize(cells[1].substring(slash + 1));
      String cpu = cells[2].strip().replace("%", "");
      double cpuPercent;
      try {
        cpuPercent = Double.parseDouble(cpu);
      } catch (NumberFormatException e) {
        cpuPercent = Double.NaN;
      }
      if (usage >= 0) {
        stats.add(new Stats(cells[0].strip(), usage, limit, cpuPercent));
      }
    }
    return stats;
  }

  /**
   * A docker size: {@code 512MiB}, {@code 1.5GiB}, {@code 800kB}, {@code 12B} (binary units for
   * {@code KiB}..{@code TiB}, decimal for {@code kB}..{@code TB}); -1 when unreadable.
   */
  public static long parseSize(String text) {
    Matcher m = SIZE.matcher(text);
    if (!m.matches()) {
      return -1;
    }
    double value = Double.parseDouble(m.group(1));
    String unit = m.group(2);
    double factor =
        switch (unit.toLowerCase(Locale.ROOT)) {
          case "b" -> 1;
          case "kib" -> 1024.0;
          case "mib" -> 1024.0 * 1024;
          case "gib" -> 1024.0 * 1024 * 1024;
          case "tib" -> 1024.0 * 1024 * 1024 * 1024;
          case "kb" -> 1e3;
          case "mb" -> 1e6;
          case "gb" -> 1e9;
          case "tb" -> 1e12;
          default -> -1;
        };
    return factor < 0 ? -1 : Math.round(value * factor);
  }
}
