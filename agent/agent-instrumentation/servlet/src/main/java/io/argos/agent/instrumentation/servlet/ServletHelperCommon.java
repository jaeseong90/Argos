/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.util.UUID;

/**
 * Namespace-independent part of the request boundary state machine (10 §7.1), shared by {@link
 * ServletHelperJavax} and {@link ServletHelperJakarta}, and the response flush end of streaming
 * responses (10 §6.7, F-A34, ADR-0010 C-127). Injected into the class loader of the transformed
 * types (10 §5.4): bootstrap API and JDK only, no state.
 *
 * <ul>
 *   <li>NONE -> ACTIVE: REQUEST dispatch, not excluded: {@link #begin}, depth 1, dispatch count 1,
 *       anchored on the request attribute {@value #TX_ATTRIBUTE} (DSN-10-02).
 *   <li>NONE -> IGNORED: excluded by the rules of 10 §7.2; the request attribute {@value
 *       #IGNORED_ATTRIBUTE} keeps every later dispatch of the request ignored.
 *   <li>NONE, not a REQUEST dispatch: not recorded; counted once per request in {@code
 *       transactions_unanchored_total} ({@value #UNANCHORED_ATTRIBUTE}), for example the ERROR
 *       dispatch the container starts after the transaction ended (C-48, WO-02 D-8).
 *   <li>ACTIVE: every nested filter/servlet call and every later dispatch of the same request
 *       ({@link #join}) adds one level; {@link #leave} removes it. A dispatch that reaches a
 *       servlet other than the REQUEST one adds one to the dispatch count (F-A28).
 *   <li>Depth 0 with {@code isAsyncStarted()}: ASYNC_PENDING ({@link #suspend}); the request thread
 *       drops the context; an {@code AsyncListener}, the ASYNC (or ERROR) dispatch that completes
 *       the request, the first response flush of a streaming response or {@code
 *       transaction.max-duration-ms} ends it, whichever comes first (single end).
 *   <li>Depth 0 otherwise: ENDING ({@link #end}): final status (D-21: an exception that escaped
 *       with a status below 400 is a 500; after {@code onTimeout} too), 101 or {@code Upgrade}
 *       marks a streaming response.
 * </ul>
 */
public final class ServletHelperCommon {
  /** Request attribute that anchors the transaction (F-A28: the request is the truth). */
  public static final String TX_ATTRIBUTE = "argos.tx";

  /** Request attribute of an excluded request (10 §7.1 IGNORED): later dispatches stay ignored. */
  public static final String IGNORED_ATTRIBUTE = "argos.ignored";

  /** Request attribute set once its first unanchored dispatch was counted (10 §7.1). */
  public static final String UNANCHORED_ATTRIBUTE = "argos.unanchored";

  /** {@code ModuleIds.SERVLET_ASYNC}: the async listener belongs to that module's switch. */
  static final int SERVLET_ASYNC_MODULE_ID = 7;

  /** Characters of the {@code X-Forwarded-For} values handed to the tracer at most (the right). */
  static final int MAX_FORWARDED_CHARS = 2048;

  /**
   * {@code X-Forwarded-For} header lines kept at most: the last ones, which the trusted proxies
   * appended (SEC-37); the same bound as the hops the core reads ({@code RemoteIps}).
   */
  static final int MAX_FORWARDED_LINES = 32;

  /** {@code HttpServletMapping} matches that name a service (Servlet 4+, 10 §6.1 exit). */
  private static final String[] NAMING_MATCHES = {"EXACT", "PATH", "EXTENSION"};

  private ServletHelperCommon() {}

