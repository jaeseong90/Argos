// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Unit tests of the license policy used by checkLicenses/checkDevOnlyLicenses (T-BLD-007). */
@Tag("req:O-L03")
class LicensePolicyTest {
  private val policy =
    LicensePolicy.load(
      File(repositoryRoot, "build-logic/allowed-licenses.json"),
      File(repositoryRoot, "build-logic/license-overrides.json"),
    )

  @Test
  @DisplayName("T-BLD-007 the allow-list is exactly the O-L03 set")
  fun allowListMatchesOL03() {
    assertThat(policy.allowed)
      .containsExactlyInAnyOrder(
        "Apache-2.0",
        "MIT",
        "BSD-2-Clause",
        "BSD-3-Clause",
        "ISC",
        "0BSD",
        "PostgreSQL",
        "Unicode-DFS-2015",
        "Unicode-DFS-2016",
        "CC0-1.0",
      )
  }

  @Test
  @DisplayName("T-BLD-007 maps common POM license names and URLs to SPDX identifiers")
  fun mapsCommonSpellings() {
    val names =
      mapOf(
        "The Apache Software License, Version 2.0" to "Apache-2.0",
        "Apache License, Version 2.0" to "Apache-2.0",
        "Apache-2.0" to "Apache-2.0",
        "Apache 2" to "Apache-2.0",
        "The MIT License (MIT)" to "MIT",
        "MIT License" to "MIT",
        "BSD-3-Clause" to "BSD-3-Clause",
        "New BSD License" to "BSD-3-Clause",
        "Eclipse Distribution License - v 1.0" to "BSD-3-Clause",
        "BSD 2-Clause License" to "BSD-2-Clause",
        "Public Domain, per Creative Commons CC0" to "CC0-1.0",
        "GNU General Public License v3.0" to "GPL-3.0-only",
        "GNU General Public License, version 2 with the GNU Classpath Exception" to
          "GPL-2.0-only WITH Classpath-exception-2.0",
        "The GNU General Public License, v2 with Universal FOSS Exception, v1.0" to
          "GPL-2.0-only WITH Universal-FOSS-exception-1.0",
        "GNU Lesser General Public License v2.1" to "LGPL-2.1-only",
        "Eclipse Public License - v 2.0" to "EPL-2.0",
        "MIT OR Apache-2.0" to "MIT OR Apache-2.0",
      )
    names.forEach { (name, id) -> assertThat(SpdxNames.fromName(name)).`as`(name).isEqualTo(id) }
    assertThat(SpdxNames.fromUrl("https://www.apache.org/licenses/LICENSE-2.0.txt")).isEqualTo("Apache-2.0")
    assertThat(SpdxNames.fromUrl("https://opensource.org/licenses/MIT")).isEqualTo("MIT")
    assertThat(SpdxNames.fromUrl("https://www.gnu.org/licenses/gpl-3.0.txt")).isEqualTo("GPL-3.0-only")
  }

  @Test
  @DisplayName("T-BLD-007 ambiguous names stay unknown (no false allow): 'BSD License', 'Public Domain'")
  fun ambiguousNamesStayUnknown() {
    listOf("BSD License", "BSD", "Public Domain", "Bouncy Castle Licence", "Proprietary").forEach {
      assertThat(SpdxNames.fromName(it)).`as`(it).isNull()
    }
    val resolved = policy.resolve("com.example:x", "1", listOf(DeclaredLicense("BSD License", null)))
    assertThat(resolved.expression).isNull()
    assertThat(resolved.allowed).isFalse()
  }

  @Test
  @DisplayName("T-BLD-007 SPDX expressions: OR needs one allowed branch, AND needs all, WITH is exact")
  fun evaluatesExpressions() {
    assertThat(policy.isAllowed("MIT OR GPL-3.0-only")).isTrue()
    assertThat(policy.isAllowed("(EPL-2.0) OR (Apache-2.0)")).isTrue()
    assertThat(policy.isAllowed("MIT AND GPL-3.0-only")).isFalse()
    assertThat(policy.isAllowed("GPL-2.0-only WITH Classpath-exception-2.0")).isFalse()
    assertThat(policy.isAllowed("EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0")).isFalse()
    assertThatThrownBy { SpdxExpression.parse("MIT OR") }.isInstanceOf(GradleException::class.java)
    assertThatThrownBy { SpdxExpression.parse("(MIT") }.isInstanceOf(GradleException::class.java)
  }

