// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/** Repository root (set by build-logic/build.gradle.kts). */
val repositoryRoot: File =
  File(System.getProperty("argos.repositoryRoot") ?: error("argos.repositoryRoot is not set"))
    .canonicalFile

/**
 * A minimal, valid class file: `public class <internalName> extends java/lang/Object` with the
 * given string constants and class references in its constant pool (javap can read it).
 */
fun classFile(
  internalName: String,
  major: Int = 52,
  strings: List<String> = emptyList(),
  classRefs: List<String> = emptyList(),
): ByteArray {
  val bytes = ByteArrayOutputStream()
  DataOutputStream(bytes).use { out ->
    out.writeInt(0xCAFEBABE.toInt())
    out.writeShort(0) // minor
    out.writeShort(major)
    // #1 Utf8 name, #2 Class #1, #3 Utf8 java/lang/Object, #4 Class #3, then pairs.
    val pairs = strings.size + classRefs.size
    out.writeShort(5 + 2 * pairs)
    out.writeByte(1)
    out.writeUTF(internalName)
    out.writeByte(7)
    out.writeShort(1)
    out.writeByte(1)
    out.writeUTF("java/lang/Object")
    out.writeByte(7)
    out.writeShort(3)
    var index = 5
    for (value in strings) {
      out.writeByte(1)
      out.writeUTF(value)
      out.writeByte(8)
      out.writeShort(index)
      index += 2
    }
    for (value in classRefs) {
      out.writeByte(1)
      out.writeUTF(value)
      out.writeByte(7)
      out.writeShort(index)
      index += 2
    }
    out.writeShort(0x0021) // ACC_PUBLIC | ACC_SUPER
    out.writeShort(2) // this_class
    out.writeShort(4) // super_class
    out.writeShort(0) // interfaces
    out.writeShort(0) // fields
    out.writeShort(0) // methods
    out.writeShort(0) // attributes
  }
  return bytes.toByteArray()
}

/** Writes a jar with [manifest] attributes (null: no MANIFEST.MF) and [entries] in order. */
fun writeJar(file: File, manifest: Map<String, String>?, entries: Map<String, ByteArray>): File {
  file.parentFile.mkdirs()
  val stream = file.outputStream()
  val jar =
    if (manifest == null) JarOutputStream(stream)
    else
      JarOutputStream(
        stream,
        Manifest().apply {
          mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
          manifest.forEach { (key, value) -> mainAttributes[Attributes.Name(key)] = value }
        },
      )
  jar.use {
    for ((name, content) in entries) {
      it.putNextEntry(JarEntry(name))
      it.write(content)
      it.closeEntry()
    }
  }
  return file
}

data class ScriptResult(val exitCode: Int, val output: String)

/** Runs a repository bash script with Git Bash on Windows (BashLocator), bash elsewhere. */
fun runScript(script: File, args: List<String>, env: Map<String, String> = emptyMap()): ScriptResult {
  val command = listOf(BashLocator.find(), script.invariantSeparatorsPath) + args
  val process =
    ProcessBuilder(command)
      .redirectErrorStream(true)
      .apply {
        environment()["JAVA_HOME"] = System.getProperty("java.home")
        environment().putAll(env)
      }
      .start()
  process.outputStream.close()
  val output = process.inputStream.bufferedReader().readText()
  check(process.waitFor(300, TimeUnit.SECONDS)) { "script timed out: $command" }
  return ScriptResult(process.exitValue(), output)
}
