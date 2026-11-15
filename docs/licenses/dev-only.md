# Development-only components with non-allow-listed licenses

Argos artifacts (the agent jar, the server package and image, the UI bundle, and the published demo
images) may only contain components licensed under Apache-2.0, MIT, MIT-0, BSD-2-Clause, BSD-3-Clause, ISC,
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
| `org.mariadb.jdbc:mariadb-java-client` | MariaDB Connector/J | LGPL-2.1-or-later | JDBC compatibility tests, test-only sample apps (`shop-boot3` `SAMPLE_DB=mariadb`, WO-13) | Copyleft driver; test fixture only |
| `com.oracle.database.jdbc:*` | Oracle JDBC (ojdbc8, ojdbc11, ucp) | Oracle Free Use Terms and Conditions (FUTC), proprietary | JDBC compatibility tests; `ojdbc11` on the runtime class path of the test-only `shop-boot3` app (`SAMPLE_DB=oracle`, WO-13) | Proprietary license; test fixture only |
| `vendor:tibero-jdbc` | Tibero JDBC driver | Proprietary (TmaxTibero) | JDBC matrix on trusted self-hosted runners | Proprietary and not redistributable; provided by the runner environment |
| `vendor:altibase-jdbc` | Altibase JDBC driver | Vendor license, to be verified when the driver is added | JDBC matrix on trusted self-hosted runners | Treated as non-allow-listed until verified; provided by the runner environment |
| `vendor:cubrid-jdbc` | CUBRID JDBC driver | Vendor license, to be verified when the driver is added | JDBC matrix on trusted self-hosted runners | Treated as non-allow-listed until verified; provided by the runner environment |
| `com.h2database:h2` | H2 Database Engine | MPL-2.0 OR EPL-1.0 | Unit and sample-app tests that need an embedded SQL database | Weak copyleft; test fixture only |
| `org.hibernate.orm:*` | Hibernate ORM 6 (hibernate-core, hibernate-envers, hibernate-jcache) | LGPL-2.1-or-later | Sample apps with JPA (`shop-boot3`, `shop-heavy`, `shop-war-jakarta`; 18 §4.1 Hibernate 6) | Test fixtures and development images only; the published `argos-demo-app` must not be built from this configuration (condition 2, WO-41). Argos instruments the application's own JPA provider |
| `org.hibernate:hibernate-core` | Hibernate ORM 5 | LGPL-2.1-or-later | `shop-boot2`, `shop-war-javax` (18 §4.1 Hibernate 5) | Test fixtures and development images only; the published `argos-demo-app` must not be built from this configuration (condition 2, WO-41) |
| `org.hibernate.common:hibernate-commons-annotations` | Hibernate Commons Annotations 5.1 (the 6.x and later lines are Apache-2.0) | LGPL-2.1-or-later | `shop-boot2`, `shop-war-javax` (Hibernate 5) | Test fixtures and development images only; the published `argos-demo-app` must not be built from this configuration (condition 2, WO-41) |
| `antlr:antlr` | ANTLR 2.7.7 (HQL parser of Hibernate 5) | ANTLR 2 license (BSD style; the POM says only "BSD License") | `shop-boot2`, `shop-war-javax` (Hibernate 5) | Test fixtures and development images only; the published `argos-demo-app` must not be built from this configuration (condition 2, WO-41) |
| `ch.qos.logback:*` | Logback (classic, core): the default logging of Spring Boot | EPL-2.0 OR LGPL-2.1-only (1.2.x: EPL-1.0 OR LGPL-2.1-only) | Spring Boot sample apps | Test fixtures and development images only; the published `argos-demo-app` must not be built from this configuration (condition 2, WO-41). Argos code itself never uses an application logging framework |
| `jakarta.annotation:jakarta.annotation-api` | Jakarta Annotations API | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | Spring Boot sample apps (Tomcat starter) | Test fixtures and development images only; the published `argos-demo-app` must not be built from this configuration (condition 2, WO-41) |
| `jakarta.transaction:jakarta.transaction-api` | Jakarta Transactions API | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | Sample apps with JPA | Test fixtures and development images only; the published `argos-demo-app` must not be built from this configuration (condition 2, WO-41) |
| `org.aspectj:aspectjweaver` | AspectJ weaver | EPL-2.0 | Spring Data JPA and AOP starters of the sample apps | Test fixtures and development images only; the published `argos-demo-app` must not be built from this configuration (condition 2, WO-41) |
| `org.bouncycastle:bcprov-jdk18on` | Bouncy Castle provider | Bouncy Castle Licence (MIT style; not mapped by the license policy) | `shop-heavy` (Spring Cloud starter) | Test fixtures and development images only; the published `argos-demo-app` must not be built from this configuration (condition 2, WO-41) |
| `org.reactivestreams:reactive-streams` | Reactive Streams API | MIT-0 | Sample apps with WebClient (Reactor) | MIT-0 is on the allow-list since ADR-0004 C-42; this row is kept only as history and can be removed. |
| `org.apache.tomcat:tomcat` | Apache Tomcat binary distribution (zip) staged as `CATALINA_HOME` for the WAR smokes and development images; bundles the Eclipse JDT Core Batch Compiler `ecj` (EPL-1.0 in 9.0, EPL-2.0 in 10.1/11) and Java EE/Jakarta EE XML schemas (CDDL-1.0 / EPL-2.0), as listed in its LICENSE | Apache-2.0; subcomponents EPL-1.0, EPL-2.0, CDDL-1.0 | `shop-war-javax` (Tomcat 9), `shop-war-jakarta` (Tomcat 10.1, 11): `sampleTomcat*` configurations, which `checkDevOnlyLicenses` does not see (not a `*RuntimeClasspath`) | Test fixture and development images only. The staging step drops `lib/ecj-*.jar` (the sample apps have no JSPs); the schemas stay inside Tomcat's own jars |
| `javax.servlet:javax.servlet-api` | Java Servlet API 3.1 (the lowest javax version the agent supports) and 4.0.1 (the Servlet 4 `HttpServletMapping` helper only) | CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0 | Agent instrumentation modules `servlet` and `spring-webmvc`: 3.1 `compileOnly` and the module unit tests (WO-02); `servlet` source set `servlet4`: 4.0.1 `compileOnly`, and 4.0.1 on the `servlet` unit test classpath (WO-12); `servlet-async`: 3.1 `compileOnly` and the module unit tests (WO-12); `agent-testing`: 3.1 `compileOnly` of the Spring-free servlet fixture WAR `servletFixtureJavax`, deployed to the staged test Tomcat 9 only (WO-12) | Compile-time API only: `compileOnly` keeps it out of the agent jar (`checkForbiddenDependencies` fails on servlet APIs in a runtime classpath); at run time the application's own servlet classes are instrumented. The fixture WAR has no servlet API inside (the container provides it) and is never shipped |
| `jakarta.servlet:jakarta.servlet-api` | Jakarta Servlet API 5.0 (the lowest jakarta version the agent supports) | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | Agent instrumentation modules `servlet` and `spring-webmvc`: `compileOnly` and the module unit tests (WO-02); `servlet-async`: `compileOnly` and its unit tests, and the agent-testing `servletFixtureJakarta` WAR compile classpath (WO-12) | Same as above |
| `org.junit.jupiter:*` | JUnit Jupiter (JUnit 5 and 6) | EPL-2.0 | Unit, architecture and integration tests of every module | Test framework; test configurations only, never in a shipped configuration |
| `org.junit.platform:*` | JUnit Platform (launcher, engine, commons) | EPL-2.0 | Test launcher of every module | Test framework; test configurations only, never in a shipped configuration |
| `tool:docker-image-mysql` | MySQL Community Server container image `mysql:8.4` (pinned by digest in the agent-testing `jdbc` suite) | GPL-2.0-only (MySQL Server); OS packages under their own licenses | JDBC integration tests of the agent (`agent-testing` package `jdbc`, `-Pagent.jdbc.containers=true`; WO-13, 18 §6.3) | Run as a separate container by Testcontainers; never linked into, copied into or shipped with Argos |
| `tool:docker-image-mariadb` | MariaDB Server container image `mariadb:11.4` (pinned by digest) | GPL-2.0-only (MariaDB Server); OS packages under their own licenses | Same as above | Same as above |
| `tool:docker-image-mssql` | Microsoft SQL Server 2022 container image `mcr.microsoft.com/mssql/server:2022-latest` (pinned by digest), Developer edition (`MSSQL_PID=Developer`) | Microsoft Software License Terms (EULA, proprietary; development and test use only); the tests set `ACCEPT_EULA=Y` | Same as above | Proprietary database run as a separate container for tests only; never redistributed. The JDBC driver `com.microsoft.sqlserver:mssql-jdbc` is MIT (allow-listed) and lives in the dev-only `shop-boot3` app |
| `tool:docker-image-oracle-free` | Oracle Database Free container image `gvenzl/oracle-free:23-slim-faststart` (pinned by digest; the image contains Oracle Database Free 23ai/26ai) | Oracle Free Use Terms and Conditions (FUTC) for Oracle Database Free; the image build scripts (gvenzl/oci-oracle-free) are Apache-2.0 | Optional Oracle run of the JDBC integration tests (`-Pagent.jdbc.oracle=true`; WO-13, 18 §18 open item 1, ADR-0010 C-149) | Proprietary database run as a separate container for tests only, never in the default build; never redistributed. In-house Oracle 19c, Tibero, Altibase, CUBRID and DB2 are reached through `ARGOS_TEST_<DB>_URL` on trusted hosts (human check) |
| `tool:k6` | Grafana k6 load generator | AGPL-3.0-only | Load scenarios and benchmarks (`sample-apps/load`, `benchTest`) | Run as a separate process or container; never linked into Argos |
| `tool:testssl.sh` | testssl.sh TLS scanner | GPL-2.0 (exact SPDX form to be confirmed when the tool is added) | TLS configuration checks in security tests | Run as a separate process or container; never linked into Argos |
| `tool:playwright-browsers` | Browser builds Playwright downloads: Chromium (Chrome for Testing, incl. chromium-headless-shell), an ffmpeg build, winldd (Windows) | BSD-3-Clause and third-party licenses (Chromium); LGPL-2.1-or-later (ffmpeg) | `pnpm -C ui e2e` and `perf` (`playwright:install`) | Downloaded to the user cache (`%LOCALAPPDATA%\ms-playwright`, `~/.cache/ms-playwright`) and run only as separate processes; never in the repository or an artifact |
| `npm:axe-core` | axe-core accessibility engine | MPL-2.0 | `eslint-plugin-jsx-a11y-x` (ESLint accessibility rules); direct devDependency and `@axe-core/playwright` for the Playwright E2E accessibility scan (axe Critical 0, WO-30, ADR-0010 C-169) | E2E accessibility scan, never bundled: devDependencies closure only, absent from `ui/dist/licenses.txt`; injected into the page under test by Playwright, not served by Argos |
| `npm:@axe-core/playwright` | axe-core integration for Playwright | MPL-2.0 | Playwright E2E accessibility scan in `ui/e2e` (axe Critical 0, WO-30, ADR-0010 C-169) | E2E accessibility scan, never bundled: devDependencies closure only, absent from `ui/dist/licenses.txt` (ESLint `import-x/no-extraneous-dependencies` keeps devDependencies out of `ui/src`) |
| `npm:argparse` | argparse 2.x (JavaScript port of Python's argparse) | Python-2.0 | `js-yaml` < `@redocly/openapi-core` < `openapi-typescript` (`pnpm gen:api`, which generates `ui/src/api/generated/schema.ts`, WO-30) | Code generator dependency only: devDependencies closure, absent from `ui/dist/licenses.txt`; the generated types contain none of its code |
| `npm:lightningcss*` | Lightning CSS and its native package for the build platform (`lightningcss-<os>-<arch>`, e.g. `lightningcss-linux-x64-gnu`, `lightningcss-win32-x64-msvc`) | MPL-2.0 | `vite` (CSS processing in `vite build` and Vitest) | Not bundled: devDependencies closure only, absent from `ui/dist/licenses.txt` |
| `npm:caniuse-lite` | caniuse-lite browser support data | CC-BY-4.0 | `browserslist` < `@babel/helper-compilation-targets` < `@babel/core` < `eslint-plugin-react-hooks` | Not bundled: devDependencies closure only, absent from `ui/dist/licenses.txt` |
| `npm:minimatch` | minimatch 10.x (the 3.x line used by `eslint-plugin-react` is ISC) | BlueOak-1.0.0 | `eslint` (`@eslint/config-array`) and the ESLint plugins | Not bundled: devDependencies closure only, absent from `ui/dist/licenses.txt` |
| `npm:lru-cache` | lru-cache 11.x (the 5.x line used by `@babel/helper-compilation-targets` is ISC) | BlueOak-1.0.0 | `jsdom` (Vitest DOM environment) | Not bundled: devDependencies closure only, absent from `ui/dist/licenses.txt` |
| `npm:@csstools/*` | `@csstools/color-helpers`, `@csstools/css-syntax-patches-for-csstree` | MIT-0 | `jsdom` (directly and via `@asamuzakjp/css-color` < `@csstools/css-color-parser`) | MIT-0 is on the allow-list since ADR-0004 C-42; this row is kept only as history and can be removed. |
