/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static io.argos.agent.testing.arch.AgentPredicates.ON_METHOD_ENTER;
import static io.argos.agent.testing.arch.AgentPredicates.ON_METHOD_EXIT;
import static io.argos.agent.testing.arch.AgentPredicates.adviceAnnotation;
import static io.argos.agent.testing.arch.AgentPredicates.adviceClasses;
import static io.argos.agent.testing.arch.AgentPredicates.adviceMethods;
import static io.argos.agent.testing.arch.AgentPredicates.assignableTo;
import static io.argos.agent.testing.arch.AgentPredicates.base;
import static io.argos.agent.testing.arch.AgentPredicates.callTo;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Advice rules (19 §5.3 ADV-01..ADV-04, 10 §5.3 A-1..A-6, DSN-02-01 as relaxed by C-9). Test IDs:
 * 18 appendix A T-AG-001 (lambda/invokedynamic, static, naming, format/stream/try-with-resources),
 * T-AG-002 (skipOn/repeatOn/inline/SelfCallHandle, suppress, writable values, Implementation) and
 * T-AG-003 (guard, synchronized, blocking, reflection, new Throwable). Every rule is a plain {@link
 * ArchRule} so that the production check and the fixture tests run the same definition.
 */
public final class AdviceRules {
  /** Advice parameter annotations whose value ByteBuddy writes back when readOnly = false. */
  private static final Set<String> WRITABLE_PARAMETER_ANNOTATIONS =
      Set.of("Argument", "AllArguments", "Return", "Thrown", "FieldValue", "This");

  /** Advice parameter annotation that hands advice a setter of an application field. */
  static final String FIELD_SETTER_HANDLE = "FieldSetterHandle";

  /** Advice parameter annotation that hands advice a handle that calls the instrumented method. */
  static final String SELF_CALL_HANDLE = "SelfCallHandle";

  /** The only ByteBuddy implementation types advice may use: Advice annotation attributes. */
  private static final Set<String> ALLOWED_IMPLEMENTATION_TYPES =
      Set.of(
          "net.bytebuddy.implementation.bytecode.assign.Assigner",
          "net.bytebuddy.implementation.bytecode.assign.Assigner$Typing");

  private AdviceRules() {}

  public static List<ArchRule> all(AgentLayout layout) {
    return List.of(
        noInvokeDynamic(layout),
        staticMethodsOnly(layout),
        adviceNaming(layout),
        noFormatStreamsOrTryWithResources(layout),
        noSkipRepeatOrDelegatingAdvice(layout),
        suppressThrowable(layout),
        writableValuesOnlyWhenAllowlisted(layout),
        noMethodDelegation(layout),
        guardFirst(layout),
        noSynchronization(layout),
        noBlockingCalls(layout),
        noReflection(layout),
        noThrowableCreation(layout),
        referenceOnlyBootstrapAndOwnHelpers(layout));
  }

  /** T-AG-001a [ADV-01, A-3]: no lambda, method reference or invokedynamic (ASM, U-8). */
  public static ArchRule noInvokeDynamic(AgentLayout layout) {
    return classes()
        .that(adviceClasses(layout))
        .should(AdviceBytecode.containNoInvokeDynamic())
        .as(
            "T-AG-001a [ADV-01] advice classes contain no lambda, method reference or invokedynamic")
        .because("inlined advice runs in the target class; lambdas need bootstrap methods there");
  }

  /**
   * T-AG-001b [ADV-01, A-1]: advice methods are static; advice classes declare static methods only.
   */
  public static ArchRule staticMethodsOnly(AgentLayout layout) {
    return methods()
        .that()
        .areDeclaredInClassesThat(adviceClasses(layout))
        .should()
        .beStatic()
        .as("T-AG-001b [ADV-01] advice classes declare static methods only")
        .because("ByteBuddy inlines static advice methods (10 §5.3 A-1)");
  }

  /** T-AG-001c [A-1]: advice class names end with "Advice". */
  public static ArchRule adviceNaming(AgentLayout layout) {
    return classes()
        .that(adviceClasses(layout))
        .should()
        .haveSimpleNameEndingWith("Advice")
        .as("T-AG-001c [A-1] advice class names end with 'Advice'")
        .because("reviewers and the transform baseline recognise advice by name (10 §5.3 A-1)");
  }

