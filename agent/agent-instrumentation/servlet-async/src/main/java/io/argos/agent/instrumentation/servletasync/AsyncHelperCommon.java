/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servletasync;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.TraceContext;
import java.lang.ref.WeakReference;

/**
 * Namespace-independent part of the servlet-async hooks (10 §6.7), used by {@link AsyncHelperJavax}
 * and {@link AsyncHelperJakarta}. Injected into the container's class loader: bootstrap API and JDK
 * only, no state.
 *
 * <p>The response store is shared with the servlet module's flush instrumentation by its key and
 * value types ({@code ContextStore<Object, TraceContext>}, no helper reference across modules): the
 * container's response facade maps to the transaction of an async request until it ends; the
 * streams the facade hands out reach it through the servlet module's stream links ({@code
 * ContextStore<Object, WeakReference>}, shared the same way), also when they were opened before
 * {@code startAsync}: the servlet module records such a stream on the transaction only, and this
 * hook links it.
 */
public final class AsyncHelperCommon {
  /** The request attribute that anchors the transaction (servlet module, DSN-10-02). */
  public static final String TX_ATTRIBUTE = "argos.tx";

  private AsyncHelperCommon() {}

  /** The running transaction of {@code anchor} (a request attribute value), or null. */
  public static TraceContext live(Object anchor) {
    if (!(anchor instanceof TraceContext)) {
      return null;
    }
    TraceContext context = (TraceContext) anchor;
    return context.isEnded() ? null : context;
  }

  /**
   * {@code startAsync} returned: the transaction is in async mode (a flush now ends a streaming
   * response), keeps the status so far, and {@code innermostResponse} maps to it; the stream the
   * request thread opened before ({@link #linkRecordedStream}) is linked to that response now.
   */
  public static void started(TraceContext context, int status, Object innermostResponse) {
    context.asyncStarted = true;
    if (status > 0) {
      context.status = status;
    }
    if (innermostResponse != null) {
      ContextStore.of(Object.class, TraceContext.class).put(innermostResponse, context);
      linkRecordedStream(context, innermostResponse);
    }
  }

  /**
   * Links the output stream or writer that the servlet module recorded on the transaction while it
   * ran synchronously ({@code TraceContext.responseStream}: Spring's {@code StreamingResponseBody}
   * opens it before {@code startAsync}) to {@code facade}, in the servlet module's stream store
   * ({@code ContextStore<Object, WeakReference>}, weak key and value, shared by its key and value
   * types), so that the stream's first flush in async mode ends a streaming response. An existing
   * link to the same facade is kept; nothing when no stream was recorded or it was collected.
   */
  @SuppressWarnings({"rawtypes", "unchecked", "ReferenceEquality"})
  static void linkRecordedStream(TraceContext context, Object facade) {
    WeakReference<Object> recorded = context.responseStream;
    Object stream = recorded == null ? null : recorded.get();
    if (stream == null) {
      return;
    }
    ContextStore<Object, WeakReference<Object>> links =
        (ContextStore) ContextStore.of(Object.class, WeakReference.class);
    WeakReference<Object> link = links.get(stream);
    if (link == null || link.get() != facade) {
      links.put(stream, new WeakReference<Object>(facade));
    }
  }

  /**
   * {@code AsyncContext.start(task)}: the task carries the current transaction ({@code
   * ContextStore<Runnable, TraceContext>}, the store of the executor module's run advice, 10 §6.6);
   * the task is not wrapped. Nothing happens without a transaction on this thread.
   *
   * <p>Stored only: until the executor module's run-side advice reads this store (WO-15), the entry
   * has no effect. Tomcat runs the task inside {@code AsyncContextImpl$RunnableWrapper} on its own
   * {@code org.apache.tomcat.util.threads.ThreadPoolExecutor}, so the run side must look up the
   * user's task (the key stored here), not the wrapper.
   */
  public static void propagate(Runnable task) {
    if (task == null) {
      return;
    }
    TraceContext context = Argos.tracer().current();
    if (context != null && !context.isEnded()) {
      ContextStore.of(Runnable.class, TraceContext.class).put(task, context);
    }
  }
}
