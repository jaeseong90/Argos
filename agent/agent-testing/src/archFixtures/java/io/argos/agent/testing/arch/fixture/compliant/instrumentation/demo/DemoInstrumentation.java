/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.compliant.instrumentation.api.InstrumentationModule;
import io.argos.agent.testing.arch.fixture.thirdparty.ThirdPartyQueue;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;

/**
 * Module definition: runs in the agent class loader, so it may use ByteBuddy and other shaded
 * libraries (B-4 restricts only advice and helper classes).
 */
public final class DemoInstrumentation implements InstrumentationModule {
  static final int MODULE_ID = 7;

  @Override
  public String id() {
    return "demo";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public List<String> helperClassNames() {
    return Arrays.asList(DemoHelper.class.getName());
  }

  public AsmVisitorWrapper advice() {
    return Advice.to(DemoAdvice.class).on(net.bytebuddy.matcher.ElementMatchers.named("service"));
  }

  public boolean queue(ThirdPartyQueue queue) {
    return queue.offer(id());
  }
}