  /**
   * Joins the transaction anchored on the request; null when there is none (or it ended). The
   * dispatches of one request run one after another (the container hands an ASYNC dispatch over
   * only after the previous thread left the request), so the depth needs no atomic update.
   *
   * @param newServletDispatch the call is the servlet of a FORWARD, INCLUDE, ERROR or ASYNC
   *     dispatch: one more dispatch for {@code dispatch_count} ({@link #countDispatch})
   * @param request the request object of the call (its identity only)
   */
  @SuppressWarnings("NonAtomicVolatileUpdate")
  public static TraceContext join(Object anchor, boolean newServletDispatch, Object request) {
    if (!(anchor instanceof TraceContext)) {
      return null;
    }
    TraceContext context = (TraceContext) anchor;
    if (context.isEnded()) {
      return null;
    }
    if (context.dispatchDepth <= 0) {
      // A dispatch after ASYNC_PENDING (dispatch(), the container's ERROR dispatch after a
      // timeout): the async cycle that startAsync opened is over.
      context.asyncStarted = false;
      context.countedDispatchDepth = 0;
    }
    int depth = context.dispatchDepth + 1;
    context.dispatchDepth = depth;
    if (newServletDispatch) {
      countDispatch(context, depth, System.identityHashCode(request));
    }
    return context;
  }

  /**
   * One more dispatch for {@code dispatch_count} (F-A28), unless an enclosing servlet call already
   * counted the dispatch of the same request object: Jasper's {@code JspServlet} hands the request
   * of a FORWARD or INCLUDE to the generated page through {@code Servlet.service(ServletRequest,
   * ServletResponse)}, which enters {@code HttpServlet.service} a second time. A container wraps
   * the request anew for every dispatch, so a nested forward or include counts.
   */
  @SuppressWarnings("NonAtomicVolatileUpdate")
  static void countDispatch(TraceContext context, int depth, int request) {
    if (context.countedDispatchDepth > 0 && context.countedDispatchRequest == request) {
      return;
    }
    context.dispatchCount = context.dispatchCount + 1;
    context.countedDispatchRequest = request;
    context.countedDispatchDepth = depth;
  }

  /** The path within the web application: {@code requestUri} without the context path. */
  public static String pathWithinApp(String requestUri, String contextPath) {
    if (requestUri == null || requestUri.isEmpty()) {
      return "/";
    }
    if (contextPath != null
        && !contextPath.isEmpty()
        && requestUri.startsWith(contextPath)
        && requestUri.length() > contextPath.length()) {
      return requestUri.substring(contextPath.length());
    }
    if (contextPath != null && !contextPath.isEmpty() && requestUri.equals(contextPath)) {
      return "/";
    }
    return requestUri;
  }

  /**
   * The provisional service name {@code [<context path> ]<METHOD> <path>} (F-A36 prefix for a
   * non-root web application). A module that knows the handler pattern replaces it ({@code
   * setServiceName(.., true)}); otherwise the tracer normalizes it at the end (F-A29).
   */
  public static String provisionalName(String contextPath, String method, String path) {
    if (contextPath == null || contextPath.isEmpty()) {
      return method + " " + path;
    }
    return contextPath + " " + method + " " + path;
  }

  /** NONE -> ACTIVE: a new HTTP transaction at depth 1, or null when the tracer cannot record. */
  public static TraceContext begin(
      Tracer tracer, String method, String contextPath, String pathWithinApp) {
    TraceContext context =
        tracer.begin(
            Kind.HTTP, provisionalName(contextPath, method, pathWithinApp), System.nanoTime());
    if (context != null) {
      context.dispatchDepth = 1;
      context.dispatchCount = 1;
    }
    return context;
  }

  /** Leaves one dispatch level; true when it was the outermost one (depth 0). */
  public static boolean leave(TraceContext context) {
    int depth = context.dispatchDepth - 1;
    if (depth < 0) {
      depth = 0;
    }
    context.dispatchDepth = depth;
    if (depth < context.countedDispatchDepth) {
      // The servlet call that counted the last dispatch returned: the next dispatch with the same
      // request object (a later ASYNC or ERROR dispatch) counts again.
      context.countedDispatchDepth = 0;
    }
    return depth == 0;
  }

