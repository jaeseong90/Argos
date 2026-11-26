/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.argos.agent.bootstrap.ContextCarrier;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.SafeMatchers;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0010 C-256 (1), the agent-core defect T-MX-112 and T-MX-113 found: a class whose interface
 * the bootstrap loader defined without a class file (OpenTelemetry's {@code
 * VirtualFieldAccessor$...} interfaces) failed to transform with {@code NoSuchTypeException}.
 *
 * <p>Without a second Java agent the case is reproduced with a class loader that hides the class
 * file of a JDK interface ({@link RandomAccess}, loaded by the bootstrap loader) from the locator,
 * the way the bootstrap loader has no class file for a type another agent defined at run time. The
 * fixture task implements {@link Runnable} and that interface; it goes through the real agent
 * builder pipeline (raw transformer, {@link ModuleTransformer}, advice, context field, a safe
 * hierarchy matcher).
 */
@Tag("req:N-27")
@Tag("req:F-A32")
class ForeignAgentTypesTest {
  private static final String PACKAGE = "fixture.coexist.";
  private static final String TASK = PACKAGE + "Task";
  private static final String HEADER = PACKAGE + "Header";
  private static final String HIDDEN = RandomAccess.class.getName();
  private static final String HIDDEN_RESOURCE = HIDDEN.replace('.', '/') + ".class";

  /** Counts the runs of the advice inlined into {@code run()}. */
  public static final class Runs {
    public static final AtomicInteger COUNT = new AtomicInteger();

    private Runs() {}
  }

  /** Advice applied to {@code run()} together with the context field. */
  static final class RunAdvice {
    private RunAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter() {
      Runs.COUNT.incrementAndGet();
    }
  }

  private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
  private final List<String> resolutionErrors =
      Collections.synchronizedList(new ArrayList<String>());
  private byte[] task;

  @BeforeEach
  void setUp() {
    task =
        new ByteBuddy(ClassFileVersion.JAVA_V8)
            .subclass(Object.class)
            .name(TASK)
            .implement(Runnable.class, RandomAccess.class)
            .defineMethod("run", void.class, Visibility.PUBLIC)
            .intercept(StubMethod.INSTANCE)
            .make()
            .getBytes();
    Runs.COUNT.set(0);
    SafeMatchers.setErrorSink(
        new SafeMatchers.ErrorSink() {
          @Override
          public void onResolutionError(String typeName, Throwable error) {
            resolutionErrors.add(typeName + ": " + error);
          }
        });
  }

  @AfterEach
  void tearDown() {
    SafeMatchers.setErrorSink(null);
  }

  @Test
  @DisplayName(
      "T-AG-005b a bootstrap type without a class file resolves from the loaded class; app types"
          + " and unknown names stay unresolved (no class loading outside the bootstrap loader)")
  void poolFallback() {
    HidingLoader loader = new HidingLoader();
    ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(loader);
    // ByteBuddy's default pool (the old strategy) cannot describe the hidden interface.
    TypePool plain = AgentBuilder.PoolStrategy.Default.FAST.typePool(locator, loader);
    assertThat(plain.describe(HIDDEN).isResolved()).isFalse();

    TypePool pool = ArgosAgentBuilder.POOL_STRATEGY.typePool(locator, loader);
    TypePool.Resolution hidden = pool.describe(HIDDEN);
    assertThat(hidden.isResolved()).isTrue();
    assertThat(hidden.resolve().isInterface()).isTrue();
    assertThat(hidden.resolve().represents(RandomAccess.class)).isTrue();
    assertThat(pool.describe(Runnable.class.getName()).isResolved()).isTrue();

    // Nothing to locate at all: bootstrap types resolve, an application class does not (the
    // fallback never asks the application loader), an unknown name stays unresolved.
    TypePool none = new BootstrapTypeFallbackPool(ClassFileLocator.NoOp.INSTANCE);
    assertThat(none.describe(Runnable.class.getName()).isResolved()).isTrue();
    assertThat(none.describe(ForeignAgentTypesTest.class.getName()).isResolved()).isFalse();
    assertThat(none.describe("fixture.coexist.Missing").isResolved()).isFalse();
    assertThat(BootstrapTypeFallbackPool.definedByBootstrap("fixture.coexist.Missing")).isNull();
  }

