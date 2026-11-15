/* SPDX-License-Identifier: Apache-2.0 */
package org.springframework.web.method.support;

import org.springframework.web.method.HandlerMethod;

/**
 * Test-only stand-in for a subclass of Spring's {@code HandlerMethod} (the helper walks the
 * superclasses). Own code, never packaged.
 */
public class InvocableHandlerMethod extends HandlerMethod {
  public InvocableHandlerMethod(String beanType, String method, String... parameterTypes) {
    super(beanType, method, parameterTypes);
  }
}
