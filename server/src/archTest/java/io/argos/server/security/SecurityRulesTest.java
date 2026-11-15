/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Sensitive;
import io.argos.server.security.fixture.ControllerWithoutRole;
import io.argos.server.security.fixture.PlainControllerWithoutRole;
import io.argos.server.security.fixture.RecordWithDefaultToString;
import io.argos.server.security.fixture.SensitiveInLog;
import io.argos.server.security.fixture.SensitiveInToString;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The security rules that hold in the code itself (SRV-01 of ADR-0003 as decided on 12 §1, 16 §7.9
 * {@code ST-48-4}), each proved against a violating fixture:
 *
 * <ul>
 *   <li>every handler of a {@code @Controller} or {@code @RestController} class, each overload on
 *       its own, has {@code @RequiresRole} on the method or class: the static complement of {@code
 *       RoleMatrix} (DSN-12-11);
 *   <li>a {@code @Sensitive} field is not in {@code toString()}: a record with one overrides the
 *       generated method, and no {@code toString()} reads the field;
 *   <li>a method that logs does not read a {@code @Sensitive} field or accessor and has no
 *       {@code @Sensitive} parameter. This is the part that byte code shows without data flow: a
 *       method that holds a secret stays out of logging altogether, a slightly stronger rule that
 *       is easy to follow (log in the caller).
 * </ul>
 */
@Tag("req:SRV-01")
@Tag("req:SEC-48")
class SecurityRulesTest {

  /** The package of the loggers the server uses. */
  private static final String LOGGER_PACKAGE = "org.slf4j.";

  /**
   * Methods with a {@code @Sensitive} parameter that also log, read by a person and found to log
   * nothing of the value. {@code writeTokenFile}: on a failed write it logs the file path and the
   * exception class, never the token.
   */
  private static final Set<String> REVIEWED_LOGGING =
      Set.of("io.argos.server.auth.setup.SetupTokenBootstrap.writeTokenFile(java.lang.String)");

  /**
   * Request-mapped methods of a {@code @Controller} class: {@code @RestController} and a plain
   * {@code @Controller} whose handlers carry {@code @ResponseBody} alike, every overload on its
   * own.
   */
  private static final DescribedPredicate<JavaMethod> HANDLER =
      DescribedPredicate.describe(
          "handlers of @Controller classes (@RestController among them) with a request mapping",
          method ->
              isController(method.getOwner())
                  && (method.isAnnotatedWith(RequestMapping.class)
                      || method.isMetaAnnotatedWith(RequestMapping.class)));

