/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.management;

import io.argos.server.config.ServerBuild;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/info} (14 §4.16): {@code {"version", "commit", "protocol"}} of the server and
 * nothing else (the default contributors are off, {@code management.info.defaults.enabled}). The
 * endpoint needs the metrics bearer token ({@link ManagementAccessFilter}).
 */
@Component
public class ArgosInfoContributor implements InfoContributor {

  private final ServerBuild build;

  public ArgosInfoContributor(ServerBuild build) {
    this.build = build;
  }

  @Override
  public void contribute(Info.Builder builder) {
    builder
        .withDetail("version", build.version())
        .withDetail("commit", build.commit())
        .withDetail("protocol", build.protocol());
  }
}
