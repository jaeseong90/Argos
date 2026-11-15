/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.argos.server.auth.SelfAuthenticatingHandler;
import io.argos.server.security.fixture.NonMethodHandlerFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.mvc.Controller;

/**
 * The static complement of {@code RoleMatrix}'s check of handlers that are not
 * {@code @RequestMapping} methods (DSN-12-11, SRV-01 of ADR-0003 as decided on 12 §1): such a
 * handler cannot carry {@code @RequiresRole}, so the server's production code declares none, except
 * a {@link SelfAuthenticatingHandler} (the hook of WO-22's {@code /ws} handshake). Each rule is
 * proved against violating fixtures.
 *
 * <ul>
 *   <li>no class implements {@code HttpRequestHandler} or the MVC {@code Controller} interface
 *       without implementing {@link SelfAuthenticatingHandler};
 *   <li>no {@code @Bean} method returns one of those types without the marker (a lambda bean has no
 *       class of its own, so the bean method is what the rule can see);
 *   <li>no {@code @Bean} method returns a {@code RouterFunction} (never allowed: a router function
 *       cannot be marked, and {@code RoleMatrix} refuses any).
 * </ul>
 */
@Tag("req:SRV-01")
@Tag("req:DSN-12-11")
class NonMethodHandlerRulesTest {

  private static final List<Class<?>> HANDLER_TYPES =
      List.of(HttpRequestHandler.class, Controller.class);

  private static boolean handlerType(JavaClass type) {
    return HANDLER_TYPES.stream().anyMatch(type::isAssignableTo);
  }

  private static boolean marked(JavaClass type) {
    return type.isAssignableTo(SelfAuthenticatingHandler.class);
  }

  static final ArchRule HANDLER_CLASSES_ARE_MARKED =
      classes()
          .that(
              DescribedPredicate.describe(
                  "implement HttpRequestHandler or the MVC Controller interface",
                  type -> !type.isInterface() && handlerType(type)))
          .should(
              new ArchCondition<JavaClass>("implement SelfAuthenticatingHandler") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                  events.add(
                      new SimpleConditionEvent(
                          type,
                          marked(type),
                          type.getName()
                              + " is a handler that cannot carry @RequiresRole and is not a"
                              + " SelfAuthenticatingHandler (DSN-12-11)"));
                }
              })
          .allowEmptyShould(true)
          .because("RoleMatrix refuses such a handler at start, the interceptor at run time");

  static final ArchRule NO_UNMARKED_HANDLER_BEANS =
      methods()
          .that(
              DescribedPredicate.describe(
                  "are @Bean methods", (JavaMethod method) -> method.isAnnotatedWith(Bean.class)))
          .should(
              new ArchCondition<JavaMethod>(
                  "not return a router function or an unmarked request handler") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                  JavaClass returned = method.getRawReturnType();
                  if (returned.isAssignableTo(RouterFunction.class)) {
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            method.getFullName()
                                + " declares a router function, which cannot carry"
                                + " @RequiresRole (DSN-12-11)"));
                  } else if (handlerType(returned) && !marked(returned)) {
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            method.getFullName()
                                + " declares a request handler bean that cannot carry"
                                + " @RequiresRole and is not a SelfAuthenticatingHandler"
                                + " (DSN-12-11)"));
                  }
                }
              })
          .allowEmptyShould(true)
          .because("RoleMatrix refuses such a handler at start, the interceptor at run time");

  /** The production classes of the server (the fixtures of this source set excluded). */
  private static JavaClasses serverClasses() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .withImportOption(location -> !location.contains("/archTest/"))
        .importPackages("io.argos.server");
  }

  private static JavaClasses fixtures() {
    return new ClassFileImporter()
        .importClasses(
            NonMethodHandlerFixtures.PlainHttpRequestHandler.class,
            NonMethodHandlerFixtures.PlainMvcController.class,
            NonMethodHandlerFixtures.Marked.class,
            NonMethodHandlerFixtures.Beans.class);
  }

  @Test
  @DisplayName(
      "SRV-01 the server declares no HttpRequestHandler, MVC Controller or router function, except"
          + " a SelfAuthenticatingHandler")
  void serverHasNone() {
    JavaClasses classes = serverClasses();
    assertThat(classes.contain(NonMethodHandlerFixtures.Beans.class)).isFalse();
    assertThat(classes.contain(SelfAuthenticatingHandler.class))
        .as("not vacuous: the server's classes are imported")
        .isTrue();
    HANDLER_CLASSES_ARE_MARKED.check(classes);
    NO_UNMARKED_HANDLER_BEANS.check(classes);
  }

  @Test
  @DisplayName(
      "SRV-01 negative fixtures: an unmarked HttpRequestHandler and MVC Controller violate the class"
          + " rule; the marked handler does not")
  void handlerClassFixtures() {
    EvaluationResult result = HANDLER_CLASSES_ARE_MARKED.evaluate(fixtures());
    assertThat(result.getFailureReport().getDetails())
        .hasSize(2)
        .anyMatch(d -> d.contains("PlainHttpRequestHandler"))
        .anyMatch(d -> d.contains("PlainMvcController"))
        .noneMatch(d -> d.contains("$Marked "));
  }

  @Test
  @DisplayName(
      "SRV-01 negative fixtures: a router function bean and a lambda request handler bean violate"
          + " the bean rule; the marked handler bean and an unrelated bean do not")
  void beanFixtures() {
    EvaluationResult result = NO_UNMARKED_HANDLER_BEANS.evaluate(fixtures());
    assertThat(result.getFailureReport().getDetails())
        .hasSize(2)
        .anyMatch(d -> d.contains("reviewRouter") && d.contains("router function"))
        .anyMatch(d -> d.contains("reviewProbe") && d.contains("request handler bean"))
        .noneMatch(d -> d.contains("markedHandler") || d.contains("unrelated"));
  }
}
