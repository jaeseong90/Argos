/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.instrumentation.api.InstrumentationModule;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/**
 * Installs one instrumentation module with the core's own installer (the agent builder of 10 §5.2:
 * ignore list, helper injection into the target loader, advice inlining), for module tests that
 * instrument real library classes on the test class path. In this package because the installer is
 * package-private.
 *
 * <p>The agent class loader is a resource-only child of the test class loader: it serves the advice
 * and helper class files as the agent jar does, and being a different loader keeps the libraries on
 * the test class path out of the installer's ignore rule for the agent's own loader.
 */
public final class SchedulerTestInstaller {
  /** Transformed type names and transformation errors, in order. */
  public static final class Result {
    public final List<String> transformed = Collections.synchronizedList(new ArrayList<String>());
    public final List<String> errors = Collections.synchronizedList(new ArrayList<String>());
  }

  private SchedulerTestInstaller() {}

  public static Result install(Instrumentation inst, InstrumentationModule module) {
    ClassLoader agentLoader =
        new URLClassLoader(new URL[0], SchedulerTestInstaller.class.getClassLoader());
    final Result result = new Result();
    AgentBuilder.Listener recorder =
        new AgentBuilder.Listener.Adapter() {
          @Override
          public void onTransformation(
              TypeDescription typeDescription,
              ClassLoader classLoader,
              JavaModule javaModule,
              boolean loaded,
              DynamicType dynamicType) {
            result.transformed.add(typeDescription.getName());
          }

          @Override
          public void onError(
              String typeName,
              ClassLoader classLoader,
              JavaModule javaModule,
              boolean loaded,
              Throwable throwable) {
            result.errors.add(typeName + ": " + throwable);
          }
        };
    LoaderState state = new LoaderState();
    InstrumentationInstaller.install(
        inst,
        agentLoader,
        Collections.singletonList(module),
        Collections.<String>emptyList(),
        recorder,
        AgentBuilder.Listener.NoOp.INSTANCE,
        new HelperInjector(inst, agentLoader, state, TestFaults.NONE),
        state,
        TestFaults.NONE);
    return result;
  }
}
