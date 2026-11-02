// SPDX-License-Identifier: Apache-2.0
//
// WO-02 P-2: agent overhead harness (02 §11 P-2, 01 §7.3, 18 §7, ADR-0004 C-46 and C-49).
// Development-only (ArgosProject.isDevelopmentOnly): nothing here is shipped. build and check only
// compile the harness and run its unit tests (statistics, parsers, verdicts); no measurement runs in
// build. The measurement runs as a standalone jar, so that no Gradle daemon works on the measured
// machine while it runs:
//   ./gradlew :test:agent:overhead:overheadJar   -> build/overhead/overhead-bench.jar
//   test/agent/overhead/run-overhead.sh <load|startup|report> ...   (builds, then runs the jar)
// WO-05 moves the runner to test/bench (benchTest); WO-51 makes it the weekly benchmark.

plugins { id("argos.java-common") }

description = "WO-02 P-2 agent overhead harness (development only, never shipped)."

tasks.named<Jar>("jar") {
  manifest { attributes("Main-Class" to "io.argos.test.overhead.OverheadBench") }
}

val overheadJar by
  tasks.registering(Copy::class) {
    group = "overhead"
    description = "Copies the runnable harness jar to build/overhead/overhead-bench.jar."
    from(tasks.named("jar"))
    into(layout.buildDirectory.dir("overhead"))
    rename { "overhead-bench.jar" }
  }
