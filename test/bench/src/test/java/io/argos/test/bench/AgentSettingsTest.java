/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 18 §7.3 --exporter noop|grpc (grpc refused until WO-11) and 18 §7.5 --profile. */
class AgentSettingsTest {

  static Path agentJar(Path dir, String version, boolean grpc) throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
    Path jar = dir.resolve("argos-agent-" + version + (grpc ? "-grpc" : "") + ".jar");
    try (OutputStream file = Files.newOutputStream(jar);
        JarOutputStream out = new JarOutputStream(file, manifest)) {
      out.putNextEntry(new JarEntry("inst/io/argos/agent/core/export/Exporters.classdata"));
      out.closeEntry();
      if (grpc) {
        out.putNextEntry(new JarEntry(AgentSettings.GRPC_FACTORY_ENTRY));
        out.closeEntry();
      }
    }
    return jar;
  }

  @Test
  @DisplayName("--exporter grpc is refused while the agent jar has no gRPC transport (WO-11)")
  void grpcRefusedWithoutTransport(@TempDir Path dir) throws IOException {
    Path jar = agentJar(dir, "0.0.0-SNAPSHOT", false);
    assertThatThrownBy(() -> AgentSettings.exporter("grpc", jar, "127.0.0.1:7600"))
        .isInstanceOf(UsageException.class)
        .hasMessageContaining("has no gRPC transport")
        .hasMessageContaining("WO-11")
        .hasMessageContaining(AgentSettings.GRPC_FACTORY_ENTRY);
  }

  @Test
  @DisplayName("--exporter grpc with a transport needs --collector")
  void grpcNeedsCollector(@TempDir Path dir) throws IOException {
    Path jar = agentJar(dir, "0.0.0-SNAPSHOT", true);
    assertThatThrownBy(() -> AgentSettings.exporter("grpc", jar, null))
        .isInstanceOf(UsageException.class)
        .hasMessageContaining("--collector");
    AgentSettings.Exporter e = AgentSettings.exporter("grpc", jar, "10.0.0.5:7600");
    assertThat(e.agentConfig())
        .containsEntry("exporter", "grpc")
        .containsEntry("server.address", "10.0.0.5:7600");
    assertThat(e.bannerExporter()).isEqualTo("grpc");
  }

  @Test
  @DisplayName("--exporter noop is 18 §5.4's test exporter; a release build is refused")
  void noopIsTheTestExporter(@TempDir Path dir) throws IOException {
    AgentSettings.Exporter e =
        AgentSettings.exporter("noop", agentJar(dir, "0.0.0-SNAPSHOT", false), null);
    assertThat(e.agentConfig()).isEqualTo(Map.of("test.exporter", "noop"));
    assertThat(e.bannerExporter()).isEqualTo("noop");
    Path release = agentJar(dir, "1.0.0", false);
    assertThatThrownBy(() -> AgentSettings.exporter("noop", release, null))
        .isInstanceOf(UsageException.class)
        .hasMessageContaining("T-BLD-010")
        .hasMessageContaining("--exporter drop");
    // drop: the WO-02 P-2 setting, also on a release build
    assertThat(AgentSettings.exporter("drop", release, null).agentConfig())
        .isEqualTo(Map.of("exporter", "noop"));
    assertThatThrownBy(() -> AgentSettings.exporter("file", release, null))
        .isInstanceOf(UsageException.class);
    assertThat(AgentSettings.isReleaseVersion("1.0.0-rc.1")).isTrue();
    // Fails closed like the agent (T-BLD-010): a jar without Implementation-Version is a release
    // build, so the test-only exporter is refused for it.
    assertThat(AgentSettings.isReleaseVersion("unknown")).isTrue();
    assertThat(AgentSettings.isReleaseVersion(" ")).isTrue();
    assertThat(AgentSettings.isReleaseVersion(null)).isTrue();
    assertThat(AgentSettings.isReleaseVersion("0.0.0-SNAPSHOT")).isFalse();
    Path unversioned = agentJarWithoutVersion(dir);
    assertThat(AgentSettings.implementationVersion(unversioned)).isEqualTo("unknown");
    assertThatThrownBy(() -> AgentSettings.exporter("noop", unversioned, null))
        .isInstanceOf(UsageException.class)
        .hasMessageContaining("T-BLD-010");
  }

  private static Path agentJarWithoutVersion(Path dir) throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    Path jar = dir.resolve("argos-agent-unversioned.jar");
    try (OutputStream file = Files.newOutputStream(jar);
        JarOutputStream out = new JarOutputStream(file, manifest)) {
      out.putNextEntry(new JarEntry("inst/io/argos/agent/core/export/Exporters.classdata"));
      out.closeEntry();
    }
    return jar;
  }

  @Test
  @DisplayName("--profile worst profiles every normal request; explicit settings win")
  void profileAndMerge(@TempDir Path dir) throws IOException {
    assertThat(AgentSettings.profile("default")).isEmpty();
    assertThat(AgentSettings.profile("worst"))
        .isEqualTo(Map.of("sampling.profile.normal-rate", "1.0"));
    assertThatThrownBy(() -> AgentSettings.profile("best")).isInstanceOf(UsageException.class);
    AgentSettings.Exporter e =
        AgentSettings.exporter("noop", agentJar(dir, "0.0.0-SNAPSHOT", false), null);
    Map<String, String> merged =
        AgentSettings.merge(
            e,
            AgentSettings.profile("worst"),
            Map.of("sampling.profile.normal-rate", "0.5", "log.level", "info"));
    assertThat(merged)
        .containsEntry("test.exporter", "noop")
        .containsEntry("sampling.profile.normal-rate", "0.5")
        .containsEntry("log.level", "info");
  }
}
