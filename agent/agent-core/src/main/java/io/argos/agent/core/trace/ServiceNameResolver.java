/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.Kind;

/**
 * The service name an HTTP transaction ends with (10 §6.1 exit, §6.2, F-A02, F-A29, F-A36, ADR-0004
 * C-48, SEC-34). Pure functions of the name the instrumentation left on the context; the tracer
 * applies them once at the end, before the per-agent cap ({@link ServiceNames#admit}).
 *
 * <p>Candidates in their order of precedence, all of them {@code [<context path> ]...} (F-A36):
 *
 * <ol>
 *   <li>A <b>fixed</b> name: spring-webmvc's {@code <METHOD> <handler pattern>} ({@code GET
 *       /api/orders/{id}}, read on the REQUEST dispatch only, so the ERROR dispatch's {@code
 *       /error} never names a transaction, C-48). Kept as it is (only cut to {@value #MAX_CHARS}
 *       chars): a pattern may contain {@code ?} and {@code *} wildcards that are not a query
 *       string.
 *   <li>A non-fixed name a module set as a better candidate than the path: spring-webmvc's {@code
 *       Controller#method} for a {@code HandlerMethod} without a pattern, or the servlet mapping
 *       pattern of Servlet 4+ ({@code HttpServletMapping}, {@code GET /items/*}, {@code GET *.do},
 *       {@code GET /plain/exact}) that the servlet module sets at the outermost exit when no other
 *       candidate exists. Such names are left as they are by {@link #fallback}: a token that does
 *       not start with {@code /} is not touched, and a mapping pattern has no identifier segments.
 *   <li>The provisional name the servlet module began the transaction with ({@code <METHOD>
 *       <path>}, the request URI without the context path): the F-A29 fallback normalizes its path
 *       ({@link #fallback}: numbers, UUIDs and hex strings of 32 characters or more become {@code
 *       {*}}).
 * </ol>
 *
 * <p>SEC-34: the query string never reaches a service name or the service dictionary. The servlet
 * module builds names from {@code getRequestURI()} (no query string) and handler patterns; {@link
 * #fallback} also drops anything from a {@code ?} or {@code #} of the path token of a non-fixed
 * name, so a provisional name built from a full URL by mistake still leaves no query string.
 *
 * <p>C-48: {@code /error} names no transaction because no module reads a name on the ERROR dispatch
 * (spring-webmvc: REQUEST dispatch only; servlet: an ERROR dispatch without the {@code argos.tx}
 * anchor is not recorded, WO-02 D-8). A request whose own path is {@code /error} keeps that path
 * like any other request.
 */
public final class ServiceNameResolver {
  /** Upper bound of a service name in characters (also {@code TracerImpl}'s bound). */
  public static final int MAX_CHARS = 1024;

  private ServiceNameResolver() {}

  /**
   * The final name of a transaction of {@link Kind} {@code kind} whose context holds {@code name}
   * ({@code fixed}: a module fixed it): the F-A29 fallback for a non-fixed HTTP name, then cut to
   * {@value #MAX_CHARS} characters. Null stays null.
   */
  public static String resolve(int kind, String name, boolean fixed) {
    if (name == null) {
      return null;
    }
    String resolved = kind == Kind.HTTP && !fixed ? fallback(name) : name;
    return cut(resolved);
  }

  /**
   * F-A29 fallback of a non-fixed HTTP name such as {@code GET /api/orders/42} or {@code /shop GET
   * /api/x/1}: the path token (the last space-separated token, when it starts with {@code /}) loses
   * its query string and fragment (SEC-34) and its path parameters, and every segment that is a
   * number, a UUID or a hex string of at least 32 characters becomes {@code {*}} ({@link
   * HttpPaths#normalize}). Other names ({@code OrderController#get}, {@code GET *.do}) are returned
   * unchanged.
   */
  public static String fallback(String name) {
    if (name == null) {
      return null;
    }
    int space = name.lastIndexOf(' ');
    String path = name.substring(space + 1);
    if (!path.startsWith("/")) {
      return name;
    }
    String normalized = HttpPaths.normalize(withoutQuery(path));
    return normalized.equals(path) ? name : name.substring(0, space + 1) + normalized;
  }

  /** {@code path} without anything from its first {@code ?} or {@code #} on (SEC-34). */
  static String withoutQuery(String path) {
    int end = path.length();
    int query = path.indexOf('?');
    if (query >= 0) {
      end = query;
    }
    int fragment = path.indexOf('#');
    if (fragment >= 0 && fragment < end) {
      end = fragment;
    }
    if (end == path.length()) {
      return path;
    }
    return end == 0 ? "/" : path.substring(0, end);
  }

  /** {@code name} cut to {@value #MAX_CHARS} characters (null stays null). */
  static String cut(String name) {
    return name != null && name.length() > MAX_CHARS ? name.substring(0, MAX_CHARS) : name;
  }
}
