/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

/** HttpClientJdkModule: identity, no helpers, the one JDK point (tests run on JDK 8). */
class HttpClientJdkModuleTest {
  private final HttpClientJdkModule module = new HttpClientJdkModule();

  @Test
  void identity() {
    assertThat(module.id()).isEqualTo("httpclient-jdk");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.HTTPCLIENT_JDK);
    assertThat(module.helperClassNames()).as("JDK class: no helper").isEmpty();
    assertThat(module.libraryVersionRange()).isNotEmpty();
  }

  @Test
  void matchesTheJdkImplementationsGetInputStream() throws Exception {
    Class<?> impl = Class.forName(HttpClientJdkModule.CONNECTION_CLASS);
    TypeInstrumentation rule = module.types().get(0);
    assertThat(rule.typeMatcher().matches(TypeDescription.ForLoadedType.of(impl))).isTrue();
    assertThat(
            rule.typeMatcher().matches(TypeDescription.ForLoadedType.of(HttpURLConnection.class)))
        .isFalse();
    final List<ElementMatcher<? super MethodDescription>> methods =
        new ArrayList<ElementMatcher<? super MethodDescription>>();
    rule.transform(
        new TypeTransformer() {
          @Override
          public void applyAdvice(
              ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
            assertThat(adviceClassName).isEqualTo(HttpUrlConnectionAdvice.class.getName());
            methods.add(methodMatcher);
          }
        });
    assertThat(methods).hasSize(1);
    assertThat(
            methods
                .get(0)
                .matches(new MethodDescription.ForLoadedMethod(impl.getMethod("getInputStream"))))
        .isTrue();
    assertThat(
            methods
                .get(0)
                .matches(new MethodDescription.ForLoadedMethod(impl.getMethod("getOutputStream"))))
        .isFalse();
  }
}
