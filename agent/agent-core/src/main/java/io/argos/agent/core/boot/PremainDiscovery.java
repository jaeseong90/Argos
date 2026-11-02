/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;

/**
 * Which already loaded classes the agent retransforms at premain (10 §4.3 P6: "이미 로드된 JDK 허용목록
 * 클래스에만"): only modifiable classes on the JDK allow-list ({@link IgnoreMatchers#isJdkAllowlisted}).
 * Application classes are not loaded yet at premain, so every other class is transformed when it is
 * loaded. Filtering by name here keeps ByteBuddy from describing and matching every loaded class
 * (premain budget, N-30).
 */
final class PremainDiscovery implements AgentBuilder.RedefinitionStrategy.DiscoveryStrategy {
  @Override
  public Iterable<Iterable<Class<?>>> resolve(Instrumentation instrumentation) {
    List<Class<?>> candidates = new ArrayList<Class<?>>();
    for (Class<?> type : instrumentation.getAllLoadedClasses()) {
      if (IgnoreMatchers.isJdkAllowlisted(type.getName())
          && instrumentation.isModifiableClass(type)) {
        candidates.add(type);
      }
    }
    return Collections.<Iterable<Class<?>>>singletonList(candidates);
  }
}
