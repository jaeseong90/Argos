/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import java.lang.instrument.Instrumentation;
import java.util.List;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Assembles and installs the agent builder (10 §4.3 P6, §5.2):
 *
 * <ul>
 *   <li>advice inlining only, no class format changes (methods and fields are never added; field
 *       injection for not-yet-loaded types arrives with WO-10/WO-15);
 *   <li>retransformation of already loaded classes (JDK allow-list classes such as {@code
 *       ThreadPoolExecutor}, P-6); no initialization or injection strategy ({@link
 *       ArgosAgentBuilder}: no auxiliary types, no {@code Nexus}, ByteBuddy never injects classes
 *       and never reaches its {@code sun.misc.Unsafe} code);
 *   <li>the ignore list ({@link IgnoreMatchers}), JPMS read edges ({@link ModuleReadEdgeListener}),
 *       transformation recording and error accounting;
 *   <li>per module and type instrumentation: the module's witness, evaluated once per class loader
 *       ({@link WitnessMatcher}), then {@link ModuleTransformer}.
 * </ul>
 */
final class InstrumentationInstaller {
  private InstrumentationInstaller() {}

  static ResettableClassFileTransformer install(
      Instrumentation inst,
      ClassLoader agentLoader,
      List<InstrumentationModule> modules,
      List<String> excludeClasses,
      AgentBuilder.Listener recorder,
      AgentBuilder.Listener errors,
      HelperInjector helpers,
      LoaderState loaderState) {
    long start = System.nanoTime();
    AdviceFactory advices = new AdviceFactory(agentLoader);
    AgentBuilder builder =
        ArgosAgentBuilder.create(
                new ByteBuddy().with(TypeValidation.DISABLED),
                new AgentBuilder.RawMatcher.ForElementMatchers(
                    IgnoreMatchers.types(excludeClasses), IgnoreMatchers.loaders(agentLoader)))
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(new PremainDiscovery())
            .with(new ModuleReadEdgeListener(inst))
            .with(recorder)
            .with(errors);
    int types = 0;
    for (InstrumentationModule module : modules) {
      ElementMatcher<ClassLoader> witness =
          new WitnessMatcher(module.moduleId(), module.witness(), loaderState);
      for (TypeInstrumentation type : module.types()) {
        builder =
            builder
                .type(type.typeMatcher(), witness)
                .transform(new ModuleTransformer(module, type, advices, helpers));
        types++;
      }
    }
    long built = System.nanoTime();
    ResettableClassFileTransformer transformer = builder.installOn(inst);
    long installed = System.nanoTime();
    AgentLogger.debug(
        "agent builder: {} module(s), {} type rule(s); build {} ms, installOn {} ms",
        modules.size(),
        types,
        (built - start) / 1_000_000L,
        (installed - built) / 1_000_000L);
    return transformer;
  }

  /**
   * A module's witness, evaluated once per class loader and cached in {@link LoaderState} (10 §5.2
   * step 2). A witness that throws counts as "not matched" for that loader.
   */
  static final class WitnessMatcher extends ElementMatcher.Junction.AbstractBase<ClassLoader> {
    private final int moduleId;
    private final ElementMatcher<ClassLoader> witness;
    private final LoaderState state;

    WitnessMatcher(int moduleId, ElementMatcher<ClassLoader> witness, LoaderState state) {
      this.moduleId = moduleId;
      this.witness = witness;
      this.state = state;
    }

    @Override
    public boolean matches(ClassLoader loader) {
      if (state.has(loader, LoaderState.WITNESS_EVALUATED, moduleId)) {
        return state.has(loader, LoaderState.WITNESS_MATCHED, moduleId);
      }
      boolean matched;
      try {
        matched = witness.matches(loader);
      } catch (Throwable t) {
        matched = false;
      }
      if (matched) {
        state.add(loader, LoaderState.WITNESS_MATCHED, moduleId);
      }
      state.add(loader, LoaderState.WITNESS_EVALUATED, moduleId);
      return matched;
    }

    @Override
    public String toString() {
      return "witness(" + moduleId + ": " + witness + ")";
    }
  }
}
