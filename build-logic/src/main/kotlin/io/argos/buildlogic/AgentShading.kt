// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

/**
 * Shading rules of the agent single jar (DSN-19-03, 19 §3.3 step 1, 10 §4.1).
 *
 * Every external package bundled under inst/ must be relocated below [PREFIX]. The list is the
 * single source for the shadow relocation and for the jar checks (checkShading,
 * checkRelocatedStrings). WO-02 finalises it against the actual transport stack (A-1).
 */
object AgentShading {
  const val PREFIX = "io.argos.agent.shaded"

  /** Packages of the agent's own code; never relocated. */
  val OWN_PACKAGES = listOf("io.argos.agent")

  /** External packages relocated to "$PREFIX.<package>". */
  val RELOCATED_PACKAGES =
    listOf(
      "net.bytebuddy",
      "io.grpc",
      "io.netty",
      "io.perfmark",
      "com.google.protobuf",
      "com.google.common",
      "com.google.thirdparty",
      "com.google.errorprone.annotations",
      "com.google.j2objc.annotations",
      "com.google.gson",
      "com.google.rpc",
      "com.google.type",
      "com.google.longrunning",
      "com.google.api",
      "org.jctools",
      "org.checkerframework",
      "org.codehaus.mojo.animal_sniffer",
    )

  /**
   * System property names read by shaded libraries that Shadow rewrites together with the package
   * names (19 §3.3 step 7, T-BLD-005). checkRelocatedStrings reports both forms and enforces
   * [PROPERTY_STRING_POLICY]. ByteBuddy names: every `net.bytebuddy.<property>` constant of
   * 1.18.15 (WO-02 scan); Netty names: 10 §10.3.
   */
  val PROPERTY_STRINGS =
    listOf(
      "io.netty.noUnsafe",
      "io.netty.tryReflectionSetAccessible",
      "io.netty.allocator.type",
      "io.netty.leakDetection.level",
      "io.netty.noPreferDirect",
      "io.netty.maxDirectMemory",
      "io.netty.eventLoopThreads",
      "io.netty.allocator.numDirectArenas",
      "net.bytebuddy.experimental",
      "net.bytebuddy.raw",
      "net.bytebuddy.nexus.disabled",
      "net.bytebuddy.dump",
      "net.bytebuddy.safe",
      "net.bytebuddy.securitymanager",
      "net.bytebuddy.validation",
    )

  /**
   * Expected form of [PROPERTY_STRINGS] in the agent jar (ADR-0003 C-23, decided by WO-02 in
   * ADR-0008): RELOCATED. The shaded libraries read `io.argos.agent.shaded.<original name>`, so
   * the agent sets those names (10 §10.3 `NettyProps`) and an application's own
   * `-Dio.netty.noUnsafe` or `-Dnet.bytebuddy.dump` never changes the agent's copy (and the agent
   * never changes the application's Netty or ByteBuddy). An original-form constant fails the build.
   */
  val PROPERTY_STRING_POLICY = RelocatedStringPolicy.RELOCATED

  /**
   * Classes of shaded libraries that reference `sun.misc.Unsafe` and are removed from the jar
   * (ADR-0003: exclude rather than allow-list; F-A35, T-BLD-004), as original class file paths.
   * ByteBuddy 1.18.15: lambda instrumentation via Unsafe and the Unsafe-based class injectors.
   * The agent never takes these code paths (no lambda instrumentation, no ByteBuddy class injection:
   * InitializationStrategy.NoOp, own HelperInjector); the attach test asserts that the remaining
   * classes that refer to them are never loaded.
   */
  val UNSAFE_EXCLUDED_CLASSES =
    listOf(
      "net/bytebuddy/agent/builder/AgentBuilder\$LambdaInstrumentationStrategy\$LambdaMetafactoryFactory\$Loader\$UsingUnsafe.class",
      "net/bytebuddy/dynamic/loading/ClassInjector\$UsingReflection\$Dispatcher\$UsingUnsafeInjection.class",
      "net/bytebuddy/dynamic/loading/ClassInjector\$UsingReflection\$Dispatcher\$UsingUnsafeOverride.class",
      "net/bytebuddy/dynamic/loading/ClassInjector\$UsingUnsafe\$Dispatcher\$CreationAction.class",
    )

  /** Entries removed from the shaded jar (signatures, JPMS and multi-release metadata). */
  val EXCLUDED_ENTRIES =
    listOf(
      "META-INF/*.SF",
      "META-INF/*.DSA",
      "META-INF/*.RSA",
      "META-INF/*.EC",
      "META-INF/versions/**",
      "META-INF/maven/**",
      "META-INF/proguard/**",
      "META-INF/native/**",
      "module-info.class",
    )
}
