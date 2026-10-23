/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.httpclient.demo11;

/** Helper of the httpclient-demo11 fixture module: JDK types only (10 §5.4, B-2). */
public final class Demo11Helper {
  private Demo11Helper() {}

  public static String[] withHeader(String[] headers) {
    String[] copy = new String[headers.length + 1];
    System.arraycopy(headers, 0, copy, 0, headers.length);
    copy[headers.length] = "tracestate";
    return copy;
  }
}
