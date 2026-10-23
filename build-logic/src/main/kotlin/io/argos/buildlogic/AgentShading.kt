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
   * System property names read by shaded libraries that Shadow may rewrite together with the
   * package names (19 §3.3 step 7, T-BLD-005). checkRelocatedStrings reports both forms.
   */
  val PROPERTY_STRINGS =
    listOf(
      "io.netty.noUnsafe",
      "io.netty.tryReflectionSetAccessible",
      "io.netty.allocator.type",
      "io.netty.leakDetection.level",
      "net.bytebuddy.experimental",
      "net.bytebuddy.raw",
      "net.bytebuddy.nexus.disabled",
    )

  /**
   * Expected form of [PROPERTY_STRINGS] in the agent jar. UNDECIDED until 10 §10.3 / WO-02 decide
   * between setting the relocated names and excluding the names from relocation (19 §13 #6).
   */
  val PROPERTY_STRING_POLICY = RelocatedStringPolicy.UNDECIDED

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
