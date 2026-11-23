/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

/**
 * The HotSpot extensions of the {@link OperatingSystemMXBean} ({@code
 * com.sun.management.OperatingSystemMXBean}, {@code com.sun.management.UnixOperatingSystemMXBean}),
 * read through method handles found once (the agent targets Java 8 and a JVM may lack them). The
 * interfaces are looked up in the class loader of the bean (the boot loader on Java 8, the loader
 * of {@code jdk.management} on 9+), only public exported interface methods are used ({@link
 * MethodHandles#publicLookup()}), and every value of a missing method is -1 (missing, C-4). {@code
 * getCpuLoad} (JDK 14+) is preferred over the deprecated {@code getSystemCpuLoad}.
 */
final class OsBean {
  static final OsBean NONE = new OsBean(null, null, null, null, null, null, null);

  private final MethodHandle processCpuTime;
  private final MethodHandle processCpuLoad;
  private final MethodHandle systemCpuLoad;
  private final MethodHandle openFds;
  private final MethodHandle maxFds;
  private final MethodHandle totalMemory;
  private final MethodHandle freeMemory;

  private OsBean(
      MethodHandle processCpuTime,
      MethodHandle processCpuLoad,
      MethodHandle systemCpuLoad,
      MethodHandle openFds,
      MethodHandle maxFds,
      MethodHandle totalMemory,
      MethodHandle freeMemory) {
    this.processCpuTime = processCpuTime;
    this.processCpuLoad = processCpuLoad;
    this.systemCpuLoad = systemCpuLoad;
    this.openFds = openFds;
    this.maxFds = maxFds;
    this.totalMemory = totalMemory;
    this.freeMemory = freeMemory;
  }

  /** The handles of this JVM's operating system bean; {@link #NONE} when it has none. */
  static OsBean jvm() {
    try {
      OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
      ClassLoader loader = bean.getClass().getClassLoader();
      Class<?> hotspot = type("com.sun.management.OperatingSystemMXBean", loader, bean);
      Class<?> unix = type("com.sun.management.UnixOperatingSystemMXBean", loader, bean);
      MethodHandle cpuLoad = handle(hotspot, bean, "getCpuLoad", double.class);
      if (cpuLoad == null) {
        cpuLoad = handle(hotspot, bean, "getSystemCpuLoad", double.class);
      }
      return new OsBean(
          handle(hotspot, bean, "getProcessCpuTime", long.class),
          handle(hotspot, bean, "getProcessCpuLoad", double.class),
          cpuLoad,
          handle(unix, bean, "getOpenFileDescriptorCount", long.class),
          handle(unix, bean, "getMaxFileDescriptorCount", long.class),
          handle(hotspot, bean, "getTotalPhysicalMemorySize", long.class),
          handle(hotspot, bean, "getFreePhysicalMemorySize", long.class));
    } catch (Throwable t) {
      return NONE;
    }
  }

  /** Fixed values (tests); -1 is a missing value. */
  static OsBean fixed(
      long processCpuNanos, double processLoad, double systemLoad, long openFds, long maxFds) {
    return new OsBean(
        MethodHandles.constant(long.class, processCpuNanos),
        MethodHandles.constant(double.class, processLoad),
        MethodHandles.constant(double.class, systemLoad),
        MethodHandles.constant(long.class, openFds),
        MethodHandles.constant(long.class, maxFds),
        null,
        null);
  }

  /**
   * Process CPU time read from {@code cpuNanos}, the first-collection load {@code load} (tests).
   */
  static OsBean processCpu(java.util.concurrent.atomic.AtomicLong cpuNanos, double load) {
    try {
      MethodHandle get =
          MethodHandles.publicLookup()
              .findVirtual(
                  java.util.concurrent.atomic.AtomicLong.class,
                  "get",
                  MethodType.methodType(long.class))
              .bindTo(cpuNanos);
      return new OsBean(
          get, MethodHandles.constant(double.class, load), null, null, null, null, null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Class<?> type(String name, ClassLoader loader, Object bean) {
    try {
      Class<?> type = Class.forName(name, false, loader);
      return type.isInstance(bean) ? type : null;
    } catch (Throwable t) {
      return null;
    }
  }

  /** {@code () -> returnType} bound to {@code bean}, or null. */
  private static MethodHandle handle(
      Class<?> type, Object bean, String method, Class<?> returnType) {
    if (type == null) {
      return null;
    }
    try {
      return MethodHandles.publicLookup()
          .findVirtual(type, method, MethodType.methodType(returnType))
          .bindTo(bean);
    } catch (Throwable t) {
      return null;
    }
  }

  /** CPU nanoseconds of the process, or -1. */
  long processCpuNanos() {
    return longValue(processCpuTime);
  }

  /** The JVM's recent CPU load of the process (0..1), or -1. */
  double processCpuLoad() {
    return doubleValue(processCpuLoad);
  }

  /** The JVM's recent CPU load of the whole system (0..1), or -1. */
  double systemCpuLoad() {
    return doubleValue(systemCpuLoad);
  }

  /** Open file descriptors (Unix), or -1. */
  long openFds() {
    return longValue(openFds);
  }

  /** Maximum file descriptors (Unix), or -1. */
  long maxFds() {
    return longValue(maxFds);
  }

  /** Physical memory of the machine, or -1. */
  long totalPhysicalMemory() {
    return longValue(totalMemory);
  }

  /** Free physical memory of the machine, or -1. */
  long freePhysicalMemory() {
    return longValue(freeMemory);
  }

  private static long longValue(MethodHandle handle) {
    if (handle == null) {
      return -1;
    }
    try {
      return (long) handle.invoke();
    } catch (Throwable t) {
      return -1;
    }
  }

  private static double doubleValue(MethodHandle handle) {
    if (handle == null) {
      return -1;
    }
    try {
      double value = (double) handle.invoke();
      return Double.isNaN(value) ? -1 : value;
    } catch (Throwable t) {
      return -1;
    }
  }
}
