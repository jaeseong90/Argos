/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The task stores of 10 §6.6 (DSN-02-05): field path for keys that implement {@link
 * ContextCarrier}, weak-map fallback for every other key. {@link Carrier} stands in for a class the
 * core injected (the same members {@code FieldInjector} generates); agent-core's {@code
 * FieldInjectorTest} checks the generated classes against these stores.
 */
@Tag("req:F-A07")
@Tag("req:DSN-02-05")
class FieldBackedContextStoreTest {

  /** What an injected task class looks like after the transformation. */
  static final class Carrier implements Runnable, Callable<Object>, ContextCarrier {
    private volatile TraceContext ctx;

    @Override
    public void run() {}

    @Override
    public Object call() {
      return null;
    }

    @Override
    public TraceContext argos$getContext() {
      return ctx;
    }

    @Override
    public void argos$setContext(TraceContext value) {
      ctx = value;
    }
  }

  /** A task class defined again by a throw-away loader (a web application's class). */
  public static final class Foreign implements Runnable {
    @Override
    public void run() {}
  }

  private static TraceContext context(long txId) {
    return new TraceContext(txId, 1, 1, 1, 0, Kind.HTTP, 0, 0, null, false, -1, -1);
  }

  @Test
  @DisplayName(
      "T-AG-052p task stores (Runnable, Callable, ForkJoinTask -> TraceContext) are field backed;"
          + " other pairs are plain weak maps")
  void fieldBackedPairs() {
    assertThat(ContextStore.of(Runnable.class, TraceContext.class))
        .isInstanceOf(FieldBackedContextStore.class);
    assertThat(ContextStore.of(Callable.class, TraceContext.class))
        .isInstanceOf(FieldBackedContextStore.class);
    assertThat(ContextStore.of(ForkJoinTask.class, TraceContext.class))
        .isInstanceOf(FieldBackedContextStore.class);
    assertThat(ContextStore.of(Runnable.class, Scope.class)).isInstanceOf(WeakContextStore.class);
    assertThat(ContextStore.of(Object.class, TraceContext.class))
        .isInstanceOf(WeakContextStore.class);
  }

  @Test
  @DisplayName(
      "T-AG-052p a carrier key keeps its context in the field: the map does not grow, one field"
          + " serves the Runnable and Callable stores, remove and put(null) clear it")
  @SuppressWarnings("rawtypes") // the advice side names the raw task interfaces as key types
  void fieldPath() {
    ContextStore<Runnable, TraceContext> runnables =
        ContextStore.of(Runnable.class, TraceContext.class);
    ContextStore<Callable, TraceContext> callables =
        ContextStore.of(Callable.class, TraceContext.class);
    int sizeBefore = runnables.size();
    long contextEntriesBefore = ContextStore.contextEntries();
    Carrier task = new Carrier();
    TraceContext ctx = context(7);

    runnables.put(task, ctx);
    assertThat(task.argos$getContext()).isSameAs(ctx);
    assertThat(runnables.get(task)).isSameAs(ctx);
    assertThat(callables.get(task)).isSameAs(ctx);
    assertThat(runnables.size()).isEqualTo(sizeBefore);
    assertThat(ContextStore.contextEntries()).isEqualTo(contextEntriesBefore);

    assertThat(callables.remove(task)).isSameAs(ctx);
    assertThat(task.argos$getContext()).isNull();
    assertThat(runnables.get(task)).isNull();
    assertThat(runnables.remove(task)).isNull();

    runnables.put(task, ctx);
    runnables.put(task, null);
    assertThat(task.argos$getContext()).isNull();

    final TraceContext created = context(8);
    ContextStore.Factory<TraceContext> factory =
        new ContextStore.Factory<TraceContext>() {
          @Override
          public TraceContext create() {
            return created;
          }
        };
    assertThat(runnables.computeIfAbsent(task, factory)).isSameAs(created);
    assertThat(runnables.computeIfAbsent(task, factory)).isSameAs(created);
    assertThat(runnables.remove(task)).isSameAs(created);
    assertThat(runnables.size()).isEqualTo(sizeBefore);
  }

