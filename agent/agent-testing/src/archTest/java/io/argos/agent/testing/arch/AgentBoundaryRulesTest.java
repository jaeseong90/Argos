/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import static io.argos.agent.testing.arch.Fixtures.COMPLIANT;
import static io.argos.agent.testing.arch.Fixtures.VIOLATING;
import static io.argos.agent.testing.arch.Fixtures.VIOLATING_ROOT;
import static io.argos.agent.testing.arch.Fixtures.assertPassesOnCompliant;
import static io.argos.agent.testing.arch.Fixtures.assertViolation;
import static io.argos.agent.testing.arch.Fixtures.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.argos.agent.testing.arch.fixture.violating.bootstrap.TraceContext;
import io.argos.agent.testing.arch.fixture.violating.bootstrap.UsesJavaSql;
import io.argos.agent.testing.arch.fixture.violating.bootstrap.UsesJul;
import io.argos.agent.testing.arch.fixture.violating.bootstrap.UsesThirdParty;
import io.argos.agent.testing.arch.fixture.violating.core.PrintsToConsole;
import io.argos.agent.testing.arch.fixture.violating.core.ReferencesModule;
import io.argos.agent.testing.arch.fixture.violating.core.trace.StepBuffer;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.ByteBuddyApiAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.CoreReferenceHelper;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.MissingSuppressAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.RepeatOnAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.SkipOnAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.ThirdPartyAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.ThirdPartyHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Negative tests of the package boundary rules (10 §2 B-1..B-4), AGT-01..AGT-03 (19 §5.3) and
 * T-AG-004: each rule fails on a violating fixture and passes on the compliant agent.
 */
class AgentBoundaryRulesTest {

  @Nested
  @Tag("req:F-A36")
  class TAg004 {
    @Test
    @DisplayName("T-AG-004 rejects a TraceContext field that can hold an application object")
    void rejectsApplicationTypeInTraceContext() {
      assertViolation(
              AgentBoundaryRules.traceStateFieldTypes(VIOLATING),
              classes(TraceContext.class),
              "TraceContext.order has type io.argos.agent.testing.arch.fixture.app.Order")
          .hasMessageContaining("TraceContext.attributes has type java.util.Map");
    }

    @Test
    @DisplayName("T-AG-004 rejects a step builder field that can hold an application object")
    void rejectsApplicationTypeInStepBuilder() {
      assertViolation(
          AgentBoundaryRules.traceStateFieldTypes(VIOLATING),
          classes(StepBuffer.class),
          "StepBuffer.lastOrder has type io.argos.agent.testing.arch.fixture.app.Order");
    }

    @Test
    @DisplayName("T-AG-004 accepts primitive, String, bootstrap, WeakReference and coreRef fields")
    void acceptsCompliant() {
      assertPassesOnCompliant(AgentBoundaryRules.traceStateFieldTypes(COMPLIANT));
    }
  }

  @Nested
  @Tag("req:F-A31")
  class B1 {
    @Test
    @DisplayName("AGT-01 [B-1] rejects bootstrap classes that use a library or java.sql")
    void rejectsNonJdkDependencies() {
      assertViolation(
          AgentBoundaryRules.bootstrapDependsOnJdkOnly(VIOLATING),
          classes(UsesThirdParty.class),
          "UsesThirdParty",
          "io.argos.agent.testing.arch.fixture.thirdparty.ThirdPartyQueue");
      assertViolation(
          AgentBoundaryRules.bootstrapDependsOnJdkOnly(VIOLATING),
          classes(UsesJavaSql.class),
          "UsesJavaSql",
          "java.sql.Connection");
    }

    @Test
    @DisplayName("AGT-01 [B-1] accepts a bootstrap module (and entry point) that uses java.* only")
    void acceptsJdkOnly() {
      assertPassesOnCompliant(AgentBoundaryRules.bootstrapDependsOnJdkOnly(COMPLIANT));
    }
  }

  @Nested
  @Tag("req:F-A31")
  class B2 {
    @Test
    @DisplayName("B-2 rejects a helper that refers to core")
    void rejectsHelperUsingCore() {
      assertViolation(
          AgentBoundaryRules.helpersReferenceOnlyBootstrap(VIOLATING),
          classes(CoreReferenceHelper.class),
          "CoreReferenceHelper.submit",
          VIOLATING_ROOT + ".core.CoreService.submit");
    }

    @Test
    @DisplayName("B-2 accepts helpers that use bootstrap classes only")
    void acceptsBootstrapHelpers() {
      assertPassesOnCompliant(AgentBoundaryRules.helpersReferenceOnlyBootstrap(COMPLIANT));
    }
  }

  @Nested
  @Tag("req:F-A31")
  class B3 {
    @Test
    @DisplayName("B-3 rejects core code that refers to an instrumentation module")
    void rejectsCoreUsingModule() {
      assertViolation(
          AgentBoundaryRules.coreUsesOnlyInstrumentationApi(VIOLATING),
          classes(ReferencesModule.class),
          "ReferencesModule",
          VIOLATING_ROOT + ".instrumentation.demo.DemoInstrumentation");
    }

    @Test
    @DisplayName("B-3 accepts core code that uses instrumentation.api")
    void acceptsCoreUsingApi() {
      assertPassesOnCompliant(AgentBoundaryRules.coreUsesOnlyInstrumentationApi(COMPLIANT));
    }
  }