  /** T-AG-001d [ADV-01, A-3]: no String.format, java.util.stream or try-with-resources. */
  public static ArchRule noFormatStreamsOrTryWithResources(AgentLayout layout) {
    return noClasses()
        .that(adviceClasses(layout))
        .should()
        .callMethodWhere(
            callTo(
                "String.format",
                (owner, name) ->
                    owner.getName().equals("java.lang.String")
                        && (name.equals("format") || name.equals("formatted"))))
        .orShould()
        .dependOnClassesThat()
        .resideInAPackage("java.util.stream..")
        .orShould()
        .callMethodWhere(
            callTo(
                "Throwable.addSuppressed (try-with-resources)",
                (owner, name) ->
                    name.equals("addSuppressed") && assignableTo(owner, "java.lang.Throwable")))
        .as("T-AG-001d [ADV-01] advice classes use no String.format, streams or try-with-resources")
        .because("advice code stays allocation-light and simple (10 §5.3 A-3)");
  }

  /**
   * T-AG-002a [ADV-02, A-4]: no skipOn, no repeatOn, no inline = false, no @Advice.SelfCallHandle.
   */
  public static ArchRule noSkipRepeatOrDelegatingAdvice(AgentLayout layout) {
    return methods()
        .that(adviceMethods(layout))
        .should(
            new ArchCondition<JavaMethod>(
                "not use skipOn, repeatOn, inline = false or @Advice.SelfCallHandle") {
              @Override
              public void check(JavaMethod method, ConditionEvents events) {
                for (String type : List.of(ON_METHOD_ENTER, ON_METHOD_EXIT)) {
                  Optional<JavaAnnotation<JavaMethod>> annotation =
                      adviceAnnotation(layout, method, type);
                  if (annotation.isEmpty()) {
                    continue;
                  }
                  for (String attribute : List.of("skipOn", "repeatOn")) {
                    annotation
                        .get()
                        .get(attribute)
                        .filter(value -> !isVoidClass(value))
                        .ifPresent(
                            value ->
                                events.add(
                                    SimpleConditionEvent.violated(
                                        method,
                                        method.getFullName()
                                            + " uses "
                                            + attribute
                                            + " = "
                                            + describe(value)
                                            + " (alters the application's control flow)")));
                  }
                  for (String attribute : List.of("skipOnIndex", "repeatOnIndex")) {
                    annotation
                        .get()
                        .get(attribute)
                        .filter(value -> value instanceof Integer index && index != -1)
                        .ifPresent(
                            value ->
                                events.add(
                                    SimpleConditionEvent.violated(
                                        method,
                                        method.getFullName()
                                            + " uses "
                                            + attribute
                                            + " = "
                                            + value)));
                  }
                  if (annotation.get().get("inline").filter(Boolean.FALSE::equals).isPresent()) {
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            method.getFullName()
                                + " uses inline = false (advice must be inlined, DSN-02-01)"));
                  }
                }
                for (JavaParameter parameter : method.getParameters()) {
                  for (JavaAnnotation<JavaParameter> annotation : parameter.getAnnotations()) {
                    if (isAdviceMember(layout, annotation, SELF_CALL_HANDLE)) {
                      events.add(
                          SimpleConditionEvent.violated(
                              method,
                              method.getFullName()
                                  + " parameter "
                                  + parameter.getIndex()
                                  + " uses @Advice.SelfCallHandle (calls the application method"
                                  + " again, like repeatOn)"));
                    }
                  }
                }
              }
            })
        .as(
            "T-AG-002a [ADV-02] advice uses no skipOn, repeatOn, inline = false or"
                + " @Advice.SelfCallHandle")
        .because("advice must never skip, repeat or delegate the application method (A-4)");
  }

  /** T-AG-002b [ADV-02, A-1]: suppress = Throwable.class, and onThrowable on exit advice. */
  public static ArchRule suppressThrowable(AgentLayout layout) {
    return methods()
        .that(adviceMethods(layout))
        .should(
            new ArchCondition<JavaMethod>("declare suppress = Throwable.class") {
              @Override
              public void check(JavaMethod method, ConditionEvents events) {
                for (String type : List.of(ON_METHOD_ENTER, ON_METHOD_EXIT)) {
                  Optional<JavaAnnotation<JavaMethod>> annotation =
                      adviceAnnotation(layout, method, type);
                  if (annotation.isEmpty()) {
                    continue;
                  }
                  List<String> required =
                      type.equals(ON_METHOD_EXIT)
                          ? List.of("suppress", "onThrowable")
                          : List.of("suppress");
                  for (String attribute : required) {
                    boolean throwable =
                        annotation
                            .get()
                            .get(attribute)
                            .filter(
                                v ->
                                    v instanceof JavaClass c
                                        && c.getName().equals("java.lang.Throwable"))
                            .isPresent();
                    if (!throwable) {
                      events.add(
                          SimpleConditionEvent.violated(
                              method,
                              method.getFullName()
                                  + " lacks "
                                  + attribute
                                  + " = Throwable.class on @Advice."
                                  + type));
                    }
                  }
                }
              }
            })
        .as("T-AG-002b [ADV-02] advice declares suppress = Throwable.class (exit: onThrowable too)")
        .because("advice exceptions must never reach the application (F-A15, 10 §5.3 A-1)");
  }

  /**
   * A writable advice value of an advice method.
   *
   * @param annotation name below {@code Advice}, as written in the allow-list
   * @param description where and how the method uses it
   */
  record WritableUse(String annotation, String description) {}

  /**
   * The writable advice values of {@code method}: readOnly = false on Argument, AllArguments,
   * Return, Thrown, FieldValue or This; @Advice.FieldSetterHandle (a setter of an application
   * field); @Advice.AssignReturned.To* on the method.
   */
  static List<WritableUse> writableUses(AgentLayout layout, JavaMethod method) {
    List<WritableUse> uses = new ArrayList<>();
    for (JavaParameter parameter : method.getParameters()) {
      for (JavaAnnotation<JavaParameter> annotation : parameter.getAnnotations()) {
        Optional<String> name = layout.adviceMemberName(annotation.getRawType().getName());
        if (name.isEmpty()) {
          continue;
        }
        String where = "parameter " + parameter.getIndex() + " uses @Advice." + name.get();
        if (WRITABLE_PARAMETER_ANNOTATIONS.contains(name.get())
            && annotation.get("readOnly").filter(Boolean.FALSE::equals).isPresent()) {
          uses.add(new WritableUse(name.get(), where + "(readOnly = false)"));
        } else if (name.get().equals(FIELD_SETTER_HANDLE)) {
          uses.add(new WritableUse(name.get(), where + " (writes an application field)"));
        }
      }
    }
    for (JavaAnnotation<JavaMethod> annotation : method.getAnnotations()) {
      Optional<String> name = layout.adviceMemberName(annotation.getRawType().getName());
      if (name.isPresent() && name.get().startsWith("AssignReturned.To")) {
        uses.add(new WritableUse(name.get(), "uses @Advice." + name.get()));
      }
    }
    return uses;
  }

  /**
   * T-AG-002c [ADV-02, A-5, C-9]: writable advice values ({@link #writableUses}) only with an entry
   * in the allow-list. The module column is the Gradle project name ({@link
   * AgentLayout#gradleModuleOf}).
   */
  public static ArchRule writableValuesOnlyWhenAllowlisted(AgentLayout layout) {
    return methods()
        .that(adviceMethods(layout))
        .should(
            new ArchCondition<JavaMethod>("write advice values only when allow-listed") {
              @Override
              public void check(JavaMethod method, ConditionEvents events) {
                String module = layout.gradleModuleOf(method.getOwner().getName()).orElse("<none>");
                String owner = method.getOwner().getName();
                for (WritableUse use : writableUses(layout, method)) {
                  if (!layout
                      .allowlist()
                      .allows(module, owner, method.getName(), use.annotation())) {
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            method.getFullName()
                                + " "
                                + use.description()
                                + " without an allow-list entry (expected line: "
                                + module
                                + " | "
                                + owner
                                + " | "
                                + method.getName()
                                + " | "
                                + use.annotation()
                                + " | <reason>)"));
                  }
                }
              }
            })
        .as(
            "T-AG-002c [ADV-02] advice writes arguments, return values or fields only when"
                + " allow-listed (readOnly = false, FieldSetterHandle, AssignReturned)")
        .because(
            "only observation data may be added, through argos-advice-allowlist.txt (A-5, C-9)");
  }

  /**
   * Allow-list entries that do not match the code (stale or mistyped): the class, the advice
   * method, the module name or the writable use do not exist, or the reason is empty. An empty
   * allow-list has no problems.
   */
  static List<String> allowlistProblems(AgentLayout layout, JavaClasses classes) {
    List<String> problems = new ArrayList<>();
    for (AdviceAllowlist.Entry entry : layout.allowlist().entries()) {
      String line =
          entry.module()
              + " | "
              + entry.adviceClass()
              + " | "
              + entry.method()
              + " | "
              + entry.annotation();
      if (entry.reason().isBlank()) {
        problems.add(line + ": empty reason");
      }
      if (!classes.contain(entry.adviceClass())) {
        problems.add(line + ": no such class");
        continue;
      }
      JavaClass adviceClass = classes.get(entry.adviceClass());
      String module = layout.gradleModuleOf(adviceClass.getName()).orElse("<none>");
      if (!module.equals(entry.module())) {
        problems.add(line + ": the class belongs to module " + module);
      }
      List<JavaMethod> methods =
          adviceClass.getMethods().stream()
              .filter(
                  m ->
                      m.getName().equals(entry.method())
                          && AgentPredicates.isAdviceMethod(layout, m))
              .toList();
      if (methods.isEmpty()) {
        problems.add(line + ": no advice method " + entry.method());
        continue;
      }
      boolean used =
          methods.stream()
              .flatMap(m -> writableUses(layout, m).stream())
              .anyMatch(use -> use.annotation().equals(entry.annotation()));
      if (!used) {
        problems.add(line + ": the method uses no writable @Advice." + entry.annotation());
      }
    }
    return problems;
  }

  /**
   * T-AG-002d [A-6]: instrumentation code never replaces a method body or writes classes. Forbidden
   * in every instrumentation class: any type of ByteBuddy's {@code net.bytebuddy.implementation..}
   * (MethodDelegation, FixedValue, SuperMethodCall, StubMethod, Implementation, ...) except {@code
   * Assigner} and {@code Assigner.Typing} (Advice annotation attributes), every {@code
   * DynamicType.Builder...intercept(..)} call (also with {@code Advice.to(..).wrap(..)}), and ASM
   * {@code ClassWriter}; original and relocated forms. {@code net.bytebuddy.asm.Advice} implements
   * Implementation itself, so the rule matches packages and calls, not assignability.
   */
  public static ArchRule noMethodDelegation(AgentLayout layout) {
    List<String> prefixes = List.of("", layout.shaded() + ".");
    List<String> implementationPackages =
        prefixes.stream().map(p -> p + "net.bytebuddy.implementation").toList();
    List<String> classWriters =
        prefixes.stream()
            .flatMap(
                p ->
                    List.of(
                        p + "net.bytebuddy.jar.asm.ClassWriter",
                        p + "org.objectweb.asm.ClassWriter")
                        .stream())
            .toList();
    List<String> builders =
        prefixes.stream().map(p -> p + "net.bytebuddy.dynamic.DynamicType$Builder").toList();
    return noClasses()
        .that()
        .resideInAPackage(layout.instrumentation() + "..")
        .should()
        .dependOnClassesThat(
            DescribedPredicate.describe(
                "ByteBuddy implementation types (except Assigner, Assigner.Typing) or ASM"
                    + " ClassWriter",
                c -> {
                  String name = base(c).getName();
                  boolean implementation =
                      implementationPackages.stream().anyMatch(p -> AgentLayout.inPackage(name, p))
                          && prefixes.stream()
                              .noneMatch(
                                  p ->
                                      ALLOWED_IMPLEMENTATION_TYPES.stream()
                                          .anyMatch(allowed -> name.equals(p + allowed)));
                  return implementation || classWriters.contains(AgentLayout.topLevel(name));
                }))
        .orShould()
        .callMethodWhere(
            callTo(
                "DynamicType.Builder...intercept(..)",
                (owner, name) ->
                    name.equals("intercept")
                        && builders.stream().anyMatch(b -> owner.getName().startsWith(b))))
        .as(
            "T-AG-002d [A-6] instrumentation uses no ByteBuddy Implementation type (except"
                + " Assigner), no DynamicType.Builder.intercept(..) and no ASM ClassWriter")
        .because(
            "Argos only inlines Advice into existing methods; replacing a method body"
                + " (MethodDelegation, FixedValue, SuperMethodCall, StubMethod, intercept) or"
                + " writing classes is forbidden (DSN-02-01, 10 §5.3 A-6)");
  }

  /**
   * T-AG-003a [ADV-03, A-2, ADR-0003 C-24]: if (Argos.skip(MODULE_ID)) return; first; exit advice
   * may instead start with if (enter == null) return; on its @Advice.Enter value (ASM).
   */
  public static ArchRule guardFirst(AgentLayout layout) {
    return classes()
        .that(adviceClasses(layout))
        .should(AdviceBytecode.callTheGuardFirst(layout))
        .as(
            "T-AG-003a [ADV-03] enter and exit advice methods start with"
                + " if (Argos.skip(MODULE_ID)) return; (exit advice: or with"
                + " if (<@Advice.Enter value> == null) return;, C-24)")
        .because(
            "Argos.skip checks the kill switch, the module state and agent threads; they must"
                + " exit before any other advice code runs (10 §3, A-2). An exit advice decides by"
                + " the enter result instead of re-reading the kill switch, so a scope opened in"
                + " enter is always closed (ADR-0003 C-24)");
  }

  /** T-AG-003b [ADV-03, A-3]: no synchronized methods or blocks (ASM). */
  public static ArchRule noSynchronization(AgentLayout layout) {
    return classes()
        .that(adviceClasses(layout))
        .should(AdviceBytecode.notSynchronize())
        .as("T-AG-003b [ADV-03] advice classes do not synchronize")
        .because("advice runs on application threads and must never block them (N-01)");
  }

  /** T-AG-003c [ADV-03]: no blocking calls. */
  public static ArchRule noBlockingCalls(AgentLayout layout) {
    return noClasses()
        .that(adviceClasses(layout))
        .should()
        .callMethodWhere(callTo("a blocking method", AdviceRules::isBlocking))
        .as("T-AG-003c [ADV-03] advice classes make no blocking calls")
        .because("advice runs on application threads and must never block them (N-01)");
  }

  /** T-AG-003d [ADV-03, A-3]: no reflection, method handle calls or dynamic class loading. */
  public static ArchRule noReflection(AgentLayout layout) {
    return noClasses()
        .that(adviceClasses(layout))
        .should()
        .callMethodWhere(callTo("a reflective or class-loading method", AdviceRules::isReflective))
        .as(
            "T-AG-003d [ADV-03] advice classes use no reflection (Class.forName, Method.invoke,"
                + " MethodHandle.invoke, ...)")
        .because(
            "reflection in inlined code is slow and can load classes on the wrong loader; field"
                + " values are read with @Advice.FieldValue, never through handles");
  }

  /** T-AG-003e [ADV-03, A-3]: no Throwable construction (stack capture). */
  public static ArchRule noThrowableCreation(AgentLayout layout) {
    return noClasses()
        .that(adviceClasses(layout))
        .should()
        .callConstructorWhere(
            DescribedPredicate.describe(
                "a Throwable constructor",
                (JavaConstructorCall call) ->
                    assignableTo(call.getTargetOwner(), "java.lang.Throwable")))
        .as("T-AG-003e [ADV-03] advice classes create no Throwable (stack capture)")
        .because("new Throwable() captures the whole stack on every call (N-01)");
  }

  /**
   * ADV-04 / B-2: advice classes refer to Argos classes only in the bootstrap package and helpers
   * of their own module (helpers are injected; everything else is invisible to the application
   * class loader).
   */
  public static ArchRule referenceOnlyBootstrapAndOwnHelpers(AgentLayout layout) {
    return classes()
        .that(adviceClasses(layout))
        .should(
            agentDependenciesLimitedTo(
                layout,
                "refer to Argos classes only in the bootstrap package or own-module helpers",
                (origin, target) ->
                    AgentLayout.inPackage(target, layout.bootstrap())
                        || AgentLayout.topLevel(target).equals(AgentLayout.topLevel(origin))
                        || (AgentPredicates.isHelperName(layout, target)
                            && AgentPredicates.sameModule(layout, origin, target))))
        .as("ADV-04 [B-2] advice refers only to bootstrap classes and helpers of its own module")
        .because("inlined advice runs in the application class loader (10 §2 B-2, 10 §5.4)");
  }

  /**
   * A condition that rejects dependencies on Argos classes that {@code allowed} does not accept.
   */
  static ArchCondition<JavaClass> agentDependenciesLimitedTo(
      AgentLayout layout, String description, BiPredicate<String, String> allowed) {
    return new ArchCondition<>(description) {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
          String target = base(dependency.getTargetClass()).getName();
          if (layout.isAgentClass(target) && !allowed.test(item.getName(), target)) {
            events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
          }
        }
      }
    };
  }

  private static boolean isAdviceMember(
      AgentLayout layout, JavaAnnotation<?> annotation, String member) {
    return layout.adviceMemberName(annotation.getRawType().getName()).equals(Optional.of(member));
  }

  private static boolean isVoidClass(Object value) {
    return value instanceof JavaClass c && c.getName().equals("void");
  }

  private static String describe(Object value) {
    return value instanceof JavaClass c ? c.getName() : String.valueOf(value);
  }

  static boolean isBlocking(JavaClass owner, String name) {
    String type = owner.getName();
    return (type.equals("java.lang.Thread") && (name.equals("sleep") || name.equals("join")))
        || name.equals("wait")
        || (assignableTo(owner, "java.util.concurrent.BlockingQueue")
            && Set.of("put", "take", "putFirst", "putLast", "takeFirst", "takeLast").contains(name))
        || (assignableTo(owner, "java.util.concurrent.locks.Lock")
            && (name.equals("lock") || name.equals("lockInterruptibly")))
        || (assignableTo(owner, "java.util.concurrent.locks.Condition") && name.startsWith("await"))
        || (type.equals("java.util.concurrent.locks.LockSupport") && name.startsWith("park"))
        || (assignableTo(owner, "java.util.concurrent.Future") && name.equals("get"))
        || (assignableTo(owner, "java.util.concurrent.CountDownLatch") && name.equals("await"))
        || (assignableTo(owner, "java.util.concurrent.CyclicBarrier") && name.equals("await"))
        || (assignableTo(owner, "java.util.concurrent.Phaser") && name.startsWith("await"))
        || (assignableTo(owner, "java.util.concurrent.Semaphore") && name.startsWith("acquire"))
        || (assignableTo(owner, "java.util.concurrent.ExecutorService")
            && Set.of("invokeAll", "invokeAny", "awaitTermination").contains(name));
  }

  static boolean isReflective(JavaClass owner, String name) {
    String type = owner.getName();
    return (type.equals("java.lang.Class")
            && (name.equals("forName")
                || name.equals("newInstance")
                || name.startsWith("getMethod")
                || name.startsWith("getDeclaredMethod")
                || name.startsWith("getField")
                || name.startsWith("getDeclaredField")
                || name.startsWith("getConstructor")
                || name.startsWith("getDeclaredConstructor")))
        || (type.startsWith("java.lang.reflect.")
            && (name.equals("invoke")
                || name.equals("newInstance")
                || name.equals("setAccessible")
                || (type.equals("java.lang.reflect.Field")
                    && (name.startsWith("get") || name.startsWith("set")))))
        || (assignableTo(owner, "java.lang.ClassLoader") && name.equals("loadClass"))
        || (type.equals("java.lang.invoke.MethodHandles")
            && Set.of("lookup", "publicLookup", "privateLookupIn").contains(name))
        || (type.equals("java.lang.invoke.MethodHandles$Lookup") && name.startsWith("find"))
        // Handles from @Advice.FieldGetterHandle/FieldSetterHandle/SelfCallHandle/Handle.
        || (type.equals("java.lang.invoke.MethodHandle")
            && Set.of("invoke", "invokeExact", "invokeWithArguments").contains(name));
  }
}
