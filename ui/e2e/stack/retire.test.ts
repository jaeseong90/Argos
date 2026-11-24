// SPDX-License-Identifier: Apache-2.0
//
// Retiring the instances a spec started (ADR-0010 C-241 (7), e2e/stack/retire.ts): an archive
// answer of 204 or 404 ends, 409 (the session is still open) asks again until the timeout, any
// other status fails at once; every instance is archived once.
import { describe, expect, it } from 'vitest';

import type { ApiResponse } from './api';
import { archiveAnswer, type ArchiveApi, retireInstances } from './retire';

/** A stub API answering each archive request from a per-instance list of statuses. */
function stubApi(statuses: Record<number, number[]>): { api: ArchiveApi; calls: string[] } {
  const calls: string[] = [];
  const api: ArchiveApi = {
    request<T = unknown>(method: string, path: string): Promise<ApiResponse<T>> {
      calls.push(`${method} ${path}`);
      const id = Number(/\/instances\/(\d+)\/archive$/.exec(path)?.[1]);
      const queue = statuses[id] ?? [];
      const status = queue.length > 1 ? (queue.shift() ?? 500) : (queue[0] ?? 500);
      return Promise.resolve({ status, headers: new Headers(), body: null, text: '' });
    },
  };
  return { api, calls };
}

describe('retireInstances', () => {
  it('reads the archive answers: 204 archived, 404 gone, 409 retry, others an error', () => {
    expect(archiveAnswer(204)).toBe('archived');
    expect(archiveAnswer(404)).toBe('gone');
    expect(archiveAnswer(409)).toBe('retry');
    expect(archiveAnswer(403)).toBe('error');
    expect(archiveAnswer(500)).toBe('error');
  });

  it('waits while the session is still open, then archives; a deleted instance is gone', async () => {
    const { api, calls } = stubApi({ 7: [409, 409, 204], 8: [404] });
    const outcomes = await retireInstances(api, [7, 8, 7], { retryMs: 1, timeoutMs: 5000 });
    expect([...outcomes]).toEqual([
      [7, 'archived'],
      [8, 'gone'],
    ]);
    expect(calls).toEqual([
      'POST /instances/7/archive',
      'POST /instances/7/archive',
      'POST /instances/7/archive',
      'POST /instances/8/archive',
    ]);
  });

  it('fails when an instance stays connected past the timeout or the answer is unexpected', async () => {
    await expect(
      retireInstances(stubApi({ 3: [409] }).api, [3], { retryMs: 1, timeoutMs: 20 }),
    ).rejects.toThrow('HTTP 409');
    await expect(retireInstances(stubApi({ 4: [403] }).api, [4], { retryMs: 1 })).rejects.toThrow(
      'HTTP 403',
    );
  });
});
