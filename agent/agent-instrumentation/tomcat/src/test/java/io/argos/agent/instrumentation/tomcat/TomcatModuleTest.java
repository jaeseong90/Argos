/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.tomcat;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.NioEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TomcatModule: identity, witness, no helper, named matching of AbstractEndpoint.start(). */
class TomcatModuleTest {
  private final TomcatModule module = new TomcatModule();

  @Test
  void identity() {
    assertThat(module.id()).isEqualTo("tomcat");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.TOMCAT);
    assertThat(module.helperClassNames()).as("bootstrap API only: no helper").isEmpty();
    assertThat(module.libraryVersionRange()).isNotEmpty();
  }

  @Test
  @DisplayName("T-AG-062e witness and matchers: AbstractEndpoint.start() only, by name")
  void matchers() throws Exception {
    assertThat(module.witness().matches(TomcatModuleTest.class.getClassLoader())).isTrue();
    assertThat(module.witness().matches(new URLClassLoader(new URL[0], null))).isFalse();
    TypeInstrumentation rule = module.types().get(0);
    assertThat(rule.typeMatcher().matches(TypeDescription.ForLoadedType.of(AbstractEndpoint.class)))
        .isTrue();
    assertThat(rule.typeMatcher().matches(TypeDescription.ForLoadedType.of(NioEndpoint.class)))
        .as("subclasses inherit the final start(); only the base class is transformed")
        .isFalse();
    final List<ElementMatcher<? super MethodDescription>> methods =
        new ArrayList<ElementMatcher<? super MethodDescription>>();
    final List<String> advice = new ArrayList<String>();
    rule.transform(
        new TypeTransformer() {
          @Override
          public void applyAdvice(
              ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
            methods.add(methodMatcher);
            advice.add(adviceClassName);
          }
        });
    assertThat(advice).containsExactly(EndpointStartAdvice.class.getName());
    assertThat(
            methods
                .get(0)
                .matches(
                    new MethodDescription.ForLoadedMethod(
                        AbstractEndpoint.class.getMethod("start"))))
        .isTrue();
    assertThat(
            methods
                .get(0)
                .matches(
                    new MethodDescription.ForLoadedMethod(
                        AbstractEndpoint.class.getMethod("stop"))))
        .isFalse();
  }
}