  @Test
  @DisplayName("T-BLD-007 several declared licenses are a choice (dual licensing)")
  fun dualLicensing() {
    val declared = listOf(DeclaredLicense("EPL 2.0", null), DeclaredLicense("Apache License, Version 2.0", null))
    val resolved = policy.resolve("com.example:dual", "1", declared)
    assertThat(resolved.expression).isEqualTo("(Apache-2.0) OR (EPL-2.0)")
    assertThat(resolved.allowed).isTrue()
  }

  @Test
  @DisplayName("T-BLD-007 overrides need a module, an SPDX license and a source URL")
  fun validatesOverrides(@TempDir dir: File) {
    val valid =
      File(dir, "valid.json").apply {
        writeText(
          """{"overrides":[{"module":"com.example:x","version":"1.0","license":"MIT",""" +
            """"source":"https://example.org/LICENSE"}]}"""
        )
      }
    val overrides = LicensePolicy.readOverrides(valid)
    assertThat(overrides).containsExactly(LicenseOverride("com.example:x", "1.0", "MIT", "https://example.org/LICENSE"))
    val overridden = LicensePolicy(policy.allowed, overrides).resolve("com.example:x", "1.0", emptyList())
    assertThat(overridden.allowed).isTrue()
    assertThat(overridden.origin).isEqualTo("override")
    val otherVersion = LicensePolicy(policy.allowed, overrides).resolve("com.example:x", "2.0", emptyList())
    assertThat(otherVersion.allowed).isFalse()

    val noSource =
      File(dir, "nosource.json").apply {
        writeText("""{"overrides":[{"module":"com.example:x","license":"MIT"}]}""")
      }
    assertThatThrownBy { LicensePolicy.readOverrides(noSource) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("needs a 'source' URL")
  }

  @Test
  @DisplayName("T-BLD-007 the dev-only list is read from docs/licenses/dev-only.md (Maven rows only)")
  fun readsDevOnlyList() {
    val devOnly = LicensePolicy.readDevOnly(File(repositoryRoot, "docs/licenses/dev-only.md"))
    assertThat(devOnly)
      .contains("com.mysql:mysql-connector-j", "org.mariadb.jdbc:mariadb-java-client", "com.h2database:h2")
      .contains("com.oracle.database.jdbc:*")
      .noneMatch { it.startsWith("tool:") || it.startsWith("vendor:") }
    assertThat(LicensePolicy.devOnlyMatches(devOnly, "com.oracle.database.jdbc:ojdbc11")).isTrue()
    assertThat(LicensePolicy.devOnlyMatches(devOnly, "com.example:gpl-lib")).isFalse()
  }

  @Test
  @DisplayName("T-BLD-007 npm: rows of dev-only.md are not read as Maven coordinates")
  fun skipsNpmRows(@TempDir dir: File) {
    val list =
      File(dir, "dev-only.md").apply {
        writeText(
          """
          | Coordinates | Component | License | Used by | Why it is not shipped |
          |---|---|---|---|---|
          | `com.example:gpl-lib` | x | GPL-3.0-only | x | x |
          | `tool:k6` | x | AGPL-3.0-only | x | x |
          | `vendor:driver` | x | Proprietary | x | x |
          | `npm:axe-core` | x | MPL-2.0 | x | x |
          | `npm:lightningcss*` | x | MPL-2.0 | x | x |
          | `npm:@csstools/*` | x | MIT-0 | x | x |
          """
            .trimIndent()
        )
      }
    assertThat(LicensePolicy.readDevOnly(list)).containsExactly("com.example:gpl-lib")

    val real = LicensePolicy.readDevOnly(File(repositoryRoot, "docs/licenses/dev-only.md"))
    assertThat(File(repositoryRoot, "docs/licenses/dev-only.md").readText()).contains("| `npm:axe-core` |")
    assertThat(real).noneMatch { it.startsWith("npm:") }
    // An npm package name never matches a Maven module through the dev-only list.
    assertThat(LicensePolicy.devOnlyMatches(real, "npm:axe-core")).isFalse()
  }
}
