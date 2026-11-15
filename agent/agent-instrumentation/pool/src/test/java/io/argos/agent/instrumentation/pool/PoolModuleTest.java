/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.pool;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PoolModule: identity, witness, no helpers, named matching of the three pool classes (and not of
 * their data sources or subclasses' own names), constructor and {@code createDataSource()} method
 * matchers against the real library classes.
 */
class PoolModuleTest {
  private final PoolModule module = new PoolModule();

  @Test
  void identity() {
    assertThat(module.id()).isEqualTo("pool");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.POOL);
    assertThat(module.helperClassNames()).as("bootstrap API only: no helper").isEmpty();
    assertThat(module.libraryVersionRange()).hasSize(3);
  }

  @Test
  @DisplayName("T-AG-062d witness: a loader that sees one of the pool libraries, not one without")
  void witness() {
    assertThat(module.witness().matches(PoolModuleTest.class.getClassLoader())).isTrue();
    URLClassLoader empty = new URLClassLoader(new URL[0], null);
    assertThat(module.witness().matches(empty)).isFalse();
  }

  @Test
  @DisplayName(
      "T-AG-062d named matching: HikariPool and ConnectionPool constructors, BasicDataSource"
          + ".createDataSource(); data sources and other classes are not matched")
  void matchers() throws Exception {
    List<TypeInstrumentation> rules = module.types();
    assertThat(rules).hasSize(3);
    assertMatches(
        rules.get(0),
        com.zaxxer.hikari.pool.HikariPool.class,
        com.zaxxer.hikari.HikariDataSource.class,
        HikariPoolAdvice.class);
    assertMatches(
        rules.get(1),
        org.apache.tomcat.jdbc.pool.ConnectionPool.class,
        org.apache.tomcat.jdbc.pool.DataSource.class,
        TomcatJdbcPoolAdvice.class);
    assertMatches(
        rules.get(2),
        org.apache.commons.dbcp2.BasicDataSource.class,
        org.apache.commons.dbcp2.PoolingDataSource.class,
        Dbcp2DataSourceAdvice.class);

    List<ElementMatcher<? super MethodDescription>> hikari = methods(rules.get(0));
    for (Constructor<?> constructor :
        com.zaxxer.hikari.pool.HikariPool.class.getDeclaredConstructors()) {
      assertThat(hikari.get(0).matches(new MethodDescription.ForLoadedConstructor(constructor)))
          .isTrue();
    }
    assertThat(
            hikari
                .get(0)
                .matches(
                    new MethodDescription.ForLoadedMethod(
                        com.zaxxer.hikari.pool.HikariPool.class.getMethod("getConnection"))))
        .isFalse();
    List<ElementMatcher<? super MethodDescription>> dbcp2 = methods(rules.get(2));
    Method create =
        org.apache.commons.dbcp2.BasicDataSource.class.getDeclaredMethod("createDataSource");
    assertThat(dbcp2.get(0).matches(new MethodDescription.ForLoadedMethod(create))).isTrue();
    Method getConnection =
        org.apache.commons.dbcp2.BasicDataSource.class.getMethod("getConnection");
    assertThat(dbcp2.get(0).matches(new MethodDescription.ForLoadedMethod(getConnection)))
        .isFalse();
  }

  private static void assertMatches(
      TypeInstrumentation rule, Class<?> matched, Class<?> notMatched, Class<?> advice) {
    assertThat(rule.typeMatcher().matches(TypeDescription.ForLoadedType.of(matched))).isTrue();
    assertThat(rule.typeMatcher().matches(TypeDescription.ForLoadedType.of(notMatched))).isFalse();
    final List<String> advices = new ArrayList<String>();
    rule.transform(
        new TypeTransformer() {
          @Override
          public void applyAdvice(
              ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
            advices.add(adviceClassName);
          }
        });
    assertThat(advices).containsExactly(advice.getName());
  }

  private static List<ElementMatcher<? super MethodDescription>> methods(TypeInstrumentation rule) {
    final List<ElementMatcher<? super MethodDescription>> methods =
        new ArrayList<ElementMatcher<? super MethodDescription>>();
    rule.transform(
        new TypeTransformer() {
          @Override
          public void applyAdvice(
              ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
            methods.add(methodMatcher);
          }
        });
    return methods;
  }
}
