/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import static io.argos.agent.testing.arch.Fixtures.COMPLIANT;
import static io.argos.agent.testing.arch.Fixtures.VIOLATING;
import static io.argos.agent.testing.arch.Fixtures.assertPassesOnCompliant;
import static io.argos.agent.testing.arch.Fixtures.assertViolation;
import static io.argos.agent.testing.arch.Fixtures.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.AdviceInterceptInstrumentation;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.AgentThreadGuardAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.AssignReturnedAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.CodeBeforeGuardAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.CoreReferenceAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.DelegationInstrumentation;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.FieldSetterHandleAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.FixedValueInstrumentation;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.ForNameAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.FormatAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.GuardWithoutReturnAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.HelperOnlyAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.IgnoredSkipAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.InstanceMethodAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.LambdaAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.LibrarySignatureAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.MethodHandleInvokeAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.MethodInvokeAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.MethodReferenceAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.MisnamedInterceptor;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.MissingOnThrowableAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.MissingSuppressAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.NewThrowableAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.NotInlinedAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.NullCheckInEnterAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.NullCheckOtherValueAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.NullCheckWithoutReturnAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.OtherModuleAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.QueueTakeAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.RepeatOnAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.ReturnReadOnlyFalseAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.SelfCallHandleAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.SkipOnAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.SleepAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.StreamAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.StubMethodInstrumentation;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.SuperMethodCallInstrumentation;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.SynchronizedBlockAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.SynchronizedMethodAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.TryWithResourcesAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.UnguardedAdvice;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;

/**
 * Negative tests of the advice rules (WO-00: each of T-AG-001..003 fails on a violating fixture and
 * passes on the compliant agent). Fixtures: src/archFixtures, compiled with --release 8.
 */
class AdviceRulesTest {

  @Nested
  @Tag("req:F-A15")
  class TAg001 {
    @Test
    @DisplayName("T-AG-001a rejects a lambda in advice code (ASM invokedynamic check)")
    void rejectsLambda() {
      assertViolation(
          AdviceRules.noInvokeDynamic(VIOLATING),
          classes(LambdaAdvice.class),
          "LambdaAdvice.enter",
          "contains a lambda or method reference");
    }

    @Test
    @DisplayName("T-AG-001a rejects a method reference in advice code (ASM invokedynamic check)")
    void rejectsMethodReference() {
      assertViolation(
          AdviceRules.noInvokeDynamic(VIOLATING),
          classes(MethodReferenceAdvice.class),
          "MethodReferenceAdvice.enter",
          "contains a lambda or method reference");
    }

    @Test
    @DisplayName("T-AG-001a accepts advice without invokedynamic")
    void acceptsCompliant() {
      assertPassesOnCompliant(AdviceRules.noInvokeDynamic(COMPLIANT));
    }

    /**
     * 19 §13 #8 (U-8), confirmed: ArchUnit hides the synthetic lambda method and reports the lambda
     * body's calls as direct calls of the enclosing advice method, so a lambda cannot be told apart
     * from a plain call in ArchUnit's model (method references do appear, as "references method").
     * The rule therefore reads the bytecode with ASM, which sees the invokedynamic instruction in
     * both fixtures.
     */
    @Test
    @DisplayName("T-AG-001a U-8: ArchUnit cannot see a lambda; the ASM reader finds invokedynamic")
    void archUnitHidesLambdasAsmDoesNot() {
      JavaClass lambdaAdvice = classes(LambdaAdvice.class).get(LambdaAdvice.class);
      assertThat(lambdaAdvice.getCodeUnits())
          .extracting(unit -> unit.getName())
          .containsExactlyInAnyOrder("enter", "<init>");
      assertThat(lambdaAdvice.getMethodCallsFromSelf())
          .anySatisfy(
              call -> {
                assertThat(call.getOrigin().getName()).isEqualTo("enter");
                assertThat(call.getTarget().getName()).isEqualTo("record");
              });

      for (Class<?> fixture : new Class<?>[] {LambdaAdvice.class, MethodReferenceAdvice.class}) {
        ClassNode node = AdviceBytecode.read(classes(fixture).get(fixture));
        long indy =
            node.methods.stream()
                .flatMap(m -> StreamSupport.stream(m.instructions.spliterator(), false))
                .filter(InvokeDynamicInsnNode.class::isInstance)
                .count();
        assertThat(indy).as("invokedynamic in %s", fixture.getSimpleName()).isEqualTo(1);
      }
    }

