/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.core.log.AgentLogger;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/**
 * JPMS read edges for transformed types of named modules (10 §4.4, DSN-02-03, P-6): advice inlined
 * into {@code java.base}, {@code java.net.http}, {@code java.sql} or an application's named module
 * calls the bootstrap API in the bootstrap class loader's unnamed module. A named module does not
 * read unnamed modules, so without the edge the advice fails with {@code IllegalAccessError}.
 *
 * <p>The edge is added in {@link #onTransformation}, before the JVM defines (or redefines) the
 * class. Each module is handled once: done modules are remembered in a weak, bounded {@link
 * ContextStore} (a module of an application layer must not be pinned). A module that cannot be
 * modified ({@code isModifiableModule} false) gets one WARN; its classes keep their transformation
 * and the advice error handler counts the failures.
 */
final class ModuleReadEdgeListener extends AgentBuilder.Listener.Adapter {
  private final Instrumentation inst;
  private final Object bootstrapModule;
  private final ContextStore<Object, ModuleReadEdgeListener> done =
      ContextStore.of(Object.class, ModuleReadEdgeListener.class);

  ModuleReadEdgeListener(Instrumentation inst) {
    this.inst = inst;
    this.bootstrapModule = Jpms.moduleOf(Argos.class);
  }

  @Override
  public void onTransformation(
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      boolean loaded,
      DynamicType dynamicType) {
    if (!Jpms.SUPPORTED || module == null) { // JavaModule.UNSUPPORTED is null
      return;
    }
    Object target = module.unwrap();
    ensureReads(target);
  }

  /** Makes {@code target} (a {@code java.lang.Module}) read the bootstrap API's module. */
  void ensureReads(Object target) {
    if (target == null || done.get(target) != null || !Jpms.isNamed(target)) {
      return;
    }
    try {
      if (!Jpms.canRead(target, bootstrapModule)) {
        if (!Jpms.isModifiable(inst, target)) {
          AgentLogger.warnOnce(
              "read-edge-" + Jpms.name(target),
              "module {} cannot be modified; advice inlined into it cannot reach the Argos"
                  + " bootstrap classes",
              Jpms.name(target));
          return;
        }
        Jpms.addReads(inst, target, bootstrapModule);
        AgentLogger.debug("added read edge {} -> Argos bootstrap", Jpms.name(target));
      }
      done.put(target, this);
    } catch (RuntimeException e) {
      AgentLogger.warnOnce(
          "read-edge-error-" + Jpms.name(target),
          "adding a read edge from module {} failed: {}",
          Jpms.name(target),
          e.toString());
    }
  }
}
