// SPDX-License-Identifier: Apache-2.0
//
// Validation of the `xlog` topic payload (docs/14 §5.2 push `data`: the point columns of §4.5,
// ADR-0010 C-167 (1)). WsManager validates the frame envelope and leaves `data` unknown; a payload
// that fails here is dropped and counted (docs/15 §10), never partly applied.
import { z } from '../../../app/zod';
import type { XLogPointColumns } from '../data/columns';

/** Most rows accepted from one 100 ms push (a guard against a runaway payload). */
export const MAX_PUSH_ROWS = 50_000;

const Id64 = z.string().regex(/^[0-9]{1,20}$/);

const XLogPushData = z
  .object({
    tx_id: z.array(Id64).max(MAX_PUSH_ROWS),
    ts: z.array(z.number()).max(MAX_PUSH_ROWS),
    elapsed_ms: z.array(z.number()).max(MAX_PUSH_ROWS),
    status: z.array(z.number().int()).max(MAX_PUSH_ROWS),
    instance_id: z.array(z.number().int()).max(MAX_PUSH_ROWS),
    service_id: z.array(Id64).max(MAX_PUSH_ROWS),
    kind: z.array(z.number().int()).max(MAX_PUSH_ROWS),
  })
  .refine(
    (data) => {
      const n = data.tx_id.length;
      return (
        data.ts.length === n &&
        data.elapsed_ms.length === n &&
        data.status.length === n &&
        data.instance_id.length === n &&
        data.service_id.length === n &&
        data.kind.length === n
      );
    },
    { message: 'columns of different lengths' },
  );

/** The point columns of a push, or null when the payload is not a valid `xlog` batch. */
export function parseXLogPush(data: unknown): XLogPointColumns | null {
  const parsed = XLogPushData.safeParse(data);
  return parsed.success ? parsed.data : null;
}