    @Test
    @DisplayName("T-AG-001b rejects a non-static advice method")
    void rejectsInstanceMethod() {
      assertViolation(
          AdviceRules.staticMethodsOnly(VIOLATING),
          classes(InstanceMethodAdvice.class),
          "InstanceMethodAdvice.enter(java.lang.Object)",
          "does not have modifier STATIC");
    }

    @Test
    @DisplayName("T-AG-001b accepts static-only advice classes")
    void acceptsStatic() {
      assertPassesOnCompliant(AdviceRules.staticMethodsOnly(COMPLIANT));
    }

    @Test
    @DisplayName("T-AG-001c rejects an advice class not named *Advice")
    void rejectsMisnamed() {
      assertViolation(
          AdviceRules.adviceNaming(VIOLATING),
          classes(MisnamedInterceptor.class),
          "MisnamedInterceptor",
          "does not have simple name ending with 'Advice'");
    }

    @Test
    @DisplayName("T-AG-001c accepts *Advice names")
    void acceptsNaming() {
      assertPassesOnCompliant(AdviceRules.adviceNaming(COMPLIANT));
    }

    @Test
    @DisplayName("T-AG-001d rejects String.format, streams and try-with-resources in advice")
    void rejectsFormatStreamsAndTryWithResources() {
      assertViolation(
          AdviceRules.noFormatStreamsOrTryWithResources(VIOLATING),
          classes(FormatAdvice.class),
          "FormatAdvice.enter",
          "String.format");
      assertViolation(
          AdviceRules.noFormatStreamsOrTryWithResources(VIOLATING),
          classes(StreamAdvice.class),
          "StreamAdvice.enter",
          "java.util.stream");
      assertViolation(
          AdviceRules.noFormatStreamsOrTryWithResources(VIOLATING),
          classes(TryWithResourcesAdvice.class),
          "TryWithResourcesAdvice.enter",
          "addSuppressed");
    }

    @Test
    @DisplayName("T-AG-001d accepts advice without format, streams or try-with-resources")
    void acceptsPlainAdvice() {
      assertPassesOnCompliant(AdviceRules.noFormatStreamsOrTryWithResources(COMPLIANT));
    }
  }

  @Nested
  @Tag("req:F-A15")
  class TAg002 {
    @Test
    @DisplayName("T-AG-002a rejects skipOn, repeatOn and inline = false")
    void rejectsSkipRepeatAndNotInlined() {
      assertViolation(
          AdviceRules.noSkipRepeatOrDelegatingAdvice(VIOLATING),
          classes(SkipOnAdvice.class),
          "SkipOnAdvice.enter",
          "uses skipOn = net.bytebuddy.asm.Advice$OnNonDefaultValue");
      assertViolation(
          AdviceRules.noSkipRepeatOrDelegatingAdvice(VIOLATING),
          classes(RepeatOnAdvice.class),
          "RepeatOnAdvice.exit",
          "uses repeatOn = net.bytebuddy.asm.Advice$OnNonDefaultValue");
      assertViolation(
          AdviceRules.noSkipRepeatOrDelegatingAdvice(VIOLATING),
          classes(NotInlinedAdvice.class),
          "NotInlinedAdvice.enter",
          "uses inline = false");
    }

    @Test
    @DisplayName("T-AG-002a rejects @Advice.SelfCallHandle (calls the application method again)")
    void rejectsSelfCallHandle() {
      assertViolation(
          AdviceRules.noSkipRepeatOrDelegatingAdvice(VIOLATING),
          classes(SelfCallHandleAdvice.class),
          "SelfCallHandleAdvice.exit",
          "parameter 0 uses @Advice.SelfCallHandle");
      // Not even an allow-list entry permits it.
      AgentLayout allowlisted =
          Fixtures.withAllowlist(
              VIOLATING,
              Fixtures.allowlistOf(
                  "demo | "
                      + SelfCallHandleAdvice.class.getName()
                      + " | exit | SelfCallHandle | test"));
      assertViolation(
          AdviceRules.noSkipRepeatOrDelegatingAdvice(allowlisted),
          classes(SelfCallHandleAdvice.class),
          "@Advice.SelfCallHandle");
    }

