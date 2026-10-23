/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.core;

import io.argos.agent.testing.arch.fixture.compliant.bootstrap.TraceContext;
import io.argos.agent.testing.arch.fixture.compliant.instrumentation.api.InstrumentationModule;
import io.argos.agent.testing.arch.fixture.thirdparty.ThirdPartyQueue;
import java.util.List;

/** Core code may use shaded libraries (B-4) and the instrumentation API only (B-3). */
public final class CoreService {
  private final ThirdPartyQueue queue = new ThirdPartyQueue();

  public int install(List<InstrumentationModule> modules) {
    int count = 0;
    for (InstrumentationModule module : modules) {
      if (module.moduleId() >= 0) {
        count++;
      }
    }
    return count;
  }

  public boolean submit(TraceContext context) {
    return queue.offer(context);
  }
}
