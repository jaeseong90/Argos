/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proxy-based {@code HttpServletRequest}/{@code HttpServletResponse} (and the session, servlet
 * context, mapping and async context they hand out) of either servlet namespace. Every call is
 * recorded with its arguments; a method the helpers are not expected to call throws, so the tests
 * show exactly which request methods the helpers use (D-26: no parameters, body or new session).
 */
final class FakeHttp {
  /** One servlet namespace: its interface and enum types. */
  static final class Api {
    final String name;
    final Class<?> request;
    final Class<?> response;
    final Class<?> dispatcherType;
    final Class<?> session;
    final Class<?> servletContext;
    final Class<?> asyncContext;
    final Class<?> mapping;
    final Class<?> mappingMatch;
    final Class<?> asyncListener;
    final Class<?> asyncEvent;

    Api(String name, String packagePrefix) {
      this.name = name;
      try {
        ClassLoader loader = FakeHttp.class.getClassLoader();
        this.request = Class.forName(packagePrefix + "http.HttpServletRequest", false, loader);
        this.response = Class.forName(packagePrefix + "http.HttpServletResponse", false, loader);
        this.dispatcherType = Class.forName(packagePrefix + "DispatcherType", false, loader);
        this.session = Class.forName(packagePrefix + "http.HttpSession", false, loader);
        this.servletContext = Class.forName(packagePrefix + "ServletContext", false, loader);
        this.asyncContext = Class.forName(packagePrefix + "AsyncContext", false, loader);
        this.mapping = Class.forName(packagePrefix + "http.HttpServletMapping", false, loader);
        this.mappingMatch = Class.forName(packagePrefix + "http.MappingMatch", false, loader);
        this.asyncListener = Class.forName(packagePrefix + "AsyncListener", false, loader);
        this.asyncEvent = Class.forName(packagePrefix + "AsyncEvent", false, loader);
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(e);
      }
    }

    Object dispatcher(String type) {
      return constant(dispatcherType, type);
    }

    Object match(String type) {
      return constant(mappingMatch, type);
    }

    private static Object constant(Class<?> enumType, String type) {
      for (Object constant : enumType.getEnumConstants()) {
        if (((Enum<?>) constant).name().equals(type)) {
          return constant;
        }
      }
      throw new IllegalArgumentException(type);
    }