    @Test
    @DisplayName("T-AG-002a accepts advice without skipOn, repeatOn or inline = false")
    void acceptsInlinedAdvice() {
      assertPassesOnCompliant(AdviceRules.noSkipRepeatOrDelegatingAdvice(COMPLIANT));
    }

    @Test
    @DisplayName("T-AG-002b rejects advice without suppress / onThrowable = Throwable.class")
    void rejectsMissingSuppress() {
      assertViolation(
          AdviceRules.suppressThrowable(VIOLATING),
          classes(MissingSuppressAdvice.class),
          "MissingSuppressAdvice.enter",
          "lacks suppress = Throwable.class on @Advice.OnMethodEnter");
      assertViolation(
          AdviceRules.suppressThrowable(VIOLATING),
          classes(MissingOnThrowableAdvice.class),
          "MissingOnThrowableAdvice.exit",
          "lacks onThrowable = Throwable.class on @Advice.OnMethodExit");
    }

    @Test
    @DisplayName("T-AG-002b accepts advice with suppress = Throwable.class")
    void acceptsSuppress() {
      assertPassesOnCompliant(AdviceRules.suppressThrowable(COMPLIANT));
    }

    @Test
    @DisplayName(
        "T-AG-002c rejects readOnly = false and AssignReturned without an allow-list entry")
    void rejectsWritableValues() {
      assertViolation(
          AdviceRules.writableValuesOnlyWhenAllowlisted(VIOLATING),
          classes(ReturnReadOnlyFalseAdvice.class),
          "ReturnReadOnlyFalseAdvice.exit",
          "uses @Advice.Return(readOnly = false) without an allow-list entry");
      assertViolation(
          AdviceRules.writableValuesOnlyWhenAllowlisted(VIOLATING),
          classes(AssignReturnedAdvice.class),
          "AssignReturnedAdvice.exit",
          "uses @Advice.AssignReturned.ToReturned without an allow-list entry");
    }

    @Test
    @DisplayName("T-AG-002c accepts readOnly = false when allow-listed (C-9), read-only otherwise")
    void acceptsAllowlistedWrite() {
      assertPassesOnCompliant(AdviceRules.writableValuesOnlyWhenAllowlisted(COMPLIANT));
      AgentLayout withoutAllowlist = Fixtures.withAllowlist(COMPLIANT, AdviceAllowlist.empty());
      assertViolation(
          AdviceRules.writableValuesOnlyWhenAllowlisted(withoutAllowlist),
          Fixtures.compliant(),
          "DemoHeaderAdvice.enter",
          "@Advice.Argument(readOnly = false)");
    }

    @Test
    @DisplayName("T-AG-002c rejects @Advice.FieldSetterHandle without an allow-list entry")
    void rejectsFieldSetterHandle() {
      assertViolation(
          AdviceRules.writableValuesOnlyWhenAllowlisted(VIOLATING),
          classes(FieldSetterHandleAdvice.class),
          "FieldSetterHandleAdvice.enter",
          "parameter 0 uses @Advice.FieldSetterHandle (writes an application field) without an"
              + " allow-list entry",
          "(expected line: demo | "
              + FieldSetterHandleAdvice.class.getName()
              + " | enter | FieldSetterHandle | <reason>)");
    }

    @Test
    @DisplayName("T-AG-002c accepts @Advice.FieldSetterHandle with an allow-list entry")
    void acceptsAllowlistedFieldSetterHandle() {
      AgentLayout allowlisted =
          Fixtures.withAllowlist(
              VIOLATING,
              Fixtures.allowlistOf(
                  "demo | "
                      + FieldSetterHandleAdvice.class.getName()
                      + " | enter | FieldSetterHandle | test: MDC copy"));
      AdviceRules.writableValuesOnlyWhenAllowlisted(allowlisted)
          .check(classes(FieldSetterHandleAdvice.class));
    }

