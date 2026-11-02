/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Proxy-based {@code HttpServletRequest}/{@code HttpServletResponse} of either servlet namespace.
 * Every call is recorded; a method the helpers are not expected to call throws, so the tests show
 * which request methods the helpers use (D-26: no parameters, body or session).
 */
final class FakeHttp {
  /** One servlet namespace: its interface and enum types. */
  static final class Api {
    final String name;
    final Class<?> request;
    final Class<?> response;
    final Class<?> dispatcherType;

    Api(String name, String packagePrefix) {
      this.name = name;
      try {
        ClassLoader loader = FakeHttp.class.getClassLoader();
        this.request = Class.forName(packagePrefix + "http.HttpServletRequest", false, loader);
        this.response = Class.forName(packagePrefix + "http.HttpServletResponse", false, loader);
        this.dispatcherType = Class.forName(packagePrefix + "DispatcherType", false, loader);
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(e);
      }
    }

    Object dispatcher(String type) {
      for (Object constant : dispatcherType.getEnumConstants()) {
        if (((Enum<?>) constant).name().equals(type)) {
          return constant;
        }
      }
      throw new IllegalArgumentException(type);
    }

    @Override
    public String toString() {
      return name;
    }
  }

  static final Api JAVAX = new Api("javax", "javax.servlet.");
  static final Api JAKARTA = new Api("jakarta", "jakarta.servlet.");

  private FakeHttp() {}

  /** A request; change the public fields between calls to model dispatches. */
  static final class Request implements InvocationHandler {
    final Api api;
    final Map<String, Object> attributes = new HashMap<String, Object>();
    final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    String method = "GET";
    String uri = "/api/orders/1";
    String contextPath = "";
    String userAgent = "test-client/1.0";
    String remoteAddr = "192.0.2.17";
    String dispatcher = "REQUEST";
    boolean asyncStarted;
    Object proxy;

    private Request(Api api) {
      this.api = api;
    }

    static Request of(Api api) {
      Request handler = new Request(api);
      handler.proxy =
          Proxy.newProxyInstance(
              FakeHttp.class.getClassLoader(), new Class<?>[] {api.request}, handler);
      return handler;
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // a proxy is equal to itself only
    public Object invoke(Object self, Method m, Object[] args) {
      String name = m.getName();
      calls.add(name);
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
        case "getDispatcherType":
          return api.dispatcher(dispatcher);
        case "getHeader":
          return "User-Agent".equalsIgnoreCase((String) args[0]) ? userAgent : null;
        case "getRemoteAddr":
          return remoteAddr;
        case "isAsyncStarted":
          return asyncStarted;
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
  }

  /** A response with a settable status. */
  static final class Response implements InvocationHandler {
    int status = 200;
    Object proxy;

    private Response() {}

    static Response of(Api api) {
      Response handler = new Response();
      handler.proxy =
          Proxy.newProxyInstance(
              FakeHttp.class.getClassLoader(), new Class<?>[] {api.response}, handler);
      return handler;
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // a proxy is equal to itself only
    public Object invoke(Object self, Method m, Object[] args) {
      switch (m.getName()) {
        case "getStatus":
          return status;
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
}