  /**
   * ASYNC_PENDING: the request continues asynchronously. The transaction stays open, keeps the
   * response status so far, is unbound from this container thread, and the outermost {@code
   * response} maps to it for the flush end of a streaming response; the stream the request opened
   * before ({@link #streamOpened}) is linked to that response now.
   */
  public static void suspend(TraceContext context, int status, Object response) {
    context.asyncStarted = true;
    if (status > 0) {
      context.status = status;
    }
    mapResponse(response, context);
    linkRecordedStream(context, response);
    Tracer tracer = Argos.tracer();
    if (tracer.current() == context) {
      // Binding null unbinds the context from this thread (the returned scope is not needed).
      tracer.activate(null);
    }
  }

  /**
   * True when the async listener may be registered: the servlet-async module is enabled ({@code
   * instrumentation.disabled: servlet-async} turns the listener off; the request then ends at the
   * exit of its last ASYNC or ERROR dispatch, or at {@code transaction.max-duration-ms}).
   */
  public static boolean asyncListenerEnabled() {
    return ModuleHealth.enabled(SERVLET_ASYNC_MODULE_ID);
  }

  /**
   * ENDING: ends the transaction with the final status (D-21): an escaped exception or an {@code
   * AsyncListener.onTimeout} with a status below 400 makes it 500.
   */
  public static void end(TraceContext context, int status, Throwable error) {
    end(context, status, error, false);
  }

  /**
   * {@link #end(TraceContext, int, Throwable)}; {@code streaming} (a 101 or {@code Upgrade}
   * response, {@link #isUpgrade}) ends through {@code Tracer.endStreaming}, so the flag is set only
   * when this end wins the single end (a concurrent {@code max-duration} end keeps its record).
   */
  public static void end(TraceContext context, int status, Throwable error, boolean streaming) {
    int finalStatus = (error != null || context.asyncTimedOut) && status < 400 ? 500 : status;
    Tracer tracer = Argos.tracer();
    if (streaming) {
      tracer.endStreaming(context, finalStatus, error, System.nanoTime());
    } else {
      tracer.end(context, finalStatus, null, null, error, System.nanoTime());
    }
  }

  /**
   * The outermost exit of a transaction that another thread ended meanwhile (the first flush of a
   * streaming response, {@code transaction.max-duration-ms}): drops this thread's binding of it,
   * which {@code begin} created on the REQUEST dispatch, so that {@code current()} does not return
   * the ended context until the next {@code begin}. {@code Tracer.end} unbinds before its
   * single-end check, so this second end changes nothing else; {@code activate(null)} would keep
   * the ended context in the binding chain.
   */
  public static void release(TraceContext context) {
    Argos.tracer().end(context, context.status, null, null, null, System.nanoTime());
  }

  /** F-A34: a 101 response or one with an {@code Upgrade} header streams from now on. */
  public static boolean isUpgrade(int status, boolean upgradeHeader) {
    return status == 101 || upgradeHeader;
  }

  /**
   * The Servlet 4+ {@code HttpServletMapping} as a service name candidate (10 §6.1, F-A02): {@code
   * [<context path> ]<METHOD> <pattern>} for an EXACT, PATH or EXTENSION match, set as a non-fixed
   * name only when no module changed the provisional name (no fixed handler pattern, no {@code
   * Controller#method} of spring-webmvc). The namespace helpers call it once, right after the
   * REQUEST dispatch began the transaction, so the name holds whichever end comes first (an exit, a
   * listener, a flush, {@code max-duration}); a module that knows the handler pattern replaces it
   * later. The tracer keeps a pattern as it is (its {@code ServiceNameResolver} normalizes path
   * tokens only).
   */
  public static void mappingCandidate(
      TraceContext context,
      String contextPath,
      String method,
      String requestUri,
      String pattern,
      String match) {
    if (context.serviceFixed || pattern == null || pattern.isEmpty() || !naming(match)) {
      return;
    }
    String provisional =
        provisionalName(contextPath, method, pathWithinApp(requestUri, contextPath));
    if (!provisional.equals(context.serviceName)) {
      return;
    }
    Argos.tracer().setServiceName(context, provisionalName(contextPath, method, pattern), false);
  }

