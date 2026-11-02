/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.api;

/**
 * Module IDs (10 §5.1 {@code moduleId()}, index into {@code ModuleHealth}, 0..63). One constant per
 * Gradle instrumentation module; IDs are never reused. The core rejects a module whose ID is out of
 * range or already taken.
 */
public final class ModuleIds {
  public static final int SERVLET = 1;
  public static final int SPRING_WEBMVC = 2;
  public static final int JDBC = 3;
  public static final int POOL = 4;
  public static final int TOMCAT = 5;
  public static final int EXECUTOR = 6;
  public static final int SERVLET_ASYNC = 7;
  public static final int SCHEDULER = 8;
  public static final int LOGGING = 9;
  public static final int CUSTOM = 10;
  public static final int HTTPCLIENT_JDK = 11;
  public static final int HTTPCLIENT_APACHE4 = 12;
  public static final int HTTPCLIENT_APACHE5 = 13;
  public static final int HTTPCLIENT_OKHTTP = 14;
  public static final int HTTPCLIENT_JDK11 = 15;
  public static final int HTTPCLIENT_REACTORNETTY = 16;

  /** First ID reserved for test-only modules of agent-testing (never in a release jar). */
  public static final int TEST_FIRST = 60;

  /** Highest valid module ID. */
  public static final int MAX = 63;

  private ModuleIds() {}
}
