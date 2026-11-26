/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.core.command;

import java.lang.reflect.Method;

/**
 * ST-91-1 compliant (16 §1.4): reflection on a JDK type whose class and method names are written in
 * the agent's source, as THREAD_DUMP does for {@code HotSpotDiagnosticMXBean.dumpThreads} on JDK
 * 21+. The names reach the lookup through a local variable, which the rule follows.
 */
public final class JdkDumpLookup {
  public Object dump(Object bean, String file) throws Exception {
    String typeName = "com.sun.management.HotSpotDiagnosticMXBean";
    Class<?> type = Class.forName(typeName);
    Class<?> format = Class.forName("com.sun.management.HotSpotDiagnosticMXBean$ThreadDumpFormat");
    Method method = type.getMethod("dumpThreads", String.class, format);
    return method.invoke(bean, file, null);
  }
}
