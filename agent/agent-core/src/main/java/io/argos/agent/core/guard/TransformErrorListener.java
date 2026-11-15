/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.guard;

import io.argos.agent.core.log.AgentLogger;
import java.util.concurrent.atomic.AtomicLong;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.utility.JavaModule;

/**
 * Transformation failures (10 §14 "변환 실패", F-A15, T-AG-041): the class stays unchanged (ByteBuddy
 * returns the original bytes), the failure is recorded for {@code debug.transform-log} (T-AG-005)
 * and counted by {@link SelfProtection} against the modules that were transforming the type; a
 * module reaching {@code instrumentation.transform-error-threshold} (10) is disabled. A {@code
 * ClassCircularityError} is counted with the reason {@code circularity}. The first {@value
 * #LOGGED_ERRORS} failures are logged at WARN with the class name and the exception class, the full
 * failure at DEBUG.
 */
public final class TransformErrorListener extends AgentBuilder.Listener.Adapter {
  static final int LOGGED_ERRORS = 20;

  /** Where failures are recorded and which modules transform the current type. */
  public interface Recorder {
    /** The module bits of the type being transformed on this thread, cleared. */
    long takeModules();

    /** Records one failure for the transform log. */
    void recordError(String typeName, ClassLoader classLoader, long moduleBits, Throwable error);

    /** A short name of {@code classLoader} ({@code bootstrap}, {@code app}, ...). */
    String loaderName(ClassLoader classLoader);
  }

  private final SelfProtection protection;
  private final Recorder recorder;
  private final AtomicLong errors = new AtomicLong();
  private final AtomicLong circularity = new AtomicLong();

  public TransformErrorListener(SelfProtection protection, Recorder recorder) {
    this.protection = protection;
    this.recorder = recorder;
  }

  @Override
  public void onError(
      String typeName,
      ClassLoader classLoader,
      JavaModule module,
      boolean loaded,
      Throwable throwable) {
    long bits = recorder.takeModules();
    long count = errors.incrementAndGet();
    boolean circular = throwable instanceof ClassCircularityError;
    if (circular) {
      circularity.incrementAndGet();
    }
    recorder.recordError(typeName, classLoader, bits, throwable);
    if (count <= LOGGED_ERRORS) {
      AgentLogger.warn(
          "transformation of {} ({}) failed, class left unchanged: {}",
          typeName,
          recorder.loaderName(classLoader),
          throwable == null ? "unknown" : throwable.getClass().getName());
      if (AgentLogger.isDebugEnabled()) {
        AgentLogger.debug("transformation failure of {}", typeName, throwable);
      }
    }
    protection.transformErrors(bits, circular);
  }

  /** Failures seen so far. */
  public long errors() {
    return errors.get();
  }

  /** {@code ClassCircularityError} failures seen so far. */
  public long circularityErrors() {
    return circularity.get();
  }
}
