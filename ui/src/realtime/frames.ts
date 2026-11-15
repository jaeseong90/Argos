// SPDX-License-Identifier: Apache-2.0
//
// WebSocket frames (docs/14 §5.2, ADR-0010 C-167 (1): docs/14 §5 is normative) and topic names
// (docs/14 §5.3). Server frames are validated with zod; a frame that fails is dropped and counted
// (docs/15 §10). The `data` of a push stays `unknown` values: each screen validates its topic's
// payload with its own schema and marks collected strings with untrusted() (SEC-60).
import { z } from '../app/zod';

/** Topics of docs/14 §5.3. */
export const TOPICS = ['xlog', 'counter', 'active', 'alert', 'overview', 'admin'] as const;
export type Topic = (typeof TOPICS)[number];

/** Subscription parameters (docs/14 §5.2): app id, env, kind, filter arrays. */
export type TopicParams = Readonly<
  Record<string, string | number | boolean | readonly (string | number)[] | undefined>
>;

/** Largest client message the server accepts (docs/14 §5.1). */
export const CLIENT_MESSAGE_MAX_BYTES = 4096;
/** Subscriptions per connection (docs/14 §5.1, SEC-44). */
export const MAX_SUBSCRIPTIONS = 20;

const Id = z.string().min(1).max(64);
const IsoInstant = z.string().max(40);

export const AckFrame = z.object({
  op: z.literal('ack'),
  id: Id,
  topic: z.enum(TOPICS),
  since: IsoInstant.optional(),
});

export const PushFrame = z.object({
  op: z.literal('push'),
  id: Id,
  topic: z.enum(TOPICS),
  t: IsoInstant,
  data: z.record(z.string(), z.unknown()),
  dict: z.record(z.string(), z.record(z.string(), z.string())).optional(),
  dropped: z.number().int().nonnegative().optional(),
});

export const ErrorFrame = z.object({
  op: z.literal('error'),
  id: Id.optional(),
  code: z.string().min(1).max(64),
  message: z.string().max(1024).optional(),
});

export const PongFrame = z.object({
  op: z.literal('pong'),
  t: z.number(),
  server_t: IsoInstant.optional(),
});

export const NoticeFrame = z.object({
  op: z.literal('notice'),
  code: z.string().min(1).max(64),
  topic: z.enum(TOPICS).optional(),
  dropped: z.number().int().nonnegative().optional(),
});

/** Session expired or revoked: the server closes after it (ADR-0010 C-167 (2), docs/16 §7.7). */
export const ReauthFrame = z.object({ op: z.literal('reauth') });

export const ServerFrame = z.discriminatedUnion('op', [
  AckFrame,
  PushFrame,
  ErrorFrame,
  PongFrame,
  NoticeFrame,
  ReauthFrame,
]);
export type ServerFrame = z.infer<typeof ServerFrame>;
export type PushFrame = z.infer<typeof PushFrame>;
export type ErrorFrame = z.infer<typeof ErrorFrame>;

/** Parses a server message; null for anything that is not a valid frame. */
export function parseServerFrame(message: unknown): ServerFrame | null {
  if (typeof message !== 'string') {
    return null;
  }
  let json: unknown;
  try {
    json = JSON.parse(message);
  } catch {
    return null;
  }
  const parsed = ServerFrame.safeParse(json);
  return parsed.success ? parsed.data : null;
}

export type ClientFrame =
  | {
      readonly op: 'subscribe';
      readonly id: string;
      readonly topic: Topic;
      readonly params: TopicParams;
    }
  | { readonly op: 'unsubscribe'; readonly id: string }
  | { readonly op: 'ping'; readonly t: number };

/** Serialises a client frame; null when it would exceed the 4 KB message limit. */
export function encodeClientFrame(frame: ClientFrame): string | null {
  const text = JSON.stringify(frame);
  return new TextEncoder().encode(text).length <= CLIENT_MESSAGE_MAX_BYTES ? text : null;
}

/** Close codes (docs/14 §5.3). */
export const CLOSE_UNAUTHENTICATED = 4401;
export const CLOSE_ORIGIN = 4403;
export const CLOSE_TIMEOUT = 4408;
export const CLOSE_TOO_MANY = 4429;
/** Client-side close after a missing pong (application range 4000-4999). */
export const CLOSE_NO_PONG = 4000;
/** Client-side close on logout or when idle. */
export const CLOSE_NORMAL = 1000;