    /** A new {@code AsyncEvent(asyncContext, request, response[, throwable])}. */
    Object event(Object request, Object response, Throwable error) {
      try {
        Class<?> requestType = Class.forName(packagePrefix() + "ServletRequest");
        Class<?> responseType = Class.forName(packagePrefix() + "ServletResponse");
        return asyncEvent
            .getConstructor(asyncContext, requestType, responseType, Throwable.class)
            .newInstance(null, request, response, error);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    private String packagePrefix() {
      return request.getPackage().getName().replace(".http", "") + ".";
    }

    @Override
    public String toString() {
      return name;
    }
  }

  static final Api JAVAX = new Api("javax", "javax.servlet.");
  static final Api JAKARTA = new Api("jakarta", "jakarta.servlet.");

  private FakeHttp() {}

  /** One recorded call: method name and arguments. */
  static final class Call {
    final String name;
    final Object[] args;

    Call(String name, Object[] args) {
      this.name = name;
      this.args = args == null ? new Object[0] : args.clone();
    }

    @Override
    public String toString() {
      return args.length == 0 ? name + "()" : name + java.util.Arrays.toString(args);
    }
  }

  /** A request; change the public fields between calls to model dispatches. */
  static final class Request implements InvocationHandler {
    final Api api;
    final Map<String, Object> attributes = new HashMap<String, Object>();
    final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    final List<Call> detailed = Collections.synchronizedList(new ArrayList<Call>());
    final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
    final List<Object> listeners = Collections.synchronizedList(new ArrayList<Object>());
    String method = "GET";
    String uri = "/api/orders/1";
    String contextPath = "";
    String queryString;
    String remoteAddr = "192.0.2.17";
    String dispatcher = "REQUEST";
    boolean asyncStarted;
    int servletMajorVersion = 4;
    String mappingPattern;
    String mappingMatch;
    Map<String, Object> sessionAttributes;
    Object proxy;

    private Request(Api api) {
      this.api = api;
      header("User-Agent", "test-client/1.0");
    }

    static Request of(Api api) {
      Request handler = new Request(api);
      handler.proxy =
          Proxy.newProxyInstance(
              FakeHttp.class.getClassLoader(), new Class<?>[] {api.request}, handler);
      return handler;
    }

    /**
     * Another request object over the same state: what the request wrapper of a FORWARD, INCLUDE,
     * ASYNC or ERROR dispatch is to the helpers (its own identity; the same attributes, dispatcher
     * type and listeners).
     */
    Object wrapper() {
      return Proxy.newProxyInstance(
          FakeHttp.class.getClassLoader(), new Class<?>[] {api.request}, this);
    }

    /** Adds a value of header {@code name} (names case-insensitive, like the servlet API). */
    Request header(String name, String value) {
      String key = name.toLowerCase(Locale.ROOT);
      List<String> values = headers.get(key);
      if (values == null) {
        values = new ArrayList<String>();
        headers.put(key, values);
      }
      values.add(value);
      return this;
    }

    /** True when a call named {@code name} with first argument {@code arg} was recorded. */
    boolean called(String name, Object arg) {
      synchronized (detailed) {
        for (Call call : detailed) {
          if (call.name.equals(name)
              && call.args.length > 0
              && (arg == null ? call.args[0] == null : arg.equals(call.args[0]))) {
            return true;
          }
        }
      }
      return false;
    }

    /** Calls named {@code name}, with any arguments. */
    int count(String name) {
      int count = 0;
      synchronized (detailed) {
        for (Call call : detailed) {
          if (call.name.equals(name)) {
            count++;
          }
        }
      }
      return count;
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // a proxy is equal to itself only
    public Object invoke(Object self, Method m, Object[] args) {
      String name = m.getName();
      calls.add(name);
      detailed.add(new Call(name, args));
      switch (name) {
        case "getAttribute":
          return attributes.get((String) args[0]);
        case "setAttribute":
          attributes.put((String) args[0], args[1]);
          return null;
        case "removeAttribute":
          attributes.remove((String) args[0]);
          return null;
        case "getMethod":
          return method;
        case "getRequestURI":
          return uri;
        case "getContextPath":
          return contextPath;
        case "getQueryString":
          return queryString;
        case "getDispatcherType":
          return api.dispatcher(dispatcher);
        case "getHeader":
          {
            List<String> values = headers.get(((String) args[0]).toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
          }
        case "getHeaders":
          {
            List<String> values = headers.get(((String) args[0]).toLowerCase(Locale.ROOT));
            return Collections.enumeration(
                values == null ? Collections.<String>emptyList() : values);
          }
        case "getRemoteAddr":
          return remoteAddr;
        case "isAsyncStarted":
          return asyncStarted;
        case "getSession":
          // getSession() and getSession(true) would create a session; the tests assert that the
          // helpers never call them (recorded above), so the fake never creates one.
          return session();
        case "getServletContext":
          return proxy(api.servletContext, new ServletContextHandler());
        case "getHttpServletMapping":
          return mappingPattern == null ? null : proxy(api.mapping, new MappingHandler());
        case "getAsyncContext":
          if (!asyncStarted) {
            throw new IllegalStateException("not async");
          }
          return proxy(api.asyncContext, new AsyncContextHandler());
        case "hashCode":
          return System.identityHashCode(self);
        case "equals":
          return self == args[0];
        case "toString":
          return "FakeRequest(" + method + " " + uri + ")";
        default:
          throw new UnsupportedOperationException("unexpected request call: " + name);
      }
    }

    /** The session if one exists (null otherwise; no session is ever created). */
    private Object session() {
      if (sessionAttributes == null) {
        return null;
      }
      return proxy(api.session, new SessionHandler());
    }

    private final class SessionHandler implements InvocationHandler {
      @Override
      @SuppressWarnings("ReferenceEquality")
      public Object invoke(Object self, Method m, Object[] args) {
        calls.add("session." + m.getName());
        switch (m.getName()) {
          case "getAttribute":
            return sessionAttributes.get((String) args[0]);
          case "hashCode":
            return System.identityHashCode(self);
          case "equals":
            return self == args[0];
          default:
            throw new UnsupportedOperationException("unexpected session call: " + m.getName());
        }
      }
    }

    private final class ServletContextHandler implements InvocationHandler {
      @Override
      @SuppressWarnings("ReferenceEquality")
      public Object invoke(Object self, Method m, Object[] args) {
        switch (m.getName()) {
          case "getMajorVersion":
            return servletMajorVersion;
          case "hashCode":
            return System.identityHashCode(self);
          case "equals":
            return self == args[0];
          default:
            throw new UnsupportedOperationException("unexpected context call: " + m.getName());
        }
      }
    }

    private final class MappingHandler implements InvocationHandler {
      @Override
      @SuppressWarnings("ReferenceEquality")
      public Object invoke(Object self, Method m, Object[] args) {
        switch (m.getName()) {
          case "getPattern":
            return mappingPattern;
          case "getMappingMatch":
            return mappingMatch == null ? null : api.match(mappingMatch);
          case "hashCode":
            return System.identityHashCode(self);
          case "equals":
            return self == args[0];
          default:
            throw new UnsupportedOperationException("unexpected mapping call: " + m.getName());
        }
      }
    }

    private final class AsyncContextHandler implements InvocationHandler {
      @Override
      @SuppressWarnings("ReferenceEquality")
      public Object invoke(Object self, Method m, Object[] args) {
        switch (m.getName()) {
          case "addListener":
            listeners.add(args[0]);
            return null;
          case "hashCode":
            return System.identityHashCode(self);
          case "equals":
            return self == args[0];
          default:
            throw new UnsupportedOperationException("unexpected async call: " + m.getName());
        }
      }
    }
  }

  /** A response with a settable status and headers. */
  static final class Response implements InvocationHandler {
    int status = 200;
    final Map<String, String> headers = new HashMap<String, String>();
    Object proxy;

    private Response() {}

    static Response of(Api api) {
      Response handler = new Response();
      handler.proxy = proxy(api.response, handler);
      return handler;
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // a proxy is equal to itself only
    public Object invoke(Object self, Method m, Object[] args) {
      switch (m.getName()) {
        case "getStatus":
          return status;
        case "containsHeader":
          return headers.containsKey(((String) args[0]).toLowerCase(Locale.ROOT));
        case "hashCode":
          return System.identityHashCode(self);
        case "equals":
          return self == args[0];
        case "toString":
          return "FakeResponse(" + status + ")";
        default:
          throw new UnsupportedOperationException("unexpected response call: " + m.getName());
      }
    }
  }

  static Object proxy(Class<?> type, InvocationHandler handler) {
    return Proxy.newProxyInstance(FakeHttp.class.getClassLoader(), new Class<?>[] {type}, handler);
  }
}