  @Test
  @DisplayName(
      "T-AG-005b a task implementing an interface the bootstrap loader has without a class file is"
          + " matched by a safe hierarchy matcher, transformed (advice, context field) and onError"
          + " stays 0; ByteBuddy's default pool fails the same class with NoSuchTypeException")
  void transformsThroughAgentBuilder() throws Exception {
    HidingLoader loader = new HidingLoader();
    byte[] transformed =
        rawTransformer(false).transform(loader, TASK.replace('.', '/'), null, null, task);
    assertThat(errors).isEmpty();
    assertThat(resolutionErrors).isEmpty();
    assertThat(transformed).isNotNull();

    Class<?> type = loader.define(TASK, transformed);
    assertThat(type.getInterfaces()).contains(Runnable.class, RandomAccess.class);
    assertThat(ContextCarrier.class.isAssignableFrom(type)).isTrue();
    ((Runnable) type.getDeclaredConstructor().newInstance()).run();
    assertThat(Runs.COUNT.get()).isEqualTo(1);

    // The same class with the builder before C-256 (1) (PoolStrategy.Default.FAST and
    // REDEFINE_FROZEN): the listener sees NoSuchTypeException (ByteBuddy also rethrows it here;
    // the JVM would keep the class unchanged).
    final ClassFileTransformer before = rawTransformer(true);
    Throwable thrown =
        catchThrowable(
            () -> before.transform(new HidingLoader(), TASK.replace('.', '/'), null, null, task));
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0))
        .isInstanceOf(TypePool.Resolution.NoSuchTypeException.class)
        .hasMessageContaining(HIDDEN);
    if (thrown != null) {
      assertThat(thrown).hasRootCauseInstanceOf(TypePool.Resolution.NoSuchTypeException.class);
    }
  }

  @Test
  @DisplayName(
      "T-AG-005c retransforming a loaded class keeps the interfaces of the class file it is given"
          + " (DECORATE), also one that reflection does not show; REDEFINE_FROZEN wrote the header"
          + " from reflection and dropped it (the JVM rejects that retransformation)")
  void retransformationKeepsInterfaces() throws Exception {
    HidingLoader loader = new HidingLoader();
    Class<?> loaded = loader.define(HEADER, headerFixture(false));
    assertThat(loaded.getInterfaces()).containsExactly(Callable.class);
    // The bytes of the retransformation carry one more interface than reflection reports: the
    // OpenTelemetry agent adds VirtualFieldAccessor interfaces at definition, reproduces them on
    // every retransformation and filters them out of Class.getInterfaces().
    byte[] given = headerFixture(true);

    String internal = HEADER.replace('.', '/');
    byte[] decorated = rawTransformer(false).transform(loader, internal, loaded, null, given);
    assertThat(errors).isEmpty();
    assertThat(decorated).isNotNull();
    assertThat(new ClassReader(decorated).getInterfaces())
        .containsExactly("java/util/concurrent/Callable", "java/util/RandomAccess");
    assertThat(new ClassReader(decorated).getSuperName()).isEqualTo("java/lang/Object");

    byte[] frozen = rawTransformer(true).transform(loader, internal, loaded, null, given);
    assertThat(frozen).isNotNull();
    assertThat(new ClassReader(frozen).getInterfaces())
        .containsExactly("java/util/concurrent/Callable");
  }

  /**
   * {@code fixture.coexist.Header<V> implements Callable<V>}, with {@code RandomAccess} added to
   * the interfaces (not to the generic signature) when {@code foreignInterface}, the way another
   * agent adds an interface.
   */
  private static byte[] headerFixture(boolean foreignInterface) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    writer.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
        HEADER.replace('.', '/'),
        "<V:Ljava/lang/Object;>Ljava/lang/Object;Ljava/util/concurrent/Callable<TV;>;",
        "java/lang/Object",
        foreignInterface
            ? new String[] {"java/util/concurrent/Callable", "java/util/RandomAccess"}
            : new String[] {"java/util/concurrent/Callable"});
    MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    init.visitCode();
    init.visitVarInsn(Opcodes.ALOAD, 0);
    init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    init.visitInsn(Opcodes.RETURN);
    init.visitMaxs(0, 0);
    init.visitEnd();
    MethodVisitor call =
        writer.visitMethod(Opcodes.ACC_PUBLIC, "call", "()Ljava/lang/Object;", "()TV;", null);
    call.visitCode();
    call.visitInsn(Opcodes.ACONST_NULL);
    call.visitInsn(Opcodes.ARETURN);
    call.visitMaxs(0, 0);
    call.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  /**
   * The raw transformer of the agent builder; {@code before} uses the builder of before C-256 (1)
   * ({@code PoolStrategy.Default.FAST}, {@code REDEFINE_FROZEN}) and matches by name only (the safe
   * matcher would just not match there).
   */
  private ClassFileTransformer rawTransformer(boolean before) {
    ClassLoader testLoader = ForeignAgentTypesTest.class.getClassLoader();
    InstrumentationModule module =
        new InstrumentationModule() {
          @Override
          public String id() {
            return "bootstrap-fallback-test";
          }

          @Override
          public int moduleId() {
            return 62;
          }

          @Override
          public List<TypeInstrumentation> types() {
            return Collections.emptyList();
          }
        };
    final ElementMatcher.Junction<TypeDescription> matcher =
        nameStartsWith(PACKAGE)
            .and(SafeMatchers.safeImplements(namedOneOf(HIDDEN, Callable.class.getName())));
    TypeInstrumentation instrumentation =
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return matcher;
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(namedOneOf("run", "call"), RunAdvice.class.getName());
            transformer.injectContextField();
          }
        };
    AgentBuilder builder =
        ArgosAgentBuilder.create(
                new ByteBuddy().with(TypeValidation.DISABLED),
                new AgentBuilder.RawMatcher.ForElementMatchers(
                    ElementMatchers.<TypeDescription>none()))
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(
                new AgentBuilder.Listener.Adapter() {
                  @Override
                  public void onError(
                      String typeName,
                      ClassLoader classLoader,
                      JavaModule javaModule,
                      boolean loaded,
                      Throwable throwable) {
                    errors.add(throwable);
                  }
                });
    if (before) {
      return builder
          .with(AgentBuilder.PoolStrategy.Default.FAST)
          .with(AgentBuilder.TypeStrategy.Default.REDEFINE_FROZEN)
          .type(nameStartsWith(PACKAGE))
          .transform(transformer(module, instrumentation, testLoader))
          .makeRaw();
    }
    return builder
        .type(instrumentation.typeMatcher())
        .transform(transformer(module, instrumentation, testLoader))
        .makeRaw();
  }

  private static ModuleTransformer transformer(
      InstrumentationModule module, TypeInstrumentation instrumentation, ClassLoader testLoader) {
    return new ModuleTransformer(
        module,
        instrumentation,
        new AdviceFactory(testLoader),
        new HelperInjector(null, testLoader, new LoaderState(), TestFaults.NONE),
        false);
  }

  /**
   * An application loader whose resources lack the class file of {@link RandomAccess}, as the
   * bootstrap loader lacks one for a type another agent defined at run time.
   */
  static final class HidingLoader extends ClassLoader {
    HidingLoader() {
      super(ForeignAgentTypesTest.class.getClassLoader());
    }

    @Override
    public URL getResource(String name) {
      return HIDDEN_RESOURCE.equals(name) ? null : super.getResource(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
      return HIDDEN_RESOURCE.equals(name) ? null : super.getResourceAsStream(name);
    }

    Class<?> define(String name, byte[] bytes) {
      return defineClass(name, bytes, 0, bytes.length);
    }
  }
}
