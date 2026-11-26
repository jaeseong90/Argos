// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.demo

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * The `demoImage { }` extension of the argos.demo-image convention plugin (WO-41).
 *
 * ```
 * demoImage {
 *   imageName = DemoImage.APP_IMAGE_NAME
 *   title = "argos-demo-app"
 *   description = "..."
 *   jvmFlags = DemoImage.APP_JVM_FLAGS
 *   environment = DemoImage.APP_ENVIRONMENT
 *   ports = listOf("8080")
 *   withAgent = true      // :agent:agent-dist:agentJar at DemoImage.AGENT_JAR
 * }
 * ```
 *
 * The main class is the sample app's (`sampleApp.mainClass`, set by `bootJar` or `executableJar`).
 */
abstract class DemoImageExtension {

  /** Local image name; the tag is the project version. */
  abstract val imageName: Property<String>

  /** OCI title label. */
  abstract val title: Property<String>

  /** OCI description label. */
  abstract val description: Property<String>

  /** JVM flags of the entrypoint. */
  abstract val jvmFlags: ListProperty<String>

  /** Environment defaults of the image (Compose may override each one). */
  abstract val environment: MapProperty<String, String>

  /** Exposed ports (documentation only; nothing is published by the image). */
  abstract val ports: ListProperty<String>

  /** Whether the image carries the Argos agent jar at [DemoImage.AGENT_JAR]. */
  abstract val withAgent: Property<Boolean>
}