    @Test
    @DisplayName(
        "T-AG-002c allow-list module key is the Gradle module name (httpclient-demo11), not the"
            + " package key")
    void allowlistUsesGradleModuleNames() {
      // Passes with the hyphenated key (fixture-advice-allowlist.txt).
      String advice =
          Fixtures.COMPLIANT_ROOT + ".instrumentation.httpclient.demo11.Demo11HeaderAdvice";
      assertThat(COMPLIANT.allowlist().allows("httpclient-demo11", advice, "enter", "Argument"))
          .isTrue();
      assertPassesOnCompliant(AdviceRules.writableValuesOnlyWhenAllowlisted(COMPLIANT));

      // Fails with the package key; the message names the expected line.
      AgentLayout packageKey =
          Fixtures.withAllowlist(
              COMPLIANT,
              Fixtures.allowlistOf(
                  "demo | "
                      + Fixtures.COMPLIANT_ROOT
                      + ".instrumentation.demo.DemoHeaderAdvice | enter | Argument | test",
                  "httpclient.demo11 | " + advice + " | enter | Argument | test"));
      assertViolation(
          AdviceRules.writableValuesOnlyWhenAllowlisted(packageKey),
          Fixtures.compliant(),
          "Demo11HeaderAdvice.enter",
          "(expected line: httpclient-demo11 | " + advice + " | enter | Argument | <reason>)");
    }

    @Test
    @DisplayName("T-AG-002c allow-list entries match existing advice (no stale entries)")
    void detectsStaleAllowlistEntries() {
      assertThat(AdviceRules.allowlistProblems(COMPLIANT, Fixtures.compliant())).isEmpty();
      assertThat(
              AdviceRules.allowlistProblems(
                  Fixtures.withAllowlist(COMPLIANT, AdviceAllowlist.empty()), Fixtures.compliant()))
          .isEmpty();

      String demo = Fixtures.COMPLIANT_ROOT + ".instrumentation.demo.";
      String advice =
          Fixtures.COMPLIANT_ROOT + ".instrumentation.httpclient.demo11.Demo11HeaderAdvice";
      AgentLayout stale =
          Fixtures.withAllowlist(
              COMPLIANT,
              Fixtures.allowlistOf(
                  "httpclient.demo11 | " + advice + " | enter | Argument | wrong module key",
                  "demo | " + demo + "GoneAdvice | enter | Argument | class removed",
                  "demo | " + demo + "DemoHeaderAdvice | exit | Argument | method renamed",
                  "demo | " + demo + "DemoAdvice | enter | Argument | argument is read-only",
                  "demo | " + demo + "DemoHeaderAdvice | enter | Return | wrong annotation"));
      assertThat(AdviceRules.allowlistProblems(stale, Fixtures.compliant()))
          .containsExactly(
              "httpclient.demo11 | "
                  + advice
                  + " | enter | Argument: the class belongs to module httpclient-demo11",
              "demo | " + demo + "GoneAdvice | enter | Argument: no such class",
              "demo | " + demo + "DemoHeaderAdvice | exit | Argument: no advice method exit",
              "demo | "
                  + demo
                  + "DemoAdvice | enter | Argument: the method uses no writable @Advice.Argument",
              "demo | "
                  + demo
                  + "DemoHeaderAdvice | enter | Return: the method uses no writable @Advice.Return");
    }

    @Test
    @DisplayName(
        "T-AG-002d rejects ByteBuddy Implementation types: MethodDelegation, FixedValue,"
            + " SuperMethodCall, StubMethod")
    void rejectsMethodDelegation() {
      assertViolation(
          AdviceRules.noMethodDelegation(VIOLATING),
          classes(DelegationInstrumentation.class),
          "DelegationInstrumentation",
          "net.bytebuddy.implementation.MethodDelegation");
      assertViolation(
          AdviceRules.noMethodDelegation(VIOLATING),
          classes(FixedValueInstrumentation.class),
          "FixedValueInstrumentation",
          "net.bytebuddy.implementation.FixedValue",
          "intercept(");
      assertViolation(
          AdviceRules.noMethodDelegation(VIOLATING),
          classes(SuperMethodCallInstrumentation.class),
          "SuperMethodCallInstrumentation",
          "net.bytebuddy.implementation.SuperMethodCall");
      assertViolation(
          AdviceRules.noMethodDelegation(VIOLATING),
          classes(StubMethodInstrumentation.class),
          "StubMethodInstrumentation",
          "net.bytebuddy.implementation.StubMethod");
    }

