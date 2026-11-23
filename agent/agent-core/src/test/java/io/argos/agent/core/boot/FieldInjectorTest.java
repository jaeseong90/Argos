/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.ContextCarrier;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.io.Serializable;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;
import net.bytebuddy.utility.OpenedClassReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The context field of DSN-02-05 through the real agent builder pipeline ({@link
 * ArgosAgentBuilder}, {@code disableClassFormatChanges()} = {@code REDEFINE_FROZEN}, {@link
 * ModuleTransformer} with advice): the raw class file transformer is called the way the JVM calls
 * it, the result is defined and verified by a class loader of its own, and the task stores of
 * {@link ContextStore} are checked against the defined classes.
 *
 * <p>Fixture classes are generated (package {@code fixture.app}): the injector leaves {@code
 * io.argos.} classes alone, so the test's own nested classes could not serve.
 */
@Tag("req:F-A07")
@Tag("req:DSN-02-05")
class FieldInjectorTest {
  private static final String PACKAGE = "fixture.app.";

  /** Counts the runs of the advice inlined next to the field. */
  public static final class Runs {
    public static final AtomicInteger COUNT = new AtomicInteger();

    private Runs() {}
  }

  /** Advice applied to {@code run()} together with the field. */
  static final class RunAdvice {
    private RunAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter() {
      Runs.COUNT.incrementAndGet();
    }
  }

  private final Map<String, byte[]> originals = new HashMap<String, byte[]>();
  private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
  private ClassFileTransformer transformer;

  @BeforeEach
  void setUp() {
    put(task("Task", ClassFileVersion.JAVA_V8).make());
    put(task("Old", ClassFileVersion.JAVA_V5).make());
    put(
        new ByteBuddy(ClassFileVersion.JAVA_V8)
            .subclass(Object.class)
            .name(PACKAGE + "Generic")
            .implement(
                TypeDescription.Generic.Builder.parameterizedType(Callable.class, String.class)
                    .build())
            .defineMethod("call", Object.class, Visibility.PUBLIC)
            .intercept(FixedValue.nullValue())
            .make());
    put(task("Serial", ClassFileVersion.JAVA_V8).implement(Serializable.class).make());
    put(
        new ByteBuddy(ClassFileVersion.JAVA_V8)
            .makeInterface(Runnable.class)
            .name(PACKAGE + "TaskInterface")
            .make());
    transformer = rawTransformer();
    Runs.COUNT.set(0);
  }

  @AfterEach
  void noTransformErrors() {
    // onError 0 (T-AG-005): every transformation above succeeded or was deliberately skipped.
    assertThat(errors).isEmpty();
  }

  private static net.bytebuddy.dynamic.DynamicType.Builder<Object> task(
      String name, ClassFileVersion version) {
    return new ByteBuddy(version)
        .subclass(Object.class)
        .name(PACKAGE + name)
        .implement(Runnable.class, Callable.class)
        .defineMethod("run", void.class, Visibility.PUBLIC)
        .intercept(StubMethod.INSTANCE)
        .defineMethod("call", Object.class, Visibility.PUBLIC)
        .intercept(FixedValue.nullValue());
  }

  private void put(net.bytebuddy.dynamic.DynamicType.Unloaded<?> type) {
    originals.put(type.getTypeDescription().getName(), type.getBytes());
  }

