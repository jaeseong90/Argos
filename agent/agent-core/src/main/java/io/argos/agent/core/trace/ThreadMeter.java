/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * CPU time and allocated bytes of the current thread (10 §9.2 step 1, D-23, C-4): the per
 * transaction {@code cpu_time} and {@code alloc_bytes} are differences of these values at begin and
 * end. A value that cannot be measured is -1, and the transaction then carries no value at all
 * (missing is not zero).
 *
 * <p>{@link #NONE} measures nothing; {@link #jvm()} uses {@link ThreadMXBean} ({@code
 * getCurrentThreadCpuTime}) and {@code com.sun.management.ThreadMXBean} ({@code
 * getCurrentThreadAllocatedBytes} on JDK 14+, {@code getThreadAllocatedBytes(id)} before), found
 * through method handles because the agent targets Java 8. {@link #jvm()} loads the management
 * classes, so the tracer calls it on an agent thread after premain (N-30); transactions that end
 * before that have no CPU or allocation value.
 */
public abstract class ThreadMeter {
  /** Measures nothing (every value -1). */
  public static final ThreadMeter NONE = new None();

  ThreadMeter() {}

  /** CPU nanoseconds of the current thread, or -1. */
  public abstract long cpuNanos();

  /** Bytes allocated by the current thread so far, or -1. */
  public abstract long allocatedBytes();

  /** True when either value can be measured. */
  public abstract boolean measures();

  /**
   * The JVM's thread meter: CPU time when {@code isCurrentThreadCpuTimeSupported} and enabled,
   * allocated bytes when the HotSpot extension is present and enabled; {@link #NONE} when neither.
   * Never throws.
   */
  public static ThreadMeter jvm() {
    try {
      ThreadMXBean bean = ManagementFactory.getThreadMXBean();
      boolean cpu = bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled();
      MethodHandle alloc = allocationHandle(bean);
      if (!cpu && alloc == null) {
        return NONE;
      }
      return new Jvm(cpu ? bean : null, alloc);
    } catch (Throwable t) {
      return NONE;
    }
  }

  /**
   * A handle {@code () -> long} of the current thread's allocated bytes, or null: {@code
   * com.sun.management.ThreadMXBean} is looked up in the class loader of the bean (the boot loader
   * on Java 8, the loader of {@code jdk.management} on 9+).
   */
  private static MethodHandle allocationHandle(ThreadMXBean bean) {
    try {
      Class<?> type =
          Class.forName("com.sun.management.ThreadMXBean", false, bean.getClass().getClassLoader());
      if (!type.isInstance(bean)) {
        return null;
      }
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      boolean supported =
          (boolean)
              lookup
                  .findVirtual(
                      type,
                      "isThreadAllocatedMemorySupported",
                      MethodType.methodType(boolean.class))
                  .invoke(bean);
      boolean enabled =
          (boolean)
              lookup
                  .findVirtual(
                      type, "isThreadAllocatedMemoryEnabled", MethodType.methodType(boolean.class))
                  .invoke(bean);
      if (!supported || !enabled) {
        return null;
      }
      try {
        return lookup
            .findVirtual(type, "getCurrentThreadAllocatedBytes", MethodType.methodType(long.class))
            .bindTo(bean);
      } catch (NoSuchMethodException e) {
        MethodHandle byId =
            lookup
                .findVirtual(
                    type, "getThreadAllocatedBytes", MethodType.methodType(long.class, long.class))
                .bindTo(bean);
        MethodHandle currentId =
            lookup.findVirtual(Thread.class, "getId", MethodType.methodType(long.class));
        MethodHandle currentThread =
            lookup.findStatic(Thread.class, "currentThread", MethodType.methodType(Thread.class));
        return MethodHandles.filterReturnValue(
            MethodHandles.filterReturnValue(currentThread, currentId), byId);
      }
    } catch (Throwable t) {
      return null;
    }
  }

  private static final class None extends ThreadMeter {
    @Override
    public long cpuNanos() {
      return -1L;
    }

    @Override
    public long allocatedBytes() {
      return -1L;
    }

    @Override
    public boolean measures() {
      return false;
    }
  }

  private static final class Jvm extends ThreadMeter {
    private final ThreadMXBean cpu;
    private final MethodHandle alloc;

    Jvm(ThreadMXBean cpu, MethodHandle alloc) {
      this.cpu = cpu;
      this.alloc = alloc;
    }

    @Override
    public long cpuNanos() {
      if (cpu == null) {
        return -1L;
      }
      try {
        long value = cpu.getCurrentThreadCpuTime();
        return value < 0 ? -1L : value;
      } catch (RuntimeException e) {
        return -1L;
      }
    }

    @Override
    public long allocatedBytes() {
      if (alloc == null) {
        return -1L;
      }
      try {
        long value = (long) alloc.invokeExact();
        return value < 0 ? -1L : value;
      } catch (Throwable e) {
        return -1L;
      }
    }

    @Override
    public boolean measures() {
      return true;
    }
  }
}