  @Nested
  @Tag("req:F-A31")
  class B4 {
    @Test
    @DisplayName("B-4 rejects advice and helpers that use a shaded library (incl. ByteBuddy API)")
    void rejectsShadedLibraries() {
      assertViolation(
          AgentBoundaryRules.adviceAndHelpersUseNoShadedLibraries(VIOLATING),
          classes(ThirdPartyAdvice.class),
          "ThirdPartyAdvice.enter",
          "io.argos.agent.testing.arch.fixture.thirdparty.ThirdPartyQueue");
      assertViolation(
          AgentBoundaryRules.adviceAndHelpersUseNoShadedLibraries(VIOLATING),
          classes(ThirdPartyHelper.class),
          "ThirdPartyHelper",
          "io.argos.agent.testing.arch.fixture.thirdparty.ThirdPartyQueue");
      assertViolation(
          AgentBoundaryRules.adviceAndHelpersUseNoShadedLibraries(VIOLATING),
          classes(ByteBuddyApiAdvice.class),
          "ByteBuddyApiAdvice.enter",
          "net.bytebuddy.ClassFileVersion.ofThisVm()");
    }

    @Test
    @DisplayName("B-4 accepts Advice annotations on advice and libraries in core/module classes")
    void acceptsAnnotationsAndCoreUse() {
      assertPassesOnCompliant(AgentBoundaryRules.adviceAndHelpersUseNoShadedLibraries(COMPLIANT));
    }

    @Test
    @DisplayName(
        "B-4 treats Advice annotation members and defaults (NoExceptionHandler) as metadata")
    void acceptsAnnotationMemberTypes() {
      AgentBoundaryRules.adviceAndHelpersUseNoShadedLibraries(VIOLATING)
          .allowEmptyShould(false)
          .check(classes(MissingSuppressAdvice.class, SkipOnAdvice.class, RepeatOnAdvice.class));
    }
  }

  @Nested
  @Tag("req:F-A35")
  class Agt02 {
    @Test
    @DisplayName("AGT-02 rejects java.util.logging in agent code")
    void rejectsJul() {
      assertViolation(
          AgentBoundaryRules.noLoggingFrameworks(VIOLATING),
          classes(UsesJul.class),
          "UsesJul",
          "java.util.logging.Logger");
    }

    @Test
    @DisplayName("AGT-02 accepts agent code without a logging framework")
    void acceptsOwnLogger() {
      assertPassesOnCompliant(AgentBoundaryRules.noLoggingFrameworks(COMPLIANT));
    }

    @Test
    @DisplayName("AGT-02 rejects System.out and printStackTrace() outside core.log/core.check")
    void rejectsConsole() {
      assertViolation(
          AgentBoundaryRules.noConsoleOutput(VIOLATING),
          classes(PrintsToConsole.class),
          "PrintsToConsole.report",
          "gets field <java.lang.System.out>",
          "calls method <java.lang.Exception.printStackTrace()>");
    }

    @Test
    @DisplayName("AGT-02 accepts the stderr fallback sink and the --check report")
    void acceptsConsolePackages() {
      assertPassesOnCompliant(AgentBoundaryRules.noConsoleOutput(COMPLIANT));
    }
  }

  @Nested
  @Tag("req:F-A35")
  class Agt03 {
    @Test
    @DisplayName("AGT-03 rejects a reference to sun.misc.Unsafe")
    void rejectsUnsafe(@TempDir Path dir) throws IOException {
      // Generated with ASM: javac --release 8 does not compile against sun.misc.Unsafe.
      String name = VIOLATING_ROOT.replace('.', '/') + "/core/UsesUnsafe";
      Path classFile = dir.resolve(name + ".class");
      Files.createDirectories(classFile.getParent());
      Files.write(classFile, unsafeUser(name));
      JavaClasses imported = new ClassFileImporter().importPath(dir);
      assertThat(imported.contain(name.replace('/', '.'))).isTrue();
      assertViolation(
          AgentBoundaryRules.noUnsafe(VIOLATING), imported, "UsesUnsafe", "sun.misc.Unsafe");
    }

    @Test
    @DisplayName("AGT-03 accepts agent code without Unsafe")
    void acceptsNoUnsafe() {
      assertPassesOnCompliant(AgentBoundaryRules.noUnsafe(COMPLIANT));
    }

    private byte[] unsafeUser(String internalName) {
      ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
      writer.visit(
          Opcodes.V1_8,
          Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
          internalName,
          null,
          "java/lang/Object",
          null);
      writer
          .visitField(
              Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "unsafe", "Lsun/misc/Unsafe;", null, null)
          .visitEnd();
      MethodVisitor method =
          writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "address", "()J", null, null);
      method.visitCode();
      method.visitFieldInsn(Opcodes.GETSTATIC, internalName, "unsafe", "Lsun/misc/Unsafe;");
      method.visitLdcInsn(16L);
      method.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL, "sun/misc/Unsafe", "allocateMemory", "(J)J", false);
      method.visitInsn(Opcodes.LRETURN);
      method.visitMaxs(0, 0);
      method.visitEnd();
      writer.visitEnd();
      return writer.toByteArray();
    }
  }

  @Test
  @DisplayName("Every boundary and advice rule passes on the complete compliant agent")
  void compliantAgentPassesEveryRule() {
    JavaClasses compliant = Fixtures.compliant();
    assertThat(compliant.size()).isGreaterThan(15);
    AdviceRules.all(COMPLIANT).forEach(rule -> rule.allowEmptyShould(false).check(compliant));
    AgentBoundaryRules.all(COMPLIANT)
        .forEach(rule -> rule.allowEmptyShould(false).check(compliant));
  }
}