  static final ArchRule HANDLERS_HAVE_A_ROLE =
      methods()
          .that(HANDLER)
          .should(
              new ArchCondition<JavaMethod>("have @RequiresRole on the method or its class") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                  boolean satisfied =
                      method.isAnnotatedWith(RequiresRole.class)
                          || method.getOwner().isAnnotatedWith(RequiresRole.class);
                  events.add(
                      new SimpleConditionEvent(
                          method,
                          satisfied,
                          method.getFullName() + " has no @RequiresRole (DSN-12-11)"));
                }
              })
          .because("a handler without a role fails the start (RoleMatrix, 12 §7.3)");

  static final ArchRule SENSITIVE_NOT_IN_TO_STRING =
      classes()
          .that(
              DescribedPredicate.describe(
                  "have a @Sensitive field", c -> !sensitiveFields(c).isEmpty()))
          .should(
              new ArchCondition<JavaClass>("keep @Sensitive fields out of toString()") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                  Optional<JavaMethod> toString = toStringOf(type);
                  if (toString.isEmpty()) {
                    return;
                  }
                  JavaMethod method = toString.get();
                  if (type.isRecord() && method.getModifiers().contains(JavaModifier.FINAL)) {
                    events.add(
                        SimpleConditionEvent.violated(
                            type,
                            type.getName()
                                + " is a record with a @Sensitive component and the generated"
                                + " toString(), which prints it"));
                  }
                  Set<String> read = readsSensitive(method);
                  if (!read.isEmpty()) {
                    events.add(
                        SimpleConditionEvent.violated(
                            type, method.getFullName() + " reads @Sensitive " + read));
                  }
                }
              })
          .because("16 §7.9: passwords and tokens are excluded from toString and logs");

  static final ArchRule SENSITIVE_NOT_LOGGED =
      methods()
          .that(DescribedPredicate.describe("call a logger", SecurityRulesTest::logs))
          .should(
              new ArchCondition<JavaMethod>("not touch a @Sensitive value") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                  Set<String> read = readsSensitive(method);
                  if (!read.isEmpty()) {
                    events.add(
                        SimpleConditionEvent.violated(
                            method, method.getFullName() + " logs and reads @Sensitive " + read));
                  }
                  if (hasSensitiveParameter(method)
                      && !REVIEWED_LOGGING.contains(method.getFullName())) {
                    events.add(
                        SimpleConditionEvent.violated(
                            method, method.getFullName() + " logs and has a @Sensitive parameter"));
                  }
                }
              })
          .allowEmptyShould(true)
          .because("16 §7.9: passwords and tokens are never passed to a logger");

  /**
   * Whether {@code type} is a Spring MVC controller. {@code @RestController} is named as well as
   * {@code @Controller}, so the answer does not depend on the meta-annotation being resolved.
   */
  private static boolean isController(JavaClass type) {
    return type.isAnnotatedWith(Controller.class)
        || type.isMetaAnnotatedWith(Controller.class)
        || type.isAnnotatedWith(RestController.class)
        || type.isMetaAnnotatedWith(RestController.class);
  }

  private static Set<JavaField> sensitiveFields(JavaClass type) {
    return type.getFields().stream()
        .filter(field -> field.isAnnotatedWith(Sensitive.class))
        .collect(Collectors.toSet());
  }

  private static Optional<JavaMethod> toStringOf(JavaClass type) {
    return type.getMethods().stream()
        .filter(m -> m.getName().equals("toString") && m.getRawParameterTypes().isEmpty())
        .findFirst();
  }

  /** The @Sensitive fields and accessors that {@code code} reads. */
  private static Set<String> readsSensitive(JavaCodeUnit code) {
    Set<String> read =
        code.getFieldAccesses().stream()
            .filter(access -> access.getTarget().resolveMember().isPresent())
            .filter(
                access -> access.getTarget().resolveMember().get().isAnnotatedWith(Sensitive.class))
            .map(access -> access.getTarget().getFullName())
            .collect(Collectors.toSet());
    code.getMethodCallsFromSelf().stream()
        .filter(call -> call.getTarget().resolveMember().isPresent())
        .filter(call -> call.getTarget().resolveMember().get().isAnnotatedWith(Sensitive.class))
        .map(call -> call.getTarget().getFullName())
        .forEach(read::add);
    return read;
  }

  private static boolean logs(JavaMethod method) {
    return method.getMethodCallsFromSelf().stream()
        .anyMatch(call -> call.getTargetOwner().getName().startsWith(LOGGER_PACKAGE));
  }

  private static boolean hasSensitiveParameter(JavaCodeUnit code) {
    return code.getParameters().stream()
        .anyMatch(parameter -> parameter.isAnnotatedWith(Sensitive.class));
  }

  /** The production classes of the server (the fixtures of this source set excluded). */
  private static JavaClasses serverClasses() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .withImportOption(location -> !location.contains("/archTest/"))
        .importPackages("io.argos.server");
  }

  @Test
  @DisplayName(
      "SRV-01 every @Controller handler of the server (@RestController included) has"
          + " @RequiresRole")
  void handlersHaveARole() {
    JavaClasses classes = serverClasses();
    assertThat(classes.contain(ControllerWithoutRole.class)).isFalse();
    assertThat(classes.contain(PlainControllerWithoutRole.class)).isFalse();
    HANDLERS_HAVE_A_ROLE.check(classes);
    assertThat(classes.stream().flatMap(c -> c.getMethods().stream()).filter(HANDLER).count())
        .as("not vacuous: the server has handlers")
        .isPositive();
  }

  @Test
  @DisplayName("SRV-01 negative fixture: a handler without @RequiresRole violates the rule")
  void handlerWithoutRole() {
    EvaluationResult result =
        HANDLERS_HAVE_A_ROLE.evaluate(
            new ClassFileImporter().importClasses(ControllerWithoutRole.class));
    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().getDetails())
        .hasSize(1)
        .allMatch(d -> d.contains("forgotten") && d.contains("no @RequiresRole"));
  }

  @Test
  @DisplayName(
      "SRV-01 negative fixture: an overload without @RequiresRole in a plain @Controller violates"
          + " the rule")
  void plainControllerOverloadWithoutRole() {
    EvaluationResult result =
        HANDLERS_HAVE_A_ROLE.evaluate(
            new ClassFileImporter().importClasses(PlainControllerWithoutRole.class));
    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().getDetails())
        .hasSize(1)
        .allMatch(d -> d.contains("get(java.lang.String)") && d.contains("no @RequiresRole"));
  }

  @Test
  @DisplayName("T-SEC-48b no toString() of the server shows a @Sensitive value")
  void sensitiveToString() {
    SENSITIVE_NOT_IN_TO_STRING.check(serverClasses());
  }

  @Test
  @DisplayName("T-SEC-48b negative fixtures: a generated or careless toString() violates the rule")
  void sensitiveToStringFixtures() {
    EvaluationResult generated =
        SENSITIVE_NOT_IN_TO_STRING.evaluate(
            new ClassFileImporter().importClasses(RecordWithDefaultToString.class));
    assertThat(generated.getFailureReport().getDetails())
        .anyMatch(d -> d.contains("generated toString()"));

    EvaluationResult careless =
        SENSITIVE_NOT_IN_TO_STRING.evaluate(
            new ClassFileImporter().importClasses(SensitiveInToString.class));
    assertThat(careless.getFailureReport().getDetails())
        .anyMatch(d -> d.contains("reads @Sensitive"));
  }

  @Test
  @DisplayName("T-SEC-48b no method of the server that logs touches a @Sensitive value")
  void sensitiveNotLogged() {
    SENSITIVE_NOT_LOGGED.check(serverClasses());
  }

  @Test
  @DisplayName("T-SEC-48b negative fixtures: logging next to a @Sensitive value violates the rule")
  void sensitiveLoggedFixtures() {
    EvaluationResult result =
        SENSITIVE_NOT_LOGGED.evaluate(new ClassFileImporter().importClasses(SensitiveInLog.class));
    assertThat(result.getFailureReport().getDetails())
        .anyMatch(d -> d.contains("logField") && d.contains("reads @Sensitive"))
        .anyMatch(d -> d.contains("logParameter") && d.contains("@Sensitive parameter"));
    assertThat(result.getFailureReport().getDetails())
        .noneMatch(d -> d.contains("logNothingSecret"));
  }
}
