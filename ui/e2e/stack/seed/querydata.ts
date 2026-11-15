// SPDX-License-Identifier: Apache-2.0
//
// Seed (a): the storage generator's data in the tiny profile (1/1000, seed 42) loaded into the
// stack's ClickHouse as argos_writer, plus the PostgreSQL rows that describe it (apps, instances,
// dictionary, error groups), through the loader WO-23 built for its API tests and the E2E stack:
//   ./gradlew :server:loadQueryTestData -Pquerydata.clickhouse=<url> -Pquerydata.postgres=<jdbc>
//       -Pquerydata.profile=tiny -Pquerydata.out=<dir>
// The schema is created by the server's migrations before (the loader does not apply it). The
// passwords reach Gradle only through the environment variables the loader names
// (ARGOS_CH_WRITER_PASSWORD, ARGOS_PG_PASSWORD), never on its command line. Placement "recent":
// the range ends at the start of the current minute (ADR-0010 C-170: anchored on today, UTC).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { z } from 'zod';

import type { Log } from '../stack.ts';

import { gradle } from './gradle.ts';

const Iso = z.string();

/** The part of query-test-data.json (QueryDataManifest, WO-23) the stack uses. */
export const QueryDataManifest = z.object({
  manifest_version: z.literal(1),
  profile: z.string(),
  placement: z.string(),
  seed: z.number().int(),
  loaded_at: Iso,
  range: z.object({ start: Iso, end: Iso, profile_from: Iso, metric_from: Iso }),
  apps: z.array(
    z.object({
      app_id: z.number().int(),
      name: z.string(),
      env: z.string(),
      instances: z.array(
        z.object({ instance_id: z.number().int(), name: z.string(), skew_ms: z.number() }),
      ),
    }),
  ),
  rows: z.record(z.string(), z.number().int()),
  samples: z.object({
    transactions: z.array(
      z.object({
        app_id: z.number().int(),
        instance_id: z.number().int(),
        tx_id: z.string(),
        ts: Iso,
        service_id: z.string(),
        kind: z.string(),
        profile_captured: z.boolean(),
        late: z.boolean(),
      }),
    ),
    traces: z.array(
      z.object({
        trace_id: z.string(),
        app_id: z.number().int(),
        ts: Iso,
        argos_generated: z.boolean(),
        transactions: z.number().int(),
      }),
    ),
  }),
});
export type QueryDataManifest = z.infer<typeof QueryDataManifest>;

export interface QueryDataTarget {
  readonly clickhouseUrl: string;
  readonly postgresJdbcUrl: string;
  readonly writerPassword: string;
  readonly appPassword: string;
  readonly outDir: string;
}

/** Loads the generator data; resolves with the loader's manifest. */
export async function loadQueryData(target: QueryDataTarget, log: Log): Promise<QueryDataManifest> {
  let partial = '';
  await gradle(
    [
      '-Pargos.ui.skip=true',
      ':server:loadQueryTestData',
      `-Pquerydata.clickhouse=${target.clickhouseUrl}`,
      `-Pquerydata.postgres=${target.postgresJdbcUrl}`,
      '-Pquerydata.profile=tiny',
      '-Pquerydata.placement=recent',
      `-Pquerydata.out=${target.outDir}`,
    ],
    {
      env: {
        ARGOS_CH_WRITER_PASSWORD: target.writerPassword,
        ARGOS_PG_PASSWORD: target.appPassword,
      },
      secrets: [target.writerPassword, target.appPassword],
      onOutput: (chunk) => {
        const lines = (partial + chunk).split(/\r?\n/);
        partial = lines.pop() ?? '';
        for (const line of lines) {
          if (line.startsWith('loadQueryTestData:')) {
            log(line);
          }
        }
      },
    },
  );
  const file = join(target.outDir, 'query-test-data.json');
  return QueryDataManifest.parse(JSON.parse(readFileSync(file, 'utf8')));
}
