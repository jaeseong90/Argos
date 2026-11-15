// SPDX-License-Identifier: Apache-2.0
//
// The shell's own realtime subscription (docs/15 §3.4, §5, §10, DSN-15-10). While a live screen is
// open in live mode (or in kiosk mode) the shell subscribes to the screen's periodic topic: the
// application counter (`counter`, every 5 s, docs/14 §5.3) on application screens and `overview`
// (every 5 s) on the overview. These frames keep `lastMessageAt` fresh, so the stale badge ("no
// updates for N s"), the kiosk badge ("updated N s ago") and the polling fallback work on every
// live screen, also before its work order exists. A screen that subscribes to the same topic and
// parameters shares the one server subscription (reference counting in WsManager); its handler
// gets the data, the shell's handler ignores it.
import type { Topic, TopicParams } from '../realtime/frames';
import { useTopic } from '../realtime/hooks';

import type { ScreenDef } from './screens';

export interface LiveSubscription {
  readonly topic: Topic;
  readonly params: TopicParams;
}

export interface LiveInput {
  readonly screen: ScreenDef | null;
  readonly appId: number | null;
  readonly live: boolean;
  readonly kiosk: boolean;
  readonly env?: string | undefined;
}

/** The subscription the shell keeps for the current screen, or null for none. */
export function shellLiveSubscription(input: LiveInput): LiveSubscription | null {
  const { screen, appId, live, kiosk, env } = input;
  if (screen?.live !== true || !(live || kiosk)) {
    return null;
  }
  if (screen.scope === 'app') {
    return appId === null ? null : { topic: 'counter', params: { app: appId } };
  }
  return { topic: 'overview', params: env === undefined ? {} : { env } };
}

function ignore(): void {
  // The shell only needs the freshness of the topic, which the connection store records.
}

/** Keeps the shell's subscription while `subscription` is not null. */
export function useShellLiveSubscription(subscription: LiveSubscription | null): void {
  useTopic(subscription?.topic ?? 'counter', subscription?.params ?? null, { onData: ignore });
}
