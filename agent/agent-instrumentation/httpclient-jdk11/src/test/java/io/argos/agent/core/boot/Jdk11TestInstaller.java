/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.instrumentation.api.InstrumentationModule;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/**
 * Installs one instrumentation module with the core's own installer (the agent builder of 10 §5.2,
 * ignore list, JPMS read edges, helper injection into the target class loader, retransformation),
 * for module tests that instrument real JDK classes. The test class loader plays the agent class
 * loader: it serves the advice and helper class files. In this package because the installer is
 * package-private.
 */
public final class Jdk11TestInstaller {
  /** Transformed type names and transformation errors, in order. */
  public static final class Result {
    public final List<String> transformed = Collections.synchronizedList(new ArrayList<String>());
    public final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
  }

  private Jdk11TestInstaller() {}

  public static Result install(Instrumentation inst, InstrumentationModule module) {
    ClassLoader loader = Jdk11TestInstaller.class.getClassLoader();
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
            result.errors.add(throwable);
          }
        };
    LoaderState state = new LoaderState();
    InstrumentationInstaller.install(
        inst,
        loader,
        Collections.singletonList(module),
        Collections.<String>emptyList(),
        recorder,
        AgentBuilder.Listener.NoOp.INSTANCE,
        new HelperInjector(inst, loader, state, TestFaults.NONE),
        state,
        TestFaults.NONE);
    return result;
  }
}
