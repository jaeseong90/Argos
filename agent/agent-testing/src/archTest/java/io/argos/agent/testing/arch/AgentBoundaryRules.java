/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static io.argos.agent.testing.arch.AgentPredicates.adviceClasses;
import static io.argos.agent.testing.arch.AgentPredicates.agentClasses;
import static io.argos.agent.testing.arch.AgentPredicates.assignableTo;
import static io.argos.agent.testing.arch.AgentPredicates.base;
import static io.argos.agent.testing.arch.AgentPredicates.callTo;
import static io.argos.agent.testing.arch.AgentPredicates.helperClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;

/**
 * Package boundary and hygiene rules of the agent: 10 §2 B-1..B-4, 19 §5.3 AGT-01..AGT-03 and the
 * TraceContext field rule T-AG-004 (F-A36).
 */
public final class AgentBoundaryRules {
  private static final Set<String> BOXED =
      Set.of(
          "java.lang.Boolean",
          "java.lang.Byte",
          "java.lang.Character",
          "java.lang.Short",
          "java.lang.Integer",
          "java.lang.Long",
          "java.lang.Float",
          "java.lang.Double");

  private AgentBoundaryRules() {}

  public static List<ArchRule> all(AgentLayout layout) {
    return List.of(
        bootstrapDependsOnJdkOnly(layout),
        helpersReferenceOnlyBootstrap(layout),
        coreUsesOnlyInstrumentationApi(layout),
        adviceAndHelpersUseNoShadedLibraries(layout),
        noLoggingFrameworks(layout),
        noConsoleOutput(layout),
        noUnsafe(layout),
        traceStateFieldTypes(layout));
  }

  /**
   * AGT-01 / B-1: the bootstrap module (bootstrap package and entry point) depends on the JDK only,
   * and only on JDK packages that the bootstrap class loader sees (not java.sql, java.net.http).
   */
  public static ArchRule bootstrapDependsOnJdkOnly(AgentLayout layout) {
    return classes()
        .that(
            DescribedPredicate.describe(
                "bootstrap module classes", c -> layout.isBootstrapModule(c.getName())))
        .should()
        .onlyDependOnClassesThat(
            DescribedPredicate.describe(
                "java.* (except platform-loader packages java.sql, java.net.http) or the bootstrap"
                    + " module",
                c -> {
                  JavaClass target = base(c);
                  String name = target.getName();
                  return target.isPrimitive()
                      || layout.isBootstrapModule(name)
                      || (name.startsWith("java.")
                          && !name.startsWith("java.sql.")
                          && !name.startsWith("java.net.http."));
                }))
        .as("AGT-01 [B-1] the bootstrap module depends on JDK classes (java.*) only")
        .because(
            "bootstrap classes are appended to the bootstrap class loader, which sees java.base and"
                + " a few other JDK modules only (10 §2 B-1, §4.2)");
  }

  /**
   * B-2 (10 §5.4): helpers refer to Argos classes only in the bootstrap package and own helpers.
   */
  public static ArchRule helpersReferenceOnlyBootstrap(AgentLayout layout) {
    return classes()
        .that(helperClasses(layout))
        .should(
            AdviceRules.agentDependenciesLimitedTo(
                layout,
                "refer to Argos classes only in the bootstrap package or own-module helpers",
                (origin, target) ->
                    AgentLayout.inPackage(target, layout.bootstrap())
                        || AgentLayout.topLevel(target).equals(AgentLayout.topLevel(origin))
                        || (AgentPredicates.isHelperName(layout, target)
                            && AgentPredicates.sameModule(layout, origin, target))))
        .as("B-2 helpers refer only to bootstrap classes and helpers of their own module")
        .because("helpers are injected into the application class loader (10 §5.4)");
  }

