/* SPDX-License-Identifier: Apache-2.0 */
package example.trivial;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Trivial Java 8 application for the agent attach tests (WO-02, T-BLD-006, T-AG-060): handles a few
 * "requests", runs a task on a {@link ThreadPoolExecutor} and checks what it can see of the agent.
 * Results go to the properties file named by the first argument; stdout gets exactly one line
 * ({@value #DONE}), so any other output on stdout or stderr comes from the agent or the JVM.
 *
 * <p>Outside {@code io.argos.*} on purpose: the agent never instruments its own packages.
 */
public final class TrivialApp {
  public static final String DONE = "trivial-app done";

  private TrivialApp() {}

  public static void main(String[] args) throws Exception {
    Properties results = new Properties();
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
    Handler handler = new Handler(pool);
    int handled = 0;
    for (int i = 0; i < 3; i++) {
      if (handler.handle("orders-" + i).startsWith("ok")) {
        handled++;
      }
    }
    pool.shutdown();
    pool.awaitTermination(10, TimeUnit.SECONDS);
    results.setProperty("handled", String.valueOf(handled));
    results.setProperty("asyncRuns", String.valueOf(handler.asyncRuns()));

    // Class loader isolation (10 §4.2): bootstrap API visible, agent internals invisible.
    results.setProperty("bootstrapLoader", loaderOf("io.argos.agent.bootstrap.Argos"));
    results.setProperty("coreLoader", loaderOf("io.argos.agent.core.boot.AgentStarter"));
    results.setProperty("apiLoader", loaderOf("io.argos.agent.instrumentation.api.ModuleIds"));
    results.setProperty(
        "shadedLoader", loaderOf("io.argos.agent.shaded.net.bytebuddy.agent.builder.AgentBuilder"));
    results.setProperty("byteBuddyLoader", loaderOf("net.bytebuddy.agent.builder.AgentBuilder"));
    results.setProperty(
        "agentClassLoaderLoader", loaderOf("io.argos.agent.bootstrap.AgentClassLoader"));
    ClassLoader app = TrivialApp.class.getClassLoader();
    results.setProperty(
        "coreClassResource",
        String.valueOf(app.getResource("io/argos/agent/core/boot/AgentStarter.class") != null));
    results.setProperty(
        "moduleServiceFile",
        String.valueOf(
            app.getResource(
                    "META-INF/services/io.argos.agent.instrumentation.api.InstrumentationModule")
                != null));
    results.setProperty("agentThreads", agentThreads());

    try (OutputStream out = new FileOutputStream(args[0])) {
      results.store(out, "TrivialApp results");
    }
    System.out.println(DONE);
  }

  /** "bootstrap", "app", another loader's class name, or "invisible" (ClassNotFoundException). */
  @SuppressWarnings("ReferenceEquality") // class loader identity
  static String loaderOf(String className) {
    try {
      ClassLoader loader =
          Class.forName(className, false, TrivialApp.class.getClassLoader()).getClassLoader();
      if (loader == null) {
        return "bootstrap";
      }
      return loader == ClassLoader.getSystemClassLoader() ? "app" : loader.getClass().getName();
    } catch (ClassNotFoundException e) {
      return "invisible";
    } catch (LinkageError e) {
      return "error:" + e;
    }
  }

  private static String agentThreads() {
    StringBuilder names = new StringBuilder();
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.getName().startsWith("argos-")) {
        names.append(names.length() == 0 ? "" : ",").append(thread.getName());
      }
    }
    return names.toString();
  }

  /** A "request handler": one transaction per call when the probe module is installed. */
  public static final class Handler {
    private final ThreadPoolExecutor pool;
    private volatile int asyncRuns;

    Handler(ThreadPoolExecutor pool) {
      this.pool = pool;
    }

    public String handle(String name) throws Exception {
      int rows = Store.query("select * from orders where id = ?");
      final Object done = new Object();
      final boolean[] ran = new boolean[1];
      pool.execute(
          new Runnable() {
            @Override
            public void run() {
              synchronized (done) {
                ran[0] = true;
                asyncRuns++;
                done.notifyAll();
              }
            }
          });
      synchronized (done) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!ran[0] && System.currentTimeMillis() < deadline) {
          done.wait(100);
        }
      }
      return "ok " + name + " " + rows;
    }

    int asyncRuns() {
      return asyncRuns;
    }
  }

  /** A "database": the probe module records each query as an SQL step. */
  public static final class Store {
    private Store() {}

    public static int query(String sql) throws IOException {
      return sql.length();
    }
  }
}