  private ClassFileTransformer rawTransformer() {
    ClassLoader testLoader = FieldInjectorTest.class.getClassLoader();
    InstrumentationModule module =
        new InstrumentationModule() {
          @Override
          public String id() {
            return "field-injector-test";
          }

          @Override
          public int moduleId() {
            return 63;
          }

          @Override
          public List<TypeInstrumentation> types() {
            return Collections.emptyList();
          }
        };
    TypeInstrumentation instrumentation =
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return nameStartsWith(PACKAGE);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(named("run"), RunAdvice.class.getName());
            transformer.injectContextField();
          }
        };
    return ArgosAgentBuilder.create(
            new ByteBuddy().with(TypeValidation.DISABLED),
            new AgentBuilder.RawMatcher.ForElementMatchers(ElementMatchers.<TypeDescription>none()))
        .disableClassFormatChanges()
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .with(
            new AgentBuilder.Listener.Adapter() {
              @Override
              public void onError(
                  String typeName,
                  ClassLoader classLoader,
                  JavaModule module,
                  boolean loaded,
                  Throwable throwable) {
                errors.add(throwable);
              }
            })
        .type(instrumentation.typeMatcher())
        .transform(
            new ModuleTransformer(
                module,
                instrumentation,
                new AdviceFactory(testLoader),
                new HelperInjector(null, testLoader, new LoaderState(), TestFaults.NONE),
                false))
        .makeRaw();
  }

  private static TraceContext context(long txId) {
    return new TraceContext(txId, 1, 1, 1, 0, Kind.HTTP, 0, 0, null, false, -1, -1);
  }

  @Test
  @DisplayName(
      "T-AG-052p a task class defined for the first time gets argos$ctx and ContextCarrier next to"
          + " its advice; the task stores use the field and the weak map does not grow")
  @SuppressWarnings("rawtypes") // the advice side names the raw task interfaces as key types
  void injectsOnFirstDefinition() throws Exception {
    Defining loader = new Defining(true);
    Class<?> type = loader.loadClass(PACKAGE + "Task");
    assertThat(ContextCarrier.class.isAssignableFrom(type)).isTrue();
    assertThat(loader.transformed).containsKey(PACKAGE + "Task");
    // Class file version unchanged (52 for this fixture, Java 8 target).
    assertThat(majorVersion(loader.transformed.get(PACKAGE + "Task")))
        .isEqualTo(majorVersion(originals.get(PACKAGE + "Task")))
        .isEqualTo(52);

    Field field = type.getDeclaredField(ContextCarrier.FIELD_NAME);
    assertThat(field.getType()).isEqualTo(TraceContext.class);
    int modifiers = field.getModifiers();
    assertThat(Modifier.isPrivate(modifiers)).isTrue();
    assertThat(Modifier.isVolatile(modifiers)).isTrue();
    assertThat(Modifier.isTransient(modifiers)).isTrue();
    assertThat(field.isSynthetic()).isTrue();
    Method getter = type.getDeclaredMethod(ContextCarrier.GETTER_NAME);
    Method setter = type.getDeclaredMethod(ContextCarrier.SETTER_NAME, TraceContext.class);
    assertThat(getter.isSynthetic()).isTrue();
    assertThat(setter.isSynthetic()).isTrue();

    Object task = type.getDeclaredConstructor().newInstance();
    ((Runnable) task).run();
    assertThat(Runs.COUNT.get()).isEqualTo(1);

    ContextStore<Runnable, TraceContext> runnables =
        ContextStore.of(Runnable.class, TraceContext.class);
    ContextStore<Callable, TraceContext> callables =
        ContextStore.of(Callable.class, TraceContext.class);
    int sizeBefore = runnables.size();
    TraceContext ctx = context(21);
    runnables.put((Runnable) task, ctx);
    assertThat(((ContextCarrier) task).argos$getContext()).isSameAs(ctx);
    assertThat(callables.get((Callable) task)).isSameAs(ctx);
    assertThat(runnables.size()).isEqualTo(sizeBefore);
    assertThat(callables.remove((Callable) task)).isSameAs(ctx);
    assertThat(((ContextCarrier) task).argos$getContext()).isNull();
    assertThat(runnables.get((Runnable) task)).isNull();
    assertThat(runnables.size()).isEqualTo(sizeBefore);
  }

  @Test
  @DisplayName(
      "T-AG-052p the field works in an old class file (version 49, no stack map frames) and keeps"
          + " a generic class's signature consistent with its interfaces")
  void oldVersionAndGenericSignature() throws Exception {
    Defining loader = new Defining(true);
    Class<?> old = loader.loadClass(PACKAGE + "Old");
    assertThat(majorVersion(loader.transformed.get(PACKAGE + "Old"))).isEqualTo(49);
    Object oldTask = old.getDeclaredConstructor().newInstance();
    assertThat(oldTask).isInstanceOf(ContextCarrier.class);
    TraceContext ctx = context(22);
    ((ContextCarrier) oldTask).argos$setContext(ctx);
    assertThat(((ContextCarrier) oldTask).argos$getContext()).isSameAs(ctx);

    Class<?> generic = loader.loadClass(PACKAGE + "Generic");
    assertThat(generic.getInterfaces()).containsExactly(Callable.class, ContextCarrier.class);
    assertThat(generic.getGenericInterfaces()).hasSize(2);
    assertThat(generic.getGenericInterfaces()[0].getTypeName())
        .isEqualTo("java.util.concurrent.Callable<java.lang.String>");
    assertThat(generic.getGenericInterfaces()[1]).isEqualTo(ContextCarrier.class);
    assertThat(generic.getDeclaredConstructor().newInstance()).isInstanceOf(ContextCarrier.class);
  }

  @Test
  @DisplayName(
      "T-AG-052s a serializable task and an interface keep their class format; their keys use"
          + " the weak map, and remove empties it")
  void notEligibleUsesMap() throws Exception {
    Defining loader = new Defining(true);
    Class<?> serial = loader.loadClass(PACKAGE + "Serial");
    assertThat(ContextCarrier.class.isAssignableFrom(serial)).isFalse();
    assertThat(declaredFieldNames(serial)).doesNotContain(ContextCarrier.FIELD_NAME);
    Class<?> iface = loader.loadClass(PACKAGE + "TaskInterface");
    assertThat(ContextCarrier.class.isAssignableFrom(iface)).isFalse();

    Runnable task = (Runnable) serial.getDeclaredConstructor().newInstance();
    task.run(); // the advice is still applied
    assertThat(Runs.COUNT.get()).isEqualTo(1);
    ContextStore<Runnable, TraceContext> runnables =
        ContextStore.of(Runnable.class, TraceContext.class);
    int sizeBefore = runnables.size();
    TraceContext ctx = context(23);
    runnables.put(task, ctx);
    assertThat(runnables.size()).isEqualTo(sizeBefore + 1);
    assertThat(runnables.remove(task)).isSameAs(ctx);
    assertThat(runnables.size()).isEqualTo(sizeBefore);
  }

  @Test
  @DisplayName(
      "T-AG-052s eligibility: no field for bootstrap (JDK) classes, for classes being"
          + " retransformed, for io.argos. classes")
  void eligibility() {
    ClassLoader app = FieldInjectorTest.class.getClassLoader();
    assertThat(
            FieldInjector.eligible(TypeDescription.ForLoadedType.of(FutureTask.class), null, null))
        .isFalse();
    // A loaded class without the field: retransformation (HYBRID describes it by its Class).
    assertThat(
            FieldInjector.eligible(
                TypeDescription.ForLoadedType.of(FieldInjectorTest.class), app, null))
        .isFalse();
    assertThat(FieldInjector.eligible(TypeDescription.ForLoadedType.of(Runs.class), app, null))
        .isFalse();
  }

  @Test
  @DisplayName(
      "T-AG-052s retransformation: a class loaded without the field stays without it; a class"
          + " injected at definition gets exactly the same members again (no schema change)")
  void retransformation() throws Exception {
    String name = PACKAGE + "Task";
    String internal = name.replace('.', '/');

    Defining plainLoader = new Defining(false);
    Class<?> plain = plainLoader.loadClass(name);
    assertThat(ContextCarrier.class.isAssignableFrom(plain)).isFalse();
    byte[] plainAgain =
        transformer.transform(plainLoader, internal, plain, null, originals.get(name));
    Members plainMembers = Members.of(plainAgain == null ? originals.get(name) : plainAgain);
    assertThat(plainMembers.fields).isZero();
    assertThat(plainMembers.carrierInterfaces).isZero();

    Defining injectedLoader = new Defining(true);
    Class<?> injected = injectedLoader.loadClass(name);
    Members first = Members.of(injectedLoader.transformed.get(name));
    byte[] again =
        transformer.transform(injectedLoader, internal, injected, null, originals.get(name));
    assertThat(again).isNotNull();
    Members retransformed = Members.of(again);
    assertThat(first.fields).isEqualTo(1);
    assertThat(first.getters).isEqualTo(1);
    assertThat(first.setters).isEqualTo(1);
    assertThat(first.carrierInterfaces).isEqualTo(1);
    assertThat(retransformed.fields).isEqualTo(1);
    assertThat(retransformed.getters).isEqualTo(1);
    assertThat(retransformed.setters).isEqualTo(1);
    assertThat(retransformed.carrierInterfaces).isEqualTo(1);
    assertThat(retransformed.schema).isEqualTo(first.schema);
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "T-AG-052r an injected task holding a context does not keep its (web application) class"
          + " loader alive, and the weak map stays unchanged")
  void injectedTaskDoesNotPinLoader() throws Exception {
    ContextStore<Runnable, TraceContext> runnables =
        ContextStore.of(Runnable.class, TraceContext.class);
    int sizeBefore = runnables.size();
    WeakReference<ClassLoader> loader = storeInjectedTask(runnables);
    assertThat(runnables.size()).isEqualTo(sizeBefore);
    long deadline = System.currentTimeMillis() + 10_000;
    while (loader.get() != null && System.currentTimeMillis() < deadline) {
      System.gc();
      Thread.sleep(20);
    }
    assertThat(loader.get()).isNull();
  }

  private WeakReference<ClassLoader> storeInjectedTask(ContextStore<Runnable, TraceContext> store)
      throws Exception {
    Defining loader = new Defining(true);
    Runnable task =
        (Runnable) loader.loadClass(PACKAGE + "Task").getDeclaredConstructor().newInstance();
    store.put(task, context(24));
    assertThat(((ContextCarrier) task).argos$getContext()).isNotNull();
    return new WeakReference<ClassLoader>(loader);
  }

  private static int majorVersion(byte[] classFile) {
    return ((classFile[6] & 0xff) << 8) | (classFile[7] & 0xff);
  }

  private static List<String> declaredFieldNames(Class<?> type) {
    List<String> names = new ArrayList<String>();
    for (Field field : type.getDeclaredFields()) {
      names.add(field.getName());
    }
    return names;
  }

  /** Context members and the full member list of a class file. */
  static final class Members {
    int fields;
    int getters;
    int setters;
    int carrierInterfaces;
    final List<String> schema = new ArrayList<String>();

    static Members of(byte[] classFile) {
      final Members members = new Members();
      final String carrier = ContextCarrier.class.getName().replace('.', '/');
      new ClassReader(classFile)
          .accept(
              new ClassVisitor(OpenedClassReader.ASM_API) {
                @Override
                public void visit(
                    int version,
                    int access,
                    String name,
                    String signature,
                    String superName,
                    String[] interfaces) {
                  for (String candidate : interfaces) {
                    members.schema.add("implements " + candidate);
                    if (candidate.equals(carrier)) {
                      members.carrierInterfaces++;
                    }
                  }
                }

                @Override
                public FieldVisitor visitField(
                    int access, String name, String descriptor, String signature, Object value) {
                  members.schema.add("field " + access + " " + name + descriptor);
                  if (name.equals(ContextCarrier.FIELD_NAME)) {
                    members.fields++;
                  }
                  return null;
                }

                @Override
                public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                  members.schema.add("method " + access + " " + name + descriptor);
                  if (name.equals(ContextCarrier.GETTER_NAME)) {
                    members.getters++;
                  } else if (name.equals(ContextCarrier.SETTER_NAME)) {
                    members.setters++;
                  }
                  return null;
                }
              },
              ClassReader.SKIP_CODE);
      Collections.sort(members.schema);
      return members;
    }
  }

  /**
   * Defines the fixture classes itself (child first), passing their class files through the agent's
   * raw transformer first when {@code transform} is set; delegates everything else.
   */
  final class Defining extends ClassLoader {
    final Map<String, byte[]> transformed = new HashMap<String, byte[]>();
    private final boolean transform;

    Defining(boolean transform) {
      super(FieldInjectorTest.class.getClassLoader());
      this.transform = transform;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      byte[] original = originals.get(name);
      if (original == null) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
          return loaded;
        }
        byte[] bytes = original;
        if (transform) {
          try {
            byte[] result =
                transformer.transform(this, name.replace('.', '/'), null, null, original);
            if (result != null) {
              transformed.put(name, result);
              bytes = result;
            }
          } catch (IllegalClassFormatException e) {
            throw new ClassNotFoundException(name, e);
          }
        }
        return defineClass(name, bytes, 0, bytes.length);
      }
    }
  }
}
