/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import net.bytebuddy.asm.Advice;

/** T-AG-003 violation: BlockingQueue.put/take block the application thread. */
public final class QueueTakeAdvice {
  private static final int MODULE_ID = 9;
  private static final BlockingQueue<Object> QUEUE = new ArrayBlockingQueue<>(16);

  private QueueTakeAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) throws InterruptedException {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    QUEUE.put(request);
    DemoHelper.record(QUEUE.take());
  }
}
