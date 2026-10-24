// SPDX-License-Identifier: Apache-2.0
//
// Turns the results of this performance run (one JSON line per scenario) into
// <outDir>/xlog-frame-<runId>.md and .json, copies them to xlog-frame-latest.*, and prints the
// Markdown report.
import { copyFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { readRecords, renderMarkdown } from './results';
import { perfSettings } from './settings';

export default function globalTeardown(): void {
  const settings = perfSettings(process.env);
  const runId = process.env.XLOG_PERF_RUN_ID ?? 'adhoc';
  const base = resolve(settings.outDir, `xlog-frame-${runId}`);
  const records = readRecords(`${base}.jsonl`);
  if (records.length === 0) {
    return;
  }
  const markdown = renderMarkdown(records);
  writeFileSync(`${base}.md`, markdown, 'utf8');
  writeFileSync(`${base}.json`, `${JSON.stringify(records, null, 2)}\n`, 'utf8');
  copyFileSync(`${base}.md`, resolve(settings.outDir, 'xlog-frame-latest.md'));
  copyFileSync(`${base}.json`, resolve(settings.outDir, 'xlog-frame-latest.json'));
  process.stdout.write(`\n${markdown}\nResults: ${base}.md\n`);
}
