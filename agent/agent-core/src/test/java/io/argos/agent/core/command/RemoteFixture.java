/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.KillSwitch;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.core.config.ConfigInputs;
import io.argos.agent.core.config.ConfigLoader;
import io.argos.agent.core.config.LoadedConfig;
import io.argos.agent.core.config.RuntimeConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.masking.Masker;
import io.argos.agent.core.trace.Sampler;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.watch.MaxDurationEnforcer;
import io.argos.proto.v1.AgentEventType;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The remote configuration path of a running agent for unit tests: a local configuration loaded
 * from a yml (and {@code -Dargos.*} values) like at start, the bootstrap capability, a recording
 * runtime, two installed modules ({@code servlet} id 3, {@code jdbc} id 5) and recorded events and
 * hashes. {@link #close()} restores the JVM-wide state it touched (kill switch, module states,
 * capture modes, masker, {@link RuntimeConfig#current()}).
 */
final class RemoteFixture implements AutoCloseable {
  static final int SERVLET = 3;
  static final int JDBC = 5;

  final File yml;
  final Map<String, String> properties;
  final AgentControl control = TestControls.newControl();
  final List<String> events = new ArrayList<String>();
  final List<String> hashes = new ArrayList<String>();
  final AtomicLong nanos = new AtomicLong(5_000_000_000L);
  LoadedConfig loaded;
  RuntimeConfig config;
  TraceRuntime runtime;
  SamplingControl sampling;
  MaxDurationEnforcer enforcer;
  RuntimeApplier applier;
  ConfigPushApplier pushes;

  private final int[] savedStates = new int[ModuleHealth.MAX_MODULES];

  RemoteFixture(File dir, Map<String, String> properties, String... ymlLines) throws IOException {
    for (int i = 0; i < savedStates.length; i++) {
      savedStates[i] = ModuleHealth.state(i);
    }
    this.yml = new File(dir, "argos-agent.yml");
    this.properties = new HashMap<String, String>(properties);
    writeYml(ymlLines);
    loaded = load();
    control.setModuleState(SERVLET, ModuleHealth.ENABLED);
    control.setModuleState(JDBC, ModuleHealth.ENABLED);
    config = new RuntimeConfig(loaded);
    runtime = TraceRuntime.create(loaded.config(), new AgentCounters(), control);
    sampling = new SamplingControl(runtime, Sampler.of(loaded.config()));
    enforcer =
        new MaxDurationEnforcer(
            runtime.activeRegistry(),
            runtime.tracerImpl(),
            null,
            loaded.config().transactionMaxDurationMs());
    String[] names = new String[64];
    names[SERVLET] = "servlet";
    names[JDBC] = "jdbc";
    applier =
        new RuntimeApplier(
            control,
            names,
            loaded.config(),
            runtime,
            sampling,
            enforcer,
            new RuntimeApplier.Events() {
              @Override
              public void event(AgentEventType type, String module, String detail) {
                events.add(type.name());
              }
            });
    pushes =
        new ConfigPushApplier(
            config,
            applier,
            new ConfigPushApplier.HashSink() {
              @Override
              public void configHash(String hash) {
                hashes.add(hash);
              }
            },
            clock(),
            control);
  }

  /** Required keys, so that the configuration has no errors. */
  static Map<String, String> required() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("argos.server.address", "collector.example:9100");
    values.put("argos.app.name", "orders");
    values.put("argos.app.env", "test");
    return values;
  }

  LongSupplier clock() {
    return new LongSupplier() {
      @Override
      public long getAsLong() {
        return nanos.get();
      }
    };
  }

  void writeYml(String... lines) throws IOException {
    Files.write(yml.toPath(), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
  }

  /** {@code ConfigLoader.load} of the yml and the properties, as at start. */
  LoadedConfig load() {
    return ConfigLoader.load(
        new ConfigInputs(yml, properties, Collections.<String, String>emptyMap(), false));
  }

  /** A {@code CONFIG_RELOAD} handler over this fixture. */
  ReloadCommand reloadCommand() {
    return new ReloadCommand(
        pushes,
        new ReloadCommand.Source() {
          @Override
          public LoadedConfig load() {
            return RemoteFixture.this.load();
          }
        },
        new RuntimeApplier.Events() {
          @Override
          public void event(AgentEventType type, String module, String detail) {
            events.add(type.name());
          }
        });
  }

  @Override
  public void close() {
    KillSwitch.ENABLED = true;
    for (int i = 0; i < savedStates.length; i++) {
      control.setModuleState(i, savedStates[i]);
    }
    control.setCapture(null);
    Masker.install(Masker.defaults());
    RuntimeConfig.install(null);
  }
}
