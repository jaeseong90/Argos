// SPDX-License-Identifier: Apache-2.0
//
// The E2E stack runs the pinned images of the repository (e2e/stack/images.ts): these tests fail
// when a source file loses its pin or when the Playwright image no longer matches @playwright/test.
import { readFileSync } from 'node:fs';

import { describe, expect, it } from 'vitest';

import {
  CLICKHOUSE_PROPERTIES,
  kotlinBaseImage,
  PLAYWRIGHT_IMAGE,
  playwrightVersion,
  POSTGRES_PROPERTIES,
  propertiesImage,
  SERVER_IMAGE_SOURCE,
  stackImages,
} from './images.ts';

describe('E2E stack images', () => {
  it('reads the digest-pinned images of deploy/ and the Jib base of ServerImage.kt', () => {
    const images = stackImages();
    expect(images.postgres).toMatch(/^postgres:16\.\d+@sha256:[0-9a-f]{64}$/);
    expect(images.clickhouse).toMatch(/^clickhouse\/clickhouse-server:[\d.]+@sha256:[0-9a-f]{64}$/);
    expect(images.serverJre).toMatch(/^eclipse-temurin:21-jre@sha256:[0-9a-f]{64}$/);
    // The same values the owners read (Compose, ServerTestEnvironment, Jib).
    expect(readFileSync(POSTGRES_PROPERTIES, 'utf8')).toContain(`image=${images.postgres}`);
    expect(readFileSync(CLICKHOUSE_PROPERTIES, 'utf8')).toContain(`image=${images.clickhouse}`);
    expect(readFileSync(SERVER_IMAGE_SOURCE, 'utf8')).toContain(`"${images.serverJre}"`);
  });

  it('pins the Linux Playwright image of the version in package.json', () => {
    expect(PLAYWRIGHT_IMAGE).toMatch(
      new RegExp(
        `^mcr\\.microsoft\\.com/playwright:v${playwrightVersion().replaceAll('.', '\\.')}-noble@sha256:[0-9a-f]{64}$`,
      ),
    );
  });

  it('refuses an image without a digest', () => {
    expect(() => propertiesImage('image=postgres:16.15\n', 'x')).toThrow(/not pinned/);
    expect(() => propertiesImage('# nothing\n', 'x')).toThrow(/no image=/);
    expect(() =>
      kotlinBaseImage('const val BASE_IMAGE: String = "eclipse-temurin:21-jre"', 'y'),
    ).toThrow(/not pinned/);
    expect(() => kotlinBaseImage('object X', 'y')).toThrow(/not found/);
  });

  it('reads a base image split over two lines', () => {
    const digest = 'a'.repeat(64);
    expect(
      kotlinBaseImage(
        `const val BASE_IMAGE: String =\n    "eclipse-temurin:21-jre@sha256:${digest}"`,
        'z',
      ),
    ).toBe(`eclipse-temurin:21-jre@sha256:${digest}`);
  });
});
