// SPDX-License-Identifier: Apache-2.0
//
// TanStack Query defaults (docs/15 §2.1, §14): server state cache with gcTime 5 minutes (memory
// budget), no automatic retry of client errors or rate limits (C-176: never a retry loop on 429),
// one retry of network errors and 502/503.
import { QueryClient } from '@tanstack/react-query';

import { isRetryable } from './errors';

export const GC_TIME_MS = 5 * 60_000;

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        gcTime: GC_TIME_MS,
        retry: (failureCount, error) => failureCount < 1 && isRetryable(error),
        refetchOnWindowFocus: false,
      },
      mutations: {
        retry: false,
      },
    },
  });
}
