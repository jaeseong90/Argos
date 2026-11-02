/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.modulefixture;

import static net.bytebuddy.matcher.ElementMatchers.named;

import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * A module that breaks every rule of {@code ModuleChecks} (violating fixture): not indexed, unknown
 * id, test module id, helpers with state, out of order, missing or outside the package, advice that
 * is missing, misnamed or not annotated.
 */
public final class BadModule implements InstrumentationModule {
  @Override
  public String id() {
    return "no-such-module";
  }

  @Override
  public int moduleId() {
    return 61;
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Arrays.<TypeInstrumentation>asList(
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named("example.Target");
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(named("run"), NotAnnotatedAdvice.class.getName());
            transformer.applyAdvice(named("run"), MisnamedInterceptor.class.getName());
            transformer.applyAdvice(
                named("run"), BadModule.class.getPackage().getName() + ".GoneAdvice");
          }
        });
  }

  @Override
  public List<String> helperClassNames() {
    String p = BadModule.class.getPackage().getName() + ".";
    return Arrays.asList(
        p + "SubHelper", p + "StatefulHelper", p + "MissingHelper", "other.pkg.OtherHelper");
  }
}
