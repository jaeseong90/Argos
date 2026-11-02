// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Minimal class file reading for jar checks (T-BLD-001, T-BLD-005); no bytecode library needed. */
object ClassFiles {

  /** Java 8 (F-A31). */
  const val JAVA_8_MAJOR = 52

  /** A class entry of a jar: `.class` anywhere or `.classdata` below inst/ (10 §4.1). */
  fun isClassEntry(name: String): Boolean = name.endsWith(".class") || name.endsWith(".classdata")

  /** Every class entry of [jar] with its bytes, in jar order. */
  fun classEntries(jar: File): Sequence<Pair<String, ByteArray>> = sequence {
    ZipFile(jar).use { zip ->
      for (entry in zip.entries()) {
        if (entry.isDirectory || !isClassEntry(entry.name)) continue
        yield(entry.name to zip.getInputStream(entry).use { it.readBytes() })
      }
    }
  }

  /** The major version of a class file, or null when [bytes] is not a class file. */
  fun majorVersion(bytes: ByteArray): Int? {
    if (bytes.size < 8) return null
    val magic =
      ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
        ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
    if (magic != 0xCAFEBABE.toInt()) return null
    return ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
  }

  /** Jar entry prefix of shaded third-party classes (10 §4.1, ADR-0003 C-22). */
  const val SHADED_ENTRY_PREFIX = "inst/io/argos/agent/shaded/"

  /** True for a class entry of a shaded third-party library. */
  fun isShadedEntry(name: String): Boolean = name.startsWith(SHADED_ENTRY_PREFIX)

  /**
   * Entries of [jar] that break the major version rule of ADR-0003 C-22 (or are not class files):
   * the agent's own classes must have exactly [ownMajor], shaded third-party classes (below
   * [SHADED_ENTRY_PREFIX]) at most [maxShadedMajor]. The goal of both is "loadable by Java 8".
   */
  fun majorVersionViolations(
    jar: File,
    ownMajor: Int = JAVA_8_MAJOR,
    maxShadedMajor: Int = JAVA_8_MAJOR,
  ): List<String> =
    classEntries(jar)
      .mapNotNull { (name, bytes) ->
        val major = majorVersion(bytes)
        when {
          major == null -> "$name (not a class file)"
          isShadedEntry(name) ->
            if (major <= maxShadedMajor) null else "$name (major $major, shaded classes must be <= $maxShadedMajor)"
          major == ownMajor -> null
          else -> "$name (major $major)"
        }
      }
      .toList()

  /** The values of all CONSTANT_String entries (string literals) of a class file. */
  fun stringConstants(bytes: ByteArray): List<String> {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    if (input.readInt() != 0xCAFEBABE.toInt()) throw IOException("not a class file")
    input.readUnsignedShort() // minor
    input.readUnsignedShort() // major
    val count = input.readUnsignedShort()
    val utf8 = arrayOfNulls<String>(count)
    val stringRefs = mutableListOf<Int>()
    var index = 1
    while (index < count) {
      when (val tag = input.readUnsignedByte()) {
        1 -> utf8[index] = input.readUTF()
        3, 4 -> input.skipBytes(4)
        5, 6 -> {
          input.skipBytes(8)
          index++ // long and double take two slots
        }
        7, 16, 19, 20 -> input.skipBytes(2)
        8 -> stringRefs += input.readUnsignedShort()
        9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
        15 -> input.skipBytes(3)
        else -> throw IOException("unknown constant pool tag $tag")
      }
      index++
    }
    return stringRefs.mapNotNull { utf8.getOrNull(it) }
  }
}