  private static boolean naming(String match) {
    for (String naming : NAMING_MATCHES) {
      if (naming.equals(match)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The text of a session attribute used as user id: a {@code String}, or a {@code Long}, {@code
   * Integer}, {@code Short}, {@code BigInteger} or {@code UUID} (JDK types whose {@code toString}
   * is known); null for anything else (no {@code toString()} of application objects, 10 §6.3).
   */
  public static String userIdText(Object value) {
    if (value instanceof String) {
      return (String) value;
    }
    if (value instanceof Long
        || value instanceof Integer
        || value instanceof Short
        || value instanceof UUID
        || (value != null && value.getClass() == BigInteger.class)) {
      return value.toString();
    }
    return null;
  }

  /**
   * The {@code X-Forwarded-For} value for the tracer (16 §6.2, SEC-37) from the last header lines
   * of the request. {@code lines} is a ring of the last lines read: line {@code i} (0-based, in
   * header order) of the {@code count} lines read is at {@code lines[i % lines.length]}, so the
   * lines that trusted proxies append at the end are always kept, however many lines the client
   * sent before them. The kept lines are joined with commas, and the right of the result is kept up
   * to {@value #MAX_FORWARDED_CHARS} characters, cut at an entry boundary: an entry that the cut
   * would split is dropped ({@code 110.0.0.1} never becomes {@code 10.0.0.1}). Null when nothing is
   * left; the tracer then uses the socket address.
   */
  public static String forwarded(String[] lines, int count) {
    if (lines == null || lines.length == 0 || count <= 0) {
      return null;
    }
    int oldest = count - Math.min(count, lines.length);
    // From the right: only the lines the character bound can still use.
    int first = count;
    int length = -1;
    while (first > oldest && length <= MAX_FORWARDED_CHARS) {
      first--;
      String line = lines[first % lines.length];
      length += 1 + (line == null ? 0 : line.length());
    }
    StringBuilder joined = new StringBuilder(Math.max(16, length));
    for (int i = first; i < count; i++) {
      if (i > first) {
        joined.append(',');
      }
      String line = lines[i % lines.length];
      if (line != null) {
        joined.append(line);
      }
    }
    int start = joined.length() - MAX_FORWARDED_CHARS;
    if (start > 0 && joined.charAt(start - 1) != ',') {
      int comma = joined.indexOf(",", start);
      if (comma < 0) {
        return null;
      }
      start = comma + 1;
    }
    String kept = start > 0 ? joined.substring(start) : joined.toString();
    return kept.isEmpty() ? null : kept;
  }

  // --- streaming response end (10 §6.7, F-A34, C-127) -----------------------------------------

  /**
   * The container's response facades mapped to the transaction of an async request ({@code
   * startAsync}, {@link #suspend}; removed at the end). Keys are weak identity keys; values are
   * contexts (A-9).
   */
  private static ContextStore<Object, TraceContext> responses() {
    return ContextStore.of(Object.class, TraceContext.class);
  }

  /**
   * The output streams and writers of async requests, linked to the response facade by a weak
   * reference (A-9: the value holds no object strongly, no transaction either), so that a flush of
   * the stream finds the facade's transaction whether the stream was opened before or after {@code
   * startAsync}. Keys are weak identity keys. Shared with the servlet-async module's {@code
   * startAsync} hook by its key and value types. Synchronous requests never write here ({@link
   * #streamOpened}).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static ContextStore<Object, WeakReference<Object>> streams() {
    return (ContextStore) ContextStore.of(Object.class, WeakReference.class);
  }

  /**
   * Links {@code stream} to {@code facade} ({@link #streams}) unless that link exists already (a
   * stream asked for again, a facade the container reuses).
   */
  @SuppressWarnings("ReferenceEquality") // the link is to this very facade (weak identity keys)
  static void link(Object stream, Object facade) {
    ContextStore<Object, WeakReference<Object>> links = streams();
    WeakReference<Object> link = links.get(stream);
    if (link == null || link.get() != facade) {
      links.put(stream, new WeakReference<Object>(facade));
    }
  }

  /**
   * Links the stream {@code context} recorded on the request thread ({@link
   * TraceContext#responseStream}) to {@code facade}, the response now mapped to the transaction:
   * the stream was opened before the request went async. Nothing when no stream was recorded or it
   * was collected.
   */
  static void linkRecordedStream(TraceContext context, Object facade) {
    WeakReference<Object> recorded = context.responseStream;
    Object stream = recorded == null ? null : recorded.get();
    if (stream != null && facade != null) {
      link(stream, facade);
    }
  }

  /** Maps {@code response} to {@code context} (async requests only; removed at the end). */
  public static void mapResponse(Object response, TraceContext context) {
    if (response != null && context != null) {
      responses().put(response, context);
    }
  }

  /** Removes the mapping of {@code response} (cheap when nothing is mapped). */
  public static void unmapResponse(Object response) {
    if (response == null) {
      return;
    }
    ContextStore<Object, TraceContext> store = responses();
    if (store.size() > 0) {
      store.remove(response);
    }
  }

  /**
   * Exit of the response facade's {@code getOutputStream()}/{@code getWriter()}: makes the returned
   * stream find the facade's transaction at its flush, whichever comes first, the stream or {@code
   * startAsync}. Spring's {@code StreamingResponseBody} opens the stream on the request thread
   * before {@code startAsync} and flushes it from another thread; its SSE support asks for the
   * stream again at every flush.
   *
   * <ul>
   *   <li>The facade is mapped already (an async request: the stream opened or asked for again
   *       after {@code startAsync}), or the transaction running on this thread is in async mode:
   *       the stream is linked to the facade ({@link #streams}).
   *   <li>A transaction runs on this thread and is not in async mode (a synchronous request so
   *       far): the stream is only recorded on the transaction ({@link
   *       TraceContext#responseStream}, one field, no shared store). Tomcat 10.1 and 11 ({@code
   *       discardFacades=true} by default) hand out a new facade and stream for every request, so a
   *       link per request would cost a store entry per request; the link is made only when the
   *       request goes async: at {@code startAsync} (servlet-async module) or at the suspending
   *       exit ({@link #suspend}).
   *   <li>Otherwise nothing (no transaction, or a facade nobody maps).
   * </ul>
   */
  @SuppressWarnings("ReferenceEquality") // the recorded stream is this very stream
  public static void streamOpened(Object response, Object stream) {
    if (response == null || stream == null) {
      return;
    }
    ContextStore<Object, TraceContext> store = responses();
    boolean mapped = store.size() > 0 && store.get(response) != null;
    if (!mapped) {
      TraceContext context = Argos.tracer().current();
      if (context == null) {
        return;
      }
      if (!context.asyncStarted) {
        WeakReference<Object> recorded = context.responseStream;
        if (recorded == null || recorded.get() != stream) {
          context.responseStream = new WeakReference<Object>(stream);
        }
        return;
      }
    }
    link(stream, response);
  }

  /**
   * Exit of {@code flushBuffer()} / {@code flush()} of a container response class (C-127): the
   * first flush after {@code startAsync}, outside an ASYNC dispatch, ends the transaction as a
   * streaming response (SSE, {@code StreamingResponseBody}, F-A34) with the status recorded so far
   * (200 when none). A flush of a synchronous response, or inside the ASYNC dispatch that writes a
   * {@code DeferredResult}, changes nothing. {@code streaming} is set only when this flush wins the
   * single end ({@code Tracer.endStreaming}): an {@code onComplete} or {@code max-duration} end on
   * another thread at the same moment keeps its record unchanged. A stream finds its transaction
   * through the facade it came from ({@link #streamOpened}).
   */
  public static void flushed(Object target) {
    if (target == null) {
      return;
    }
    ContextStore<Object, TraceContext> store = responses();
    if (store.size() == 0) {
      return;
    }
    Object facade = target;
    TraceContext context = store.get(target);
    if (context == null) {
      WeakReference<Object> link = streams().get(target);
      facade = link == null ? null : link.get();
      context = facade == null ? null : store.get(facade);
    }
    if (context == null || context.isEnded() || !context.asyncStarted) {
      return;
    }
    store.remove(facade);
    int status = context.status;
    Argos.tracer().endStreaming(context, status > 0 ? status : 200, null, System.nanoTime());
  }
}
