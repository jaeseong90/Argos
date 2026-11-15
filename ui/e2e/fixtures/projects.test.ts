// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { projectSelection, runsPreview, SELECTION_ENV, selectedProjects } from './projects';

describe('Playwright project selection', () => {
  it('reads --project in both spellings, repeated', () => {
    expect(selectedProjects(['test', '--project', 'stack', '--project=preview'])).toEqual([
      'stack',
      'preview',
    ]);
    expect(selectedProjects(['test', '--shard=1/4'])).toBeNull();
    expect(selectedProjects(['test', '--project'])).toBeNull();
    // Variadic like Playwright's option: `--project stack screenshots` names two projects.
    expect(selectedProjects(['test', '--project', 'preview', 'stack', '--shard=1/4'])).toEqual([
      'preview',
      'stack',
    ]);
  });

  it('serves dist/ for preview runs and for runs without a selection only', () => {
    expect(runsPreview(['test'])).toBe(true);
    expect(runsPreview(['test', '--project', 'preview', '--project', 'stack'])).toBe(true);
    expect(runsPreview(['test', '--project', 'stack'])).toBe(false);
    expect(runsPreview(['test', '--project=initial-load'])).toBe(false);
  });

  it('hands the runner selection to the workers through the environment', () => {
    const env: Record<string, string | undefined> = {};
    expect(projectSelection(['test', '--project', 'initial-load'], env)).toEqual(['initial-load']);
    expect(env[SELECTION_ENV]).toBe('["initial-load"]');
    // A worker: its own argv names no project, the inherited environment does.
    expect(projectSelection(['worker'], env)).toEqual(['initial-load']);
    const none: Record<string, string | undefined> = {};
    expect(projectSelection(['test'], none)).toBeNull();
    expect(projectSelection(['worker', '--project', 'stack'], none)).toBeNull();
  });
});
