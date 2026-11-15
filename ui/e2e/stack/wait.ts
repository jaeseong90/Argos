// SPDX-License-Identifier: Apache-2.0
//
// Polling and port helpers of the E2E stack.
import { createServer } from 'node:net';
import { setTimeout as sleep } from 'node:timers/promises';

export { sleep };

/** A free TCP port on 127.0.0.1 (bound, read and released; the caller binds it right away). */
export function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      if (address === null || typeof address === 'string') {
        server.close();
        reject(new Error('no port'));
        return;
      }
      const { port } = address;
      server.close(() => {
        resolve(port);
      });
    });
  });
}

export interface WaitOptions {
  readonly timeoutMs: number;
  readonly intervalMs?: number;
  /** Shown in the timeout error. */
  readonly what: string;
  /** Called on every failed attempt with the reason (progress output). */
  readonly onRetry?: (reason: string) => void;
}

/**
 * Calls `probe` until it returns a value other than undefined, or throws when the time is up. A
 * probe that throws counts as "not yet" (its message is the reason shown at the end); a probe that
 * throws a `FatalWait` stops the wait at once.
 */
export async function waitFor<T>(
  probe: () => Promise<T | undefined>,
  options: WaitOptions,
): Promise<T> {
  const deadline = Date.now() + options.timeoutMs;
  let reason: string;
  for (;;) {
    try {
      const value = await probe();
      if (value !== undefined) {
        return value;
      }
      reason = 'not ready';
    } catch (error) {
      if (error instanceof FatalWait) {
        throw error;
      }
      reason = error instanceof Error ? error.message : String(error);
    }
    if (Date.now() >= deadline) {
      throw new Error(
        `Timed out after ${String(options.timeoutMs / 1000)} s waiting for ${options.what}: ${reason}`,
      );
    }
    options.onRetry?.(reason);
    await sleep(options.intervalMs ?? 1000);
  }
}

/** Ends a waitFor at once (e.g. the container exited). */
export class FatalWait extends Error {}

/** GET with a timeout; resolves with status and body text. */
export async function httpGet(
  url: string,
  headers: Readonly<Record<string, string>> = {},
  timeoutMs = 5000,
): Promise<{ status: number; text: string }> {
  const response = await fetch(url, { headers, signal: AbortSignal.timeout(timeoutMs) });
  return { status: response.status, text: await response.text() };
}
