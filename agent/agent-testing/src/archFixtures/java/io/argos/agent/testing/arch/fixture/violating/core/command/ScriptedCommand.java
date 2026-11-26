/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.core.command;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import javax.script.ScriptEngineManager;

/**
 * ST-91-1 violations (16 §1.4): a command handler that turns data from the server into code. Every
 * method is a separate way the rule must catch.
 */
public final class ScriptedCommand {
  public Class<?> load(String className) throws ClassNotFoundException {
    return Class.forName(className);
  }

  public Class<?> loadWith(String className, ClassLoader loader) throws ClassNotFoundException {
    return loader.loadClass(className);
  }

  public Method method(Class<?> type, String name) throws NoSuchMethodException {
    return type.getMethod(name);
  }

  public Method[] members(Class<?> type) {
    return type.getDeclaredMethods();
  }

  public MethodHandle handle(String name) throws ReflectiveOperationException {
    return MethodHandles.lookup()
        .findStatic(ScriptedCommand.class, name, MethodType.methodType(void.class));
  }

  public Object script(String source) throws Exception {
    return new ScriptEngineManager().getEngineByName("javascript").eval(source);
  }

  public Process run(String command) throws java.io.IOException {
    return Runtime.getRuntime().exec(command);
  }

  public Process start(String command) throws java.io.IOException {
    return new ProcessBuilder(command).start();
  }
}