    @Test
    @DisplayName("T-AG-002d rejects DynamicType.Builder.intercept(..), also with Advice")
    void rejectsIntercept() {
      assertViolation(
              AdviceRules.noMethodDelegation(VIOLATING),
              classes(AdviceInterceptInstrumentation.class),
              "AdviceInterceptInstrumentation.transform",
              "net.bytebuddy.dynamic.DynamicType$Builder$MethodDefinition$ImplementationDefinition"
                  + ".intercept(")
          // Only the call: no net.bytebuddy.implementation type is referenced by the class itself.
          .hasMessageContaining("was violated (1 times)");
    }

    @Test
    @DisplayName(
        "T-AG-002d accepts Advice.to(..) and Advice annotations with Assigner.Typing.DYNAMIC")
    void acceptsAdviceInstrumentation() {
      assertPassesOnCompliant(AdviceRules.noMethodDelegation(COMPLIANT));
    }
  }

  @Nested
  @Tag("req:F-A15")
  @Tag("req:N-01")
  class TAg003 {
    private static final String EXPECTED_GUARD =
        "expected the first statement if ("
            + Fixtures.VIOLATING_ROOT
            + ".bootstrap.Argos.skip(MODULE_ID)) return;";

    @Test
    @DisplayName("T-AG-003a rejects advice whose first call is not Argos.skip (ASM)")
    void rejectsUnguarded() {
      assertViolation(
          AdviceRules.guardFirst(VIOLATING),
          classes(UnguardedAdvice.class),
          "UnguardedAdvice.enter",
          "calls " + Fixtures.VIOLATING_ROOT + ".bootstrap.Argos.tracer()Ljava/lang/Object; first",
          EXPECTED_GUARD);
    }

    @Test
    @DisplayName("T-AG-003a rejects an exit advice that only delegates to a helper without guard")
    void rejectsHelperDelegationWithoutGuard() {
      assertViolation(
              AdviceRules.guardFirst(VIOLATING),
              classes(HelperOnlyAdvice.class),
              "HelperOnlyAdvice.exit(Ljava/lang/Object;)V",
              "calls "
                  + Fixtures.VIOLATING_ROOT
                  + ".instrumentation.demo.DemoHelper.record(Ljava/lang/Object;)V first",
              EXPECTED_GUARD)
          .hasMessageNotContaining("HelperOnlyAdvice.enter");
    }

    @Test
    @DisplayName("T-AG-003a rejects a guard on ArgosThread.isAgentThread() alone")
    void rejectsAgentThreadGuard() {
      assertViolation(
          AdviceRules.guardFirst(VIOLATING),
          classes(AgentThreadGuardAdvice.class),
          "AgentThreadGuardAdvice.enter",
          "calls " + Fixtures.VIOLATING_ROOT + ".bootstrap.ArgosThread.isAgentThread()Z first");
    }

    @Test
    @DisplayName("T-AG-003a rejects Argos.skip whose result is ignored")
    void rejectsIgnoredSkipResult() {
      assertViolation(
          AdviceRules.guardFirst(VIOLATING),
          classes(IgnoredSkipAdvice.class),
          "IgnoredSkipAdvice.enter",
          "does not branch on the result of skip(int) (next instruction: ISTORE)");
    }

    @Test
    @DisplayName("T-AG-003a rejects a guard whose skip path runs code instead of returning")
    void rejectsGuardWithoutReturn() {
      assertViolation(
          AdviceRules.guardFirst(VIOLATING),
          classes(GuardWithoutReturnAdvice.class),
          "GuardWithoutReturnAdvice.enter",
          "does not return at once when skip(int) is true");
    }

    @Test
    @DisplayName("T-AG-003a rejects code before the guard")
    void rejectsCodeBeforeGuard() {
      assertViolation(
          AdviceRules.guardFirst(VIOLATING),
          classes(CodeBeforeGuardAdvice.class),
          "CodeBeforeGuardAdvice.enter",
          "runs other code before the guard");
    }

