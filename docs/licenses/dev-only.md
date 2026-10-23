# Development-only components with non-allow-listed licenses

Argos artifacts (the agent jar, the server package and image, the UI bundle, and the published demo
images) may only contain components licensed under Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC,
0BSD, PostgreSQL, Unicode-DFS or CC0-1.0 (O-L03). `checkLicenses` enforces this on the shipped
configurations.

Some tests and sample applications need components under other licenses: commercial database
drivers, copyleft drivers, test databases and load tools. They are allowed **only** under these
conditions:

1. They appear only in test, sample-application or tool configurations, never in a configuration that
   feeds a shipped artifact (`runtimeClasspath` of `agent-dist`, `server`, `deploy`, or the UI
   production dependencies).
2. Sample applications that are published as images (`argos-demo-app`, `argos-demo-load`) use only
   allow-listed components. Components below are used by unpublished test fixtures only.
3. Tools (k6, testssl.sh) run as separate processes or containers; they are not linked into or copied
   into Argos code or artifacts.
4. Vendor drivers that cannot be redistributed are not committed to the repository and not downloaded
   by the public build. Only the trusted self-hosted matrix runners provide them.
5. Every such component is listed below. `checkDevOnlyLicenses` fails when a test-only dependency
   has a license outside the allow list and is not listed here; `pnpm -C ui check:licenses:dev` does
   the same for the installed UI tool packages.

## Machine-readable list

`checkDevOnlyLicenses` reads the first column of the table: Maven dependencies are written as
`` `group:artifact` `` (`*` matches every artifact of the group); `tool:` and `vendor:` entries are
components that are not resolved by Gradle and are listed for completeness. `npm:` entries are npm
packages of the UI build and test tool chain (the `devDependencies` closure of `ui/package.json`),
written as `` `npm:<package>` ``, where `*` matches any characters (`npm:@csstools/*` covers a scope,
`npm:lightningcss*` covers the per-platform native packages); `pnpm -C ui check:licenses:dev` checks
every installed package against them, and `checkDevOnlyLicenses` ignores them. Add a row in the same
pull request that introduces the dependency.

| Coordinates | Component | License | Used by | Why it is not shipped |
|---|---|---|---|---|
| `com.mysql:mysql-connector-j` | MySQL Connector/J | GPL-2.0-only WITH Universal-FOSS-exception-1.0 | JDBC compatibility tests, test-only sample apps | Copyleft driver; Argos instruments the application's own driver and never bundles one |
| `mysql:mysql-connector-java` | MySQL Connector/J (pre-8.0.31 coordinates) | GPL-2.0-only WITH Universal-FOSS-exception-1.0 | JDBC compatibility tests for older driver versions | Same as above |
| `org.mariadb.jdbc:mariadb-java-client` | MariaDB Connector/J | LGPL-2.1-or-later | JDBC compatibility tests, test-only sample apps | Copyleft driver; test fixture only |
| `com.oracle.database.jdbc:*` | Oracle JDBC (ojdbc8, ojdbc11, ucp) | Oracle Free Use Terms and Conditions (FUTC), proprietary | JDBC compatibility tests | Proprietary license; test fixture only |
| `vendor:tibero-jdbc` | Tibero JDBC driver | Proprietary (TmaxTibero) | JDBC matrix on trusted self-hosted runners | Proprietary and not redistributable; provided by the runner environment |
| `vendor:altibase-jdbc` | Altibase JDBC driver | Vendor license, to be verified when the driver is added | JDBC matrix on trusted self-hosted runners | Treated as non-allow-listed until verified; provided by the runner environment |
| `vendor:cubrid-jdbc` | CUBRID JDBC driver | Vendor license, to be verified when the driver is added | JDBC matrix on trusted self-hosted runners | Treated as non-allow-listed until verified; provided by the runner environment |
| `com.h2database:h2` | H2 Database Engine | MPL-2.0 OR EPL-1.0 | Unit and sample-app tests that need an embedded SQL database | Weak copyleft; test fixture only |
| `org.junit.jupiter:*` | JUnit Jupiter (JUnit 5 and 6) | EPL-2.0 | Unit, architecture and integration tests of every module | Test framework; test configurations only, never in a shipped configuration |
| `org.junit.platform:*` | JUnit Platform (launcher, engine, commons) | EPL-2.0 | Test launcher of every module | Test framework; test configurations only, never in a shipped configuration |
| `tool:k6` | Grafana k6 load generator | AGPL-3.0-only | Load scenarios and benchmarks (`sample-apps/load`, `benchTest`) | Run as a separate process or container; never linked into Argos |
| `tool:testssl.sh` | testssl.sh TLS scanner | GPL-2.0 (exact SPDX form to be confirmed when the tool is added) | TLS configuration checks in security tests | Run as a separate process or container; never linked into Argos |
| `npm:axe-core` | axe-core accessibility engine | MPL-2.0 | `eslint-plugin-jsx-a11y-x` (ESLint accessibility rules) | Not bundled: devDependencies closure only, absent from `ui/dist/licenses.txt` |
| `npm:lightningcss*` | Lightning CSS and its native package for the build platform (`lightningcss-<os>-<arch>`, e.g. `lightningcss-linux-x64-gnu`, `lightningcss-win32-x64-msvc`) | MPL-2.0 | `vite` (CSS processing in `vite build` and Vitest) | Not bundled: devDependencies closure only, absent from `ui/dist/licenses.txt` |
| `npm:caniuse-lite` | caniuse-lite browser support data | CC-BY-4.0 | `browserslist` < `@babel/helper-compilation-targets` < `@babel/core` < `eslint-plugin-react-hooks` | Not bundled: devDependencies closure only, absent from `ui/dist/licenses.txt` |
| `npm:minimatch` | minimatch 10.x (the 3.x line used by `eslint-plugin-react` is ISC) | BlueOak-1.0.0 | `eslint` (`@eslint/config-array`) and the ESLint plugins | Not bundled: devDependencies closure only, absent from `ui/dist/licenses.txt` |
| `npm:lru-cache` | lru-cache 11.x (the 5.x line used by `@babel/helper-compilation-targets` is ISC) | BlueOak-1.0.0 | `jsdom` (Vitest DOM environment) | Not bundled: devDependencies closure only, absent from `ui/dist/licenses.txt` |
| `npm:@csstools/*` | `@csstools/color-helpers`, `@csstools/css-syntax-patches-for-csstree` | MIT-0 | `jsdom` (directly and via `@asamuzakjp/css-color` < `@csstools/css-color-parser`) | Not bundled: devDependencies closure only, absent from `ui/dist/licenses.txt` |
