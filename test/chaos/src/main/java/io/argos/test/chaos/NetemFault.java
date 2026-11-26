/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The {@code tc netem} variant of T-CH-003 (18 §11.1 "손실 10%" as packet loss): a sidecar container
 * with {@code NET_ADMIN} in toxiproxy's network namespace adds {@code netem loss 10%} to its {@code
 * eth0}, so 10 % of the packets between the agent and the collector are lost and TCP retransmits
 * them. Linux Docker Engine only, with toxiproxy on the bridge network and an image that has {@code
 * tc} (iproute2) pinned by digest ({@code -Pchaos.netem.image}); {@link #unavailableReason} names
 * what is missing otherwise.
 */
public final class NetemFault implements Fault {

  /** Packet loss in percent. */
  static final int LOSS_PERCENT = 10;

  private final String image;

  public NetemFault(String image) {
    this.image = image;
  }

  /**
   * Why the netem variant cannot run here, or null: it needs a Linux host with Docker Engine,
   * toxiproxy on a bridge network and {@code -Pchaos.netem.image}.
   */
  public static String unavailableReason(
      boolean linuxHost, boolean dockerDesktop, Toxiproxy.Network network, String image) {
    if (!linuxHost || dockerDesktop) {
      return "tc netem needs a Linux Docker Engine host (NET_ADMIN sidecar in toxiproxy's network"
          + " namespace); this host is "
          + (linuxHost ? "Linux with Docker Desktop" : System.getProperty("os.name"))
          + ": the toxiproxy variant covers T-CH-003";
    }
    if (image == null) {
      return "no -Pchaos.netem.image=<image with tc (iproute2)>@sha256:<digest> given";
    }
    if (network != Toxiproxy.Network.BRIDGE) {
      return "toxiproxy runs in the host network: netem would change the host's own interface";
    }
    return null;
  }

  /** The sidecar's command line ({@code docker run ... tc qdisc <args>}). */
  static List<String> sidecar(String container, String image, String... tcArgs) {
    List<String> command = new ArrayList<>();
    command.add("docker");
    command.add("run");
    command.add("--rm");
    command.add("--label");
    command.add("io.argos.test=chaos");
    command.add("--network");
    command.add("container:" + container);
    command.add("--cap-add");
    command.add("NET_ADMIN");
    command.add("--entrypoint");
    command.add("tc");
    command.add(image);
    command.addAll(List.of(tcArgs));
    return command;
  }

  @Override
  public String describe() {
    return String.format(
        Locale.ROOT, "tc netem loss %d%% on toxiproxy's eth0 (sidecar %s)", LOSS_PERCENT, image);
  }

  @Override
  public void inject(ScenarioContext c) throws IOException, InterruptedException {
    run(
        sidecar(
            c.stack().toxiproxy().containerId(),
            image,
            "qdisc",
            "add",
            "dev",
            "eth0",
            "root",
            "netem",
            "loss",
            LOSS_PERCENT + "%"));
  }

  @Override
  public void remove(ScenarioContext c) throws IOException, InterruptedException {
    run(sidecar(c.stack().toxiproxy().containerId(), image, "qdisc", "del", "dev", "eth0", "root"));
  }

  private static void run(List<String> command) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out =
        new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    if (!p.waitFor(2, TimeUnit.MINUTES) || p.exitValue() != 0) {
      throw new IllegalStateException("netem sidecar failed: " + out.strip());
    }
  }
}