  /** B-3: core refers to instrumentation.api only, never to an individual module. */
  public static ArchRule coreUsesOnlyInstrumentationApi(AgentLayout layout) {
    return noClasses()
        .that()
        .resideInAPackage(layout.core() + "..")
        .should()
        .dependOnClassesThat(
            DescribedPredicate.describe(
                "classes of an instrumentation module (other than instrumentation.api)",
                c -> {
                  String name = base(c).getName();
                  return AgentLayout.inPackage(name, layout.instrumentation())
                      && !AgentLayout.inPackage(name, layout.instrumentationApi());
                }))
        .as("B-3 core depends on instrumentation.api only, not on instrumentation modules")
        .because("modules are registered through a static list, never referenced by core (10 §2)");
  }

  /**
   * B-4: advice and helper classes never use shaded (relocated) libraries. The ByteBuddy Advice
   * annotations are the exception: they are metadata for the transformer, not inlined code.
   */
  public static ArchRule adviceAndHelpersUseNoShadedLibraries(AgentLayout layout) {
    DescribedPredicate<JavaClass> adviceOrHelper =
        adviceClasses(layout).or(helperClasses(layout)).as("advice or helper classes");
    return classes()
        .that(adviceOrHelper)
        .should(
            new ArchCondition<JavaClass>("not use shaded (relocated) libraries") {
              @Override
              public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                  JavaClass target = base(dependency.getTargetClass());
                  if (layout.isShadedLibrary(target.getName())
                      && !isAdviceMetadata(dependency, target)) {
                    events.add(
                        SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                  }
                }
              }
            })
        .as("B-4 advice and helper classes use no shaded library (only core may)")
        .because(
            "relocated libraries live in the agent class loader, invisible to the application class"
                + " loader where advice and helpers run (10 §2 B-4)");
  }

  /**
   * ByteBuddy Advice annotations and the types of their members (including default values such as
   * {@code Advice.NoExceptionHandler} and {@code Assigner.Typing}): transformer metadata that is
   * not part of the inlined code. Any other use of these types (calls, fields) is still a
   * violation.
   */
  private static boolean isAdviceMetadata(Dependency dependency, JavaClass target) {
    String name = target.getName();
    boolean adviceType =
        name.startsWith(AgentPredicates.ADVICE + "$")
            || name.equals("net.bytebuddy.implementation.bytecode.assign.Assigner$Typing");
    String description = dependency.getDescription();
    return adviceType
        && (target.isAnnotation()
            || description.contains("annotation member of type")
            || description.contains("annotated with"));
  }

  /**
   * AGT-02: no logging framework. java.util.logging nowhere in the agent; SLF4J, Log4j, Logback,
   * commons-logging and JBoss Logging nowhere except the logging instrumentation module, which
   * instruments them (10 §6.9).
   */
  public static ArchRule noLoggingFrameworks(AgentLayout layout) {
    List<String> frameworks =
        List.of(
            "org.slf4j.",
            "org.apache.logging.log4j.",
            "org.apache.log4j.",
            "org.apache.commons.logging.",
            "ch.qos.logback.",
            "org.jboss.logging.");
    return noClasses()
        .that(agentClasses(layout))
        .should()
        .dependOnClassesThat(
            DescribedPredicate.describe(
                "java.util.logging classes",
                c -> base(c).getName().startsWith("java.util.logging.")))
        .orShould(
            new ArchCondition<JavaClass>("use a logging framework outside the logging module") {
              @Override
              public void check(JavaClass item, ConditionEvents events) {
                if (AgentLayout.inPackage(item.getName(), layout.loggingModule())) {
                  return;
                }
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                  String target = base(dependency.getTargetClass()).getName();
                  if (frameworks.stream().anyMatch(target::startsWith)) {
                    events.add(
                        SimpleConditionEvent.satisfied(dependency, dependency.getDescription()));
                  }
                }
              }
            })
        .as("AGT-02 agent code uses no logging framework (own AgentLogger only)")
        .because("logging frameworks clash with the application's (CLAUDE.md, 19 §5.3 AGT-02)");
  }

  /** AGT-02: no stdout/stderr outside the logger's stderr fallback and the --check output. */
  public static ArchRule noConsoleOutput(AgentLayout layout) {
    return noClasses()
        .that(agentClasses(layout))
        .and(
            DescribedPredicate.describe(
                "are outside " + layout.consolePackages(),
                (JavaClass c) ->
                    layout.consolePackages().stream()
                        .noneMatch(p -> AgentLayout.inPackage(c.getName(), p))))
        .should()
        .accessField("java.lang.System", "out")
        .orShould()
        .accessField("java.lang.System", "err")
        .orShould()
        .callMethodWhere(
            callTo(
                "Throwable.printStackTrace()",
                (owner, name) ->
                    name.equals("printStackTrace") && assignableTo(owner, "java.lang.Throwable")))
        .as("AGT-02 agent code writes nothing to stdout/stderr (except core.log, core.check)")
        .because("the agent must not write to the application's console (F-A35, T-AG-060)");
  }

  /** AGT-03: no reference to sun.misc.Unsafe (shaded dependencies: check-shading.sh). */
  public static ArchRule noUnsafe(AgentLayout layout) {
    return noClasses()
        .that(agentClasses(layout))
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("sun.misc.Unsafe")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("jdk.internal.misc.Unsafe")
        .as("AGT-03 agent code does not reference sun.misc.Unsafe")
        .because("JDK 25 denies Unsafe memory access by default (F-A35)");
  }

  /**
   * T-AG-004 (F-A36): TraceContext and the step builders (core.trace Transaction, StepBuffer,
   * *Step) keep primitives, String, boxed primitives and bootstrap types only, so they never pin an
   * application class loader. Exceptions from 10 §3: {@code WeakReference} and the opaque {@code
   * Object coreRef}; core.trace classes may also hold core types.
   */
  public static ArchRule traceStateFieldTypes(AgentLayout layout) {
    String traceContext = layout.bootstrap() + ".TraceContext";
    String trace = layout.core() + ".trace";
    DescribedPredicate<JavaClass> traceState =
        DescribedPredicate.describe(
            "TraceContext or a step builder (core.trace Transaction, StepBuffer, *Step)",
            c -> {
              String top = AgentLayout.topLevel(c.getName());
              if (top.equals(traceContext)) {
                return true;
              }
              if (!AgentLayout.inPackage(top, trace)) {
                return false;
              }
              String simple = top.substring(top.lastIndexOf('.') + 1);
              return simple.equals("Transaction")
                  || simple.equals("StepBuffer")
                  || simple.endsWith("Step");
            });
    return fields()
        .that()
        .areDeclaredInClassesThat(traceState)
        .should(
            new ArchCondition<JavaField>(
                "have a primitive, String, boxed primitive or bootstrap type") {
              @Override
              public void check(JavaField field, ConditionEvents events) {
                JavaClass type = base(field.getRawType());
                String name = type.getName();
                boolean coreOwner = AgentLayout.inPackage(field.getOwner().getName(), trace);
                boolean allowed =
                    type.isPrimitive()
                        || name.equals("java.lang.String")
                        || BOXED.contains(name)
                        || name.equals("java.lang.ref.WeakReference")
                        || (name.equals("java.lang.Object")
                            && field.getName().equals("coreRef")
                            && !field.getRawType().isArray())
                        || AgentLayout.inPackage(name, layout.bootstrap())
                        || (coreOwner && AgentLayout.inPackage(name, layout.core()));
                if (!allowed) {
                  events.add(
                      SimpleConditionEvent.violated(
                          field,
                          field.getFullName()
                              + " has type "
                              + field.getRawType().getName()
                              + " (may hold application objects and pin their class loader)"));
                }
              }
            })
        .as(
            "T-AG-004 TraceContext and step builder fields are primitive, String or bootstrap types")
        .because(
            "a reference to an application object leaks the web application class loader (F-A36)");
  }
}