    @Test
    @DisplayName(
        "T-AG-003a accepts enter and exit advice starting with if (Argos.skip(ID)) return; (IFEQ"
            + " and IFNE forms)")
    void acceptsGuard() {
      assertPassesOnCompliant(AdviceRules.guardFirst(COMPLIANT));
      // The compliant agent has guarded enter and exit methods, the negated form, and void,
      // reference (return null) and exit-only advice.
      assertThat(Fixtures.compliant().stream().map(JavaClass::getSimpleName))
          .contains("DemoAdvice", "DemoExitOnlyAdvice", "DemoNegatedGuardAdvice");
    }

    @Test
    @DisplayName(
        "T-AG-003a C-24 accepts exit advice that starts with a null check of its @Advice.Enter"
            + " value (== null return and != null block forms, Enter not the first parameter)")
    void acceptsEnterNullCheckInExitAdvice() {
      assertPassesOnCompliant(AdviceRules.guardFirst(COMPLIANT));
      assertThat(Fixtures.compliant().stream().map(JavaClass::getSimpleName))
          .contains("DemoEnterNullCheckAdvice", "DemoEnterNonNullBlockAdvice");
    }

    @Test
    @DisplayName("T-AG-003a C-24 rejects an exit advice whose null check tests another value")
    void rejectsNullCheckOfOtherValue() {
      assertViolation(
          AdviceRules.guardFirst(VIOLATING),
          classes(NullCheckOtherValueAdvice.class),
          "NullCheckOtherValueAdvice.exit",
          "does not start with a null check of the @Advice.Enter parameter (first instruction:"
              + " ALOAD 1)",
          "in exit advice, if (<@Advice.Enter parameter> == null) return; (C-24)");
    }

    @Test
    @DisplayName(
        "T-AG-003a C-24 rejects an exit advice that runs code when the enter value is null")
    void rejectsNullCheckWithoutReturn() {
      assertViolation(
          AdviceRules.guardFirst(VIOLATING),
          classes(NullCheckWithoutReturnAdvice.class),
          "NullCheckWithoutReturnAdvice.exit",
          "does not return at once when the @Advice.Enter value is null");
    }

    @Test
    @DisplayName("T-AG-003a C-24 rejects a null check as the guard of enter advice")
    void rejectsNullCheckInEnterAdvice() {
      assertViolation(
              AdviceRules.guardFirst(VIOLATING),
              classes(NullCheckInEnterAdvice.class),
              "NullCheckInEnterAdvice.enter",
              "calls " + Fixtures.VIOLATING_ROOT + ".instrumentation.demo.DemoHelper.record",
              EXPECTED_GUARD)
          .hasMessageNotContaining("or, in exit advice");
    }

    @Test
    @DisplayName("T-AG-003b rejects synchronized blocks and methods (ASM)")
    void rejectsSynchronization() {
      assertViolation(
          AdviceRules.noSynchronization(VIOLATING),
          classes(SynchronizedBlockAdvice.class),
          "SynchronizedBlockAdvice.enter",
          "enters a monitor (synchronized block)");
      assertViolation(
          AdviceRules.noSynchronization(VIOLATING),
          classes(SynchronizedMethodAdvice.class),
          "SynchronizedMethodAdvice.enter",
          "is a synchronized method");
    }

    @Test
    @DisplayName("T-AG-003b accepts advice without synchronization")
    void acceptsUnsynchronized() {
      assertPassesOnCompliant(AdviceRules.noSynchronization(COMPLIANT));
    }

    @Test
    @DisplayName("T-AG-003c rejects Thread.sleep and BlockingQueue.put/take")
    void rejectsBlocking() {
      assertViolation(
          AdviceRules.noBlockingCalls(VIOLATING),
          classes(SleepAdvice.class),
          "SleepAdvice.enter",
          "calls method <java.lang.Thread.sleep(long)>");
      assertViolation(
              AdviceRules.noBlockingCalls(VIOLATING),
              classes(QueueTakeAdvice.class),
              "QueueTakeAdvice.enter",
              "calls method <java.util.concurrent.BlockingQueue.take()>")
          .hasMessageContaining(
              "calls method <java.util.concurrent.BlockingQueue.put(java.lang.Object)>");
    }

