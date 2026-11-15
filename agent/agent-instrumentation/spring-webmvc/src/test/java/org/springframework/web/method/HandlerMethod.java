/* SPDX-License-Identifier: Apache-2.0 */
package org.springframework.web.method;

/**
 * Test-only stand-in with the class name of Spring's {@code HandlerMethod} (spring-web is not on
 * the module's class path; the helper matches the handler by class name). Its {@link #toString()}
 * has the shape Spring builds once per handler method: {@code <bean type>#<method>(<parameter
 * simple names>)}. Own code, never packaged.
 */
public class HandlerMethod {
  private final String description;

  public HandlerMethod(String beanType, String method, String... parameterTypes) {
    StringBuilder text = new StringBuilder(beanType).append('#').append(method).append('(');
    for (int i = 0; i < parameterTypes.length; i++) {
      if (i > 0) {
        text.append(", ");
      }
      text.append(parameterTypes[i]);
    }
    this.description = text.append(')').toString();
  }

  @Override
  public String toString() {
    return description;
  }
}
