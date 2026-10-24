/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.diag;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** JVM facts for GET /api/runtime, readable from Java 8 code. */
public final class JavaRuntime {

  private static final Method IS_VIRTUAL = findIsVirtual();

  private JavaRuntime() {}

  /** Whether the current thread is a virtual thread (always false before JDK 21). */
  public static boolean currentThreadIsVirtual() {
    if (IS_VIRTUAL == null) {
      return false;
    }
    try {
      return (Boolean) IS_VIRTUAL.invoke(Thread.currentThread());
    } catch (ReflectiveOperationException e) {
      return false;
    }
  }

  /** Major Java version: 8, 11, 17, 21, 25. */
  public static int featureVersion() {
    String version = System.getProperty("java.specification.version", "0");
    if (version.startsWith("1.")) {
      version = version.substring(2);
    }
    int dot = version.indexOf('.');
    return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
  }

  /** java.version, vendor, VM name, loaded class counts and uptime. */
  public static Map<String, Object> describe() {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("javaVersion", System.getProperty("java.version"));
    info.put("javaFeatureVersion", featureVersion());
    info.put("javaVendor", System.getProperty("java.vendor"));
    info.put("vmName", System.getProperty("java.vm.name"));
    ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
    info.put("loadedClassCount", classes.getLoadedClassCount());
    info.put("totalLoadedClassCount", classes.getTotalLoadedClassCount());
    info.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
    info.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    return info;
  }

  private static Method findIsVirtual() {
    try {
      return Thread.class.getMethod("isVirtual");
    } catch (NoSuchMethodException e) {
      return null;
    }
  }
}
