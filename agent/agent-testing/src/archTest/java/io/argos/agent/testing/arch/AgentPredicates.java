/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import java.util.Optional;
import java.util.function.BiPredicate;

/** Predicates over agent classes shared by {@link AdviceRules} and {@link AgentBoundaryRules}. */
final class AgentPredicates {
  static final String ADVICE = AgentLayout.ADVICE;
  static final String ON_METHOD_ENTER = "OnMethodEnter";
  static final String ON_METHOD_EXIT = "OnMethodExit";

  private AgentPredicates() {}

  /**
   * The {@code @Advice.<member>} annotation of {@code method} (original or relocated ByteBuddy),
   * e.g. member {@code OnMethodEnter}.
   */
  static Optional<JavaAnnotation<JavaMethod>> adviceAnnotation(
      AgentLayout layout, JavaMethod method, String member) {
    return method.getAnnotations().stream()
        .filter(a -> layout.adviceMemberName(a.getRawType().getName()).equals(Optional.of(member)))
        .findFirst();
  }

  static boolean isAdviceMethod(AgentLayout layout, JavaMethod method) {
    return adviceAnnotation(layout, method, ON_METHOD_ENTER).isPresent()
        || adviceAnnotation(layout, method, ON_METHOD_EXIT).isPresent();
  }

  static boolean isAdviceClass(AgentLayout layout, JavaClass javaClass) {
    return javaClass.getMethods().stream().anyMatch(m -> isAdviceMethod(layout, m));
  }

  /** Classes of the agent that declare an {@code @Advice.OnMethodEnter/Exit} method. */
  static DescribedPredicate<JavaClass> adviceClasses(AgentLayout layout) {
    return DescribedPredicate.describe(
        "advice classes (declaring @Advice.OnMethodEnter/@Advice.OnMethodExit methods)",
        c -> layout.isAgentClass(c.getName()) && isAdviceClass(layout, c));
  }

  static DescribedPredicate<JavaMethod> adviceMethods(AgentLayout layout) {
    return DescribedPredicate.describe(
        "advice methods (@Advice.OnMethodEnter/@Advice.OnMethodExit)",
        m -> layout.isAgentClass(m.getOwner().getName()) && isAdviceMethod(layout, m));
  }

  /**
   * Helper classes: injected into the application class loader (10 §5.4). Identified by name: a
   * class of an instrumentation module whose top-level class name contains "Helper".
   */
  static boolean isHelperName(AgentLayout layout, String className) {
    if (layout.moduleOf(className).isEmpty()
        || AgentLayout.inPackage(className, layout.instrumentationApi())) {
      return false;
    }
    String topLevel = AgentLayout.topLevel(className);
    return topLevel.substring(topLevel.lastIndexOf('.') + 1).contains("Helper");
  }

  static DescribedPredicate<JavaClass> helperClasses(AgentLayout layout) {
    return DescribedPredicate.describe(
        "helper classes (instrumentation classes named *Helper*)",
        c -> isHelperName(layout, c.getName()) && !isAdviceClass(layout, c));
  }

  static DescribedPredicate<JavaClass> agentClasses(AgentLayout layout) {
    return DescribedPredicate.describe(
        "agent classes (below " + layout.root() + ")", c -> layout.isAgentClass(c.getName()));
  }

  static boolean sameModule(AgentLayout layout, String a, String b) {
    return layout.moduleOf(a).isPresent() && layout.moduleOf(a).equals(layout.moduleOf(b));
  }

  /** The base element type of an array class, the class itself otherwise. */
  static JavaClass base(JavaClass javaClass) {
    return javaClass.isArray() ? javaClass.getBaseComponentType() : javaClass;
  }

  static DescribedPredicate<JavaMethodCall> callTo(
      String description, BiPredicate<JavaClass, String> ownerAndName) {
    return DescribedPredicate.describe(
        description, call -> ownerAndName.test(call.getTargetOwner(), call.getName()));
  }

  static boolean assignableTo(JavaClass owner, String type) {
    return owner.getName().equals(type) || owner.isAssignableTo(type);
  }
}
