/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.load.ClickHouseProbe;
import io.argos.server.testing.load.LoadServerJvm;
import io.argos.server.testing.load.ResidentSetProbe;
import java.nio.file.Path;
import java.util.List;

/**
 * What a {@link LoadPhase} can use: the options, the databases, the server child JVM and its
 * probes, the running generator and its counters, the load apps and the run's work directory.
 *
 * @param options the run options
 * @param env PostgreSQL and ClickHouse
 * @param server the server child JVM
 * @param rss the server's resident set probe
 * @param clickHouse the ClickHouse probe
 * @param generator the steady load
 * @param counters what the steady load sent
 * @param apps the load apps
 * @param work directory for files of the phase
 */
record LoadContext(
    LoadOptions options,
    ServerTestEnvironment env,
    LoadServerJvm server,
    ResidentSetProbe rss,
    ClickHouseProbe clickHouse,
    Generator generator,
    LoadCounters counters,
    List<LoadApp> apps,
    Path work) {}
