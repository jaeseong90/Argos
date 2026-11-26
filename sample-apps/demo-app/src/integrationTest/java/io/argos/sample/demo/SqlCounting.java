/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.demo;

import java.io.Closeable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Test-only source of the demo app context: wraps the app's DataSource and counts executed
 * statements per kind of thread (HTTP request threads of Tomcat, the scheduler thread of the
 * batch), so that the test checks the SQL counts of DemoCatalog without an agent.
 */
@Configuration(proxyBeanMethods = false)
class SqlCounting {

  /** Statement executions on HTTP request threads and on the batch scheduler thread. */
  static final class Counter {
    final AtomicInteger http = new AtomicInteger();
    final AtomicInteger batch = new AtomicInteger();

    void executed() {
      String thread = Thread.currentThread().getName();
      if (thread.startsWith("http-")) {
        http.incrementAndGet();
      } else if (thread.startsWith("scheduling-")) {
        batch.incrementAndGet();
      }
    }
  }

  @Bean
  static Counter sqlCounter() {
    return new Counter();
  }

  @Bean
  static BeanPostProcessor countingDataSource(
      org.springframework.beans.factory.ObjectProvider<Counter> counter) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource) {
          return proxy(dataSource, counter.getObject(), DataSource.class, Closeable.class);
        }
        return bean;
      }
    };
  }

  private static Object proxy(Object target, Counter counter, Class<?>... interfaces) {
    InvocationHandler handler =
        (p, method, args) -> {
          Object result = invoke(target, method, args);
          String name = method.getName();
          if (result instanceof Connection c) {
            return proxy(c, counter, Connection.class);
          }
          if (result instanceof Statement s
              && (name.equals("createStatement")
                  || name.equals("prepareStatement")
                  || name.equals("prepareCall"))) {
            return proxy(s, counter, interfacesOf(s));
          }
          if (target instanceof Statement && name.startsWith("execute")) {
            counter.executed();
          }
          return result;
        };
    return Proxy.newProxyInstance(SqlCounting.class.getClassLoader(), interfaces, handler);
  }

  private static Class<?>[] interfacesOf(Statement s) {
    if (s instanceof java.sql.CallableStatement) {
      return new Class<?>[] {java.sql.CallableStatement.class};
    }
    if (s instanceof java.sql.PreparedStatement) {
      return new Class<?>[] {java.sql.PreparedStatement.class};
    }
    return new Class<?>[] {Statement.class};
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