    @Test
    @DisplayName("T-AG-003c accepts non-blocking advice")
    void acceptsNonBlocking() {
      assertPassesOnCompliant(AdviceRules.noBlockingCalls(COMPLIANT));
    }

    @Test
    @DisplayName("T-AG-003d rejects Class.forName, Method.invoke and MethodHandle.invoke")
    void rejectsReflection() {
      assertViolation(
          AdviceRules.noReflection(VIOLATING),
          classes(ForNameAdvice.class),
          "ForNameAdvice.enter",
          "calls method <java.lang.Class.forName(java.lang.String)>");
      assertViolation(
          AdviceRules.noReflection(VIOLATING),
          classes(MethodInvokeAdvice.class),
          "MethodInvokeAdvice.enter",
          "calls method <java.lang.reflect.Method.invoke(");
      assertViolation(
          AdviceRules.noReflection(VIOLATING),
          classes(MethodHandleInvokeAdvice.class),
          "MethodHandleInvokeAdvice.enter",
          "calls method <java.lang.invoke.MethodHandle.invoke(");
    }

    @Test
    @DisplayName("T-AG-003d accepts advice without reflection")
    void acceptsNoReflection() {
      assertPassesOnCompliant(AdviceRules.noReflection(COMPLIANT));
    }

    @Test
    @DisplayName("T-AG-003e rejects new Throwable (stack capture)")
    void rejectsThrowableCreation() {
      assertViolation(
          AdviceRules.noThrowableCreation(VIOLATING),
          classes(NewThrowableAdvice.class),
          "NewThrowableAdvice.enter",
          "calls constructor <java.lang.IllegalStateException.<init>(java.lang.String)>");
    }

    @Test
    @DisplayName("T-AG-003e accepts advice that creates no Throwable")
    void acceptsNoThrowable() {
      assertPassesOnCompliant(AdviceRules.noThrowableCreation(COMPLIANT));
    }
  }

  @Nested
  @Tag("req:F-A15")
  class Adv004 {
    @Test
    @DisplayName("ADV-04 [B-2] rejects advice that refers to core or another module's helper")
    void rejectsForeignReferences() {
      assertViolation(
          AdviceRules.referenceOnlyBootstrapAndOwnHelpers(VIOLATING),
          classes(CoreReferenceAdvice.class),
          "CoreReferenceAdvice.enter",
          Fixtures.VIOLATING_ROOT + ".core.CoreService.submit");
      assertViolation(
          AdviceRules.referenceOnlyBootstrapAndOwnHelpers(VIOLATING),
          classes(OtherModuleAdvice.class),
          "OtherModuleAdvice.enter",
          Fixtures.VIOLATING_ROOT + ".instrumentation.other.OtherHelper.record");
    }

    @Test
    @DisplayName("ADV-04 [B-2] accepts advice that uses bootstrap classes and its own helpers")
    void acceptsBootstrapAndOwnHelpers() {
      assertPassesOnCompliant(AdviceRules.referenceOnlyBootstrapAndOwnHelpers(COMPLIANT));
    }

    @Test
    @DisplayName(
        "ADV-04b [C-223 (8)] rejects a library type as an advice parameter, array element or"
            + " return type")
    void rejectsLibrarySignatureTypes() {
      assertViolation(
          AdviceRules.signatureOnlyJdkOrBootstrapTypes(VIOLATING),
          classes(LibrarySignatureAdvice.class),
          "LibrarySignatureAdvice.enter("
              + Fixtures.BASE
              + ".app.Order) uses the library type "
              + Fixtures.BASE
              + ".app.Order in its signature",
          "uses the library type " + Fixtures.BASE + ".thirdparty.ThirdPartyQueue in its signature",
          "uses the library type [L" + Fixtures.BASE + ".app.Order; in its signature");
    }

    @Test
    @DisplayName(
        "ADV-04b [C-223 (8)] accepts advice whose signatures use primitives, java.* and bootstrap"
            + " types")
    void acceptsJdkAndBootstrapSignatures() {
      assertPassesOnCompliant(AdviceRules.signatureOnlyJdkOrBootstrapTypes(COMPLIANT));
    }
  }
}
