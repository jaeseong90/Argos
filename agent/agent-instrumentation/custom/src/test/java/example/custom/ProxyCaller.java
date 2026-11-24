/* SPDX-License-Identifier: Apache-2.0 */
package example.custom;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;

/**
 * Calls {@link Hidden#value()} through a JDK proxy. {@code Hidden} is package-private, so the JDK
 * defines the proxy class in this package ({@code example.custom.$Proxy<n>}), where a glob such as
 * {@code example.custom.**} would reach it if generated classes were not excluded. The handler is a
 * lambda: its class is generated and its body a synthetic method, neither is ever a step.
 */
public class ProxyCaller implements Callable<Object> {
  /** The proxy class of the last call. */
  public static volatile String proxyClassName;

  @Override
  public Object call() {
    final Hidden target = new HiddenImpl();
    InvocationHandler handler = (self, method, args) -> method.invoke(target, args);
    Hidden proxy =
        (Hidden)
            Proxy.newProxyInstance(
                Hidden.class.getClassLoader(), new Class<?>[] {Hidden.class}, handler);
    proxyClassName = proxy.getClass().getName();
    return Integer.valueOf(proxy.value());
  }
}
