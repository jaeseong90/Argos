// SPDX-License-Identifier: Apache-2.0
//
// Container images of the E2E stack, each pinned by a digest and read from the file that owns it,
// so the stack runs what an installation runs:
//   PostgreSQL   deploy/postgres/image.properties (WO-20; also read by Compose and the server's
//                ServerTestEnvironment)
//   ClickHouse   deploy/clickhouse/image.properties (WO-03; also read by test/storage/clickhouse-support)
//   server JRE   the Jib base image of the server, ServerImage.BASE_IMAGE in
//                build-logic/src/main/kotlin/io/argos/buildlogic/server/ServerImage.kt (WO-20)
// The Linux Playwright image of the visual-regression baselines (e2e:docker, docker/18 §13) is
// pinned here; its version must equal @playwright/test in package.json (images.test.ts).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { REPO_ROOT, UI_ROOT } from './paths.ts';

/** `repository:tag@sha256:<64 hex>`. */
const PINNED = /^[a-z0-9][a-z0-9._/-]*:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}$/;

/**
 * mcr.microsoft.com/playwright:v1.64.1-noble, multi-arch index digest (linux/amd64, linux/arm64),
 * resolved on 2026-11-14 with `docker buildx imagetools inspect mcr.microsoft.com/playwright:v1.64.1-noble`.
 * Update together with @playwright/test (images.test.ts fails otherwise).
 */
export const PLAYWRIGHT_IMAGE =
  'mcr.microsoft.com/playwright:v1.64.1-noble@sha256:2f05f8a734509a7b3f26a912639488ee08f58a7b292d14d62271bcff9038768f';

export interface StackImages {
  readonly postgres: string;
  readonly clickhouse: string;
  readonly serverJre: string;
}

/** The `image=` entry of a Java properties file (comments and blank lines ignored). */
export function propertiesImage(text: string, file: string): string {
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith('image=')) {
      const value = line.slice('image='.length).trim();
      if (!PINNED.test(value)) {
        throw new Error(`${file}: image is not pinned by a digest: ${value}`);
      }
      return value;
    }
  }
  throw new Error(`${file}: no image= entry`);
}

/** ServerImage.BASE_IMAGE of the Kotlin source. */
export function kotlinBaseImage(text: string, file: string): string {
  const match = /BASE_IMAGE\s*:\s*String\s*=\s*"([^"]+)"/.exec(
    text.replace(/"\s*\+?\s*\r?\n\s*"/g, ''),
  );
  const value = match?.[1];
  if (value === undefined) {
    throw new Error(`${file}: BASE_IMAGE not found`);
  }
  if (!PINNED.test(value)) {
    throw new Error(`${file}: BASE_IMAGE is not pinned by a digest: ${value}`);
  }
  return value;
}

export const POSTGRES_PROPERTIES = join(REPO_ROOT, 'deploy', 'postgres', 'image.properties');
export const CLICKHOUSE_PROPERTIES = join(REPO_ROOT, 'deploy', 'clickhouse', 'image.properties');
export const SERVER_IMAGE_SOURCE = join(
  REPO_ROOT,
  'build-logic',
  'src',
  'main',
  'kotlin',
  'io',
  'argos',
  'buildlogic',
  'server',
  'ServerImage.kt',
);

/** The images of the stack, read from the repository. */
export function stackImages(): StackImages {
  return {
    postgres: propertiesImage(readFileSync(POSTGRES_PROPERTIES, 'utf8'), POSTGRES_PROPERTIES),
    clickhouse: propertiesImage(readFileSync(CLICKHOUSE_PROPERTIES, 'utf8'), CLICKHOUSE_PROPERTIES),
    serverJre: kotlinBaseImage(readFileSync(SERVER_IMAGE_SOURCE, 'utf8'), SERVER_IMAGE_SOURCE),
  };
}

/** The exact @playwright/test version of ui/package.json. */
export function playwrightVersion(): string {
  const pkg = JSON.parse(readFileSync(join(UI_ROOT, 'package.json'), 'utf8')) as {
    devDependencies?: Record<string, string>;
  };
  const version = pkg.devDependencies?.['@playwright/test'];
  if (version === undefined || !/^\d+\.\d+\.\d+$/.test(version)) {
    throw new Error(`package.json: @playwright/test is not an exact version: ${String(version)}`);
  }
  return version;
}