  @Test
  @DisplayName(
      "T-AG-052s a key without the field (JDK FutureTask, a lambda) uses the weak map; remove"
          + " empties it")
  void mapPath() {
    ContextStore<Runnable, TraceContext> runnables =
        ContextStore.of(Runnable.class, TraceContext.class);
    int sizeBefore = runnables.size();
    FutureTask<Object> jdkTask = new FutureTask<Object>(new Foreign(), null);
    Runnable lambda = () -> {};
    TraceContext ctx = context(9);
    runnables.put(jdkTask, ctx);
    runnables.put(lambda, ctx);
    assertThat(runnables.get(jdkTask)).isSameAs(ctx);
    assertThat(runnables.get(lambda)).isSameAs(ctx);
    assertThat(runnables.size()).isEqualTo(sizeBefore + 2);
    assertThat(runnables.remove(jdkTask)).isSameAs(ctx);
    assertThat(runnables.remove(lambda)).isSameAs(ctx);
    assertThat(runnables.get(jdkTask)).isNull();
    assertThat(runnables.size()).isEqualTo(sizeBefore);
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "T-AG-052q 10,000 JDK tasks in the fallback map that are never run: after GC the map is"
          + " back to its size before (10 §6.6, 03 §3.1 leak)")
  void fallbackMapEmptiesAfterGc() throws InterruptedException {
    ContextStore<Runnable, TraceContext> runnables =
        ContextStore.of(Runnable.class, TraceContext.class);
    int sizeBefore = runnables.size();
    TraceContext ctx = context(10);
    List<FutureTask<Object>> tasks = new ArrayList<FutureTask<Object>>();
    for (int i = 0; i < 10_000; i++) {
      FutureTask<Object> task = new FutureTask<Object>(new Foreign(), null);
      tasks.add(task);
      runnables.put(task, ctx);
    }
    // The list keeps the tasks reachable until here, so all 10,000 entries are present.
    assertThat(runnables.size()).isEqualTo(sizeBefore + tasks.size());
    assertThat(tasks).hasSize(10_000);
    tasks.clear();
    awaitSize(runnables, sizeBefore);
    assertThat(runnables.size()).isEqualTo(sizeBefore);
    assertThat(runnables.droppedEntries()).isZero();
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "T-AG-052r a task of a web application's loader in the fallback map does not keep that"
          + " loader alive")
  void foreignLoaderNotPinned() throws Exception {
    ContextStore<Runnable, TraceContext> runnables =
        ContextStore.of(Runnable.class, TraceContext.class);
    int sizeBefore = runnables.size();
    WeakReference<ClassLoader> loader = storeForeignTask(runnables);
    assertThat(runnables.size()).isEqualTo(sizeBefore + 1);
    long deadline = System.currentTimeMillis() + 10_000;
    while (loader.get() != null && System.currentTimeMillis() < deadline) {
      System.gc();
      Thread.sleep(20);
      ContextStore.expungeAll();
    }
    assertThat(loader.get()).isNull();
    awaitSize(runnables, sizeBefore);
    assertThat(runnables.size()).isEqualTo(sizeBefore);
  }

  /** Defines {@link Foreign} in a new loader, stores one instance and forgets both. */
  private static WeakReference<ClassLoader> storeForeignTask(
      ContextStore<Runnable, TraceContext> store) throws Exception {
    ChildFirst child = new ChildFirst(Foreign.class.getName());
    Class<?> type = child.loadClass(Foreign.class.getName());
    assertThat(type.getClassLoader()).isSameAs(child);
    Runnable task = (Runnable) type.getDeclaredConstructor().newInstance();
    store.put(task, context(11));
    assertThat(store.get(task)).isNotNull();
    return new WeakReference<ClassLoader>(child);
  }

  private static void awaitSize(ContextStore<?, ?> store, int size) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (store.size() > size && System.currentTimeMillis() < deadline) {
      System.gc();
      Thread.sleep(20);
      ContextStore.expungeAll();
    }
  }

  /** Defines one class itself from the parent's class file, delegates everything else. */
  static final class ChildFirst extends ClassLoader {
    private final String name;

    ChildFirst(String name) {
      super(FieldBackedContextStoreTest.class.getClassLoader());
      this.name = name;
    }

    @Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
      if (!className.equals(name)) {
        return super.loadClass(className, resolve);
      }
      synchronized (getClassLoadingLock(className)) {
        Class<?> loaded = findLoadedClass(className);
        if (loaded == null) {
          byte[] bytes = classFile(className);
          loaded = defineClass(className, bytes, 0, bytes.length);
        }
        return loaded;
      }
    }

    private byte[] classFile(String className) throws ClassNotFoundException {
      String resource = className.replace('.', '/') + ".class";
      try (InputStream in = getParent().getResourceAsStream(resource)) {
        if (in == null) {
          throw new ClassNotFoundException(className);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
          out.write(buffer, 0, read);
        }
        return out.toByteArray();
      } catch (IOException e) {
        throw new ClassNotFoundException(className, e);
      }
    }
  }
}
