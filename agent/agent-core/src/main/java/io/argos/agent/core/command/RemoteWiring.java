/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.AllowList;
import io.argos.agent.core.config.ConfigInputs;
import io.argos.agent.core.config.ConfigLoader;
import io.argos.agent.core.config.EffectiveConfig;
import io.argos.agent.core.config.LoadedConfig;
import io.argos.agent.core.config.RuntimeConfig;
import io.argos.agent.core.export.ExporterContext;
import io.argos.agent.core.guard.RetransformLimiter;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.trace.Sampler;
import io.argos.agent.core.transport.AgentSession;
import io.argos.proto.v1.AgentEventType;
import io.argos.proto.v1.CommandType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Builds the remote control of the running agent (WO-17) from the exporter context, on {@code
 * argos-sender} when the gRPC transport starts (never on the premain thread, T-NFR-30a): the {@link
 * RuntimeConfig} (installed as {@link RuntimeConfig#current()}), the {@link SamplingControl} the
 * transport's throttle shares, the {@link RuntimeApplier}, the {@link ConfigPushApplier}, the
 * command handlers ({@link Handlers#create} and {@link ReloadCommand}) and the {@link
 * CommandDispatcher} with its lazily started {@code argos-command} thread. The configuration hash
 * and {@code AgentEvent}s go to {@link AgentSession#current()} at the time they happen.
 */
public final class RemoteWiring {
  private final CommandDispatcher dispatcher;
  private final SamplingControl sampling;
  private final RuntimeConfig config;

  private RemoteWiring(
      CommandDispatcher dispatcher, SamplingControl sampling, RuntimeConfig config) {
    this.dispatcher = dispatcher;
    this.sampling = sampling;
    this.config = config;
  }

  /** The dispatcher the session hands commands and configuration to. */
  public CommandDispatcher dispatcher() {
    return dispatcher;
  }

  /** The profile sampler control (configured sampler and the server's throttle cap). */
  public SamplingControl sampling() {
    return sampling;
  }

  /** The configuration in force. */
  public RuntimeConfig config() {
    return config;
  }

  /**
   * The remote control of {@code context} (its runtime must be set).
   *
   * @param clockOffsetMillis the transport's clock offset to the server (self-diagnostics), may be
   *     null
   * @param extras transport values for self-diagnostics (session state), may be null
   */
  public static RemoteWiring create(
      ExporterContext context,
      LongSupplier clockOffsetMillis,
      Supplier<Map<String, Object>> extras) {
    LoadedConfig loaded = context.loaded();
    if (loaded == null) {
      loaded =
          new LoadedConfig(
              context.config(),
              new EffectiveConfig(Collections.<String, EffectiveConfig.Entry>emptyMap()),
              context.config().serverToken(),
              Collections.<String>emptyList());
    }
    final RuntimeConfig config = new RuntimeConfig(loaded);
    RuntimeConfig.install(config);
    SamplingControl sampling = new SamplingControl(context.runtime(), Sampler.of(config.config()));
    RuntimeApplier.Events events =
        new RuntimeApplier.Events() {
          @Override
          public void event(AgentEventType type, String module, String detail) {
            AgentSession session = AgentSession.current();
            if (session != null) {
              session.event(type, module, detail);
            }
          }
        };
    RuntimeApplier runtime =
        new RuntimeApplier(
            context.control(),
            context.moduleNames(),
            loaded.config(),
            context.runtime(),
            sampling,
            context.maxDurationEnforcer(),
            events);
    // SEC-25: the policy of the local custom methods; forbidden items are ignored with a WARN.
    CustomMethodPolicy policy = CustomMethodPolicy.of(loaded.config());
    CustomMethodPolicy.install(policy);
    policy.warnIgnored("local configuration:");
    final Retransformer retransformer =
        new Retransformer(context.instrumentation(), new RetransformLimiter(), NANO_CLOCK);
    final Supplier<Map<String, Object>> transportExtras = extras;
    Supplier<Map<String, Object>> allExtras =
        new Supplier<Map<String, Object>>() {
          @Override
          public Map<String, Object> get() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            Map<String, Object> transport = transportExtras == null ? null : transportExtras.get();
            if (transport != null) {
              out.putAll(transport);
            }
            out.putAll(retransformer.diagnostics());
            return out;
          }
        };
    ConfigPushApplier applier =
        new ConfigPushApplier(
            config,
            runtime,
            new ConfigPushApplier.HashSink() {
              @Override
              public void configHash(String hash) {
                AgentSession session = AgentSession.current();
                if (session != null) {
                  session.configHash(hash);
                }
              }
            },
            NANO_CLOCK,
            context.control(),
            retransformer);
    Supplier<AgentConfig> current =
        new Supplier<AgentConfig>() {
          @Override
          public AgentConfig get() {
            return config.config();
          }
        };
    CommandEnv env =
        CommandEnv.builder()
            .info(context.agent())
            .runtime(context.runtime())
            .diagnostics(context.diagnostics())
            .logDir(AgentLogger.logDirectory())
            .config(current)
            .effective(
                new Supplier<EffectiveConfig>() {
                  @Override
                  public EffectiveConfig get() {
                    return config.effective();
                  }
                })
            .allow(
                new Supplier<AllowList>() {
                  @Override
                  public AllowList get() {
                    return config.allow();
                  }
                })
            .configHash(
                new Supplier<String>() {
                  @Override
                  public String get() {
                    return config.configHash();
                  }
                })
            .clockOffsetMillis(clockOffsetMillis)
            .extras(allExtras)
            .build();
    Map<CommandType, CommandHandler> handlers =
        new EnumMap<CommandType, CommandHandler>(CommandType.class);
    handlers.putAll(Handlers.create(env));
    final ConfigInputs inputs = context.configInputs();
    if (inputs != null) {
      handlers.put(
          CommandType.CONFIG_RELOAD,
          new ReloadCommand(
              applier,
              new ReloadCommand.Source() {
                @Override
                public LoadedConfig load() {
                  return ConfigLoader.load(inputs);
                }
              },
              events));
    }
    CommandDispatcher dispatcher =
        new CommandDispatcher(handlers, applier, current, NANO_CLOCK, THREADS);
    return new RemoteWiring(dispatcher, sampling, config);
  }

  private static final LongSupplier NANO_CLOCK =
      new LongSupplier() {
        @Override
        public long getAsLong() {
          return System.nanoTime();
        }
      };

  private static final ThreadFactory THREADS =
      new ThreadFactory() {
        @Override
        public Thread newThread(Runnable task) {
          return new ArgosThread(task, CommandDispatcher.THREAD_NAME);
        }
      };
}
