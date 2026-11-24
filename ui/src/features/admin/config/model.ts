// SPDX-License-Identifier: Apache-2.0
//
// Pure parts of the configuration screen (docs/15 §9.10 `/admin/config`, 05 §2.11-5, F-S29): the
// rows of the current configuration (every remotable key of the reference instance's effective
// configuration, its value and source, which instances drift from the server-held value, whether
// the key may be changed remotely on every instance), the draft of a change, the checks before a
// preview (reason required and at most 1 KiB, at least one key, targets) and the instance names.
import {
  CONFIG_KEYS_MAX,
  CONFIG_REASON_MAX_BYTES,
  CONFIG_TARGETS_MAX,
  KILL_SWITCH_KEY,
  type AppConfig,
  type ConfigTargetChoice,
} from '../../../api/admin/config';
import type { EffectiveConfigEntry } from '../../../api/instances';
import type { Untrusted } from '../../../api/untrusted';
import { utf8ByteLength } from '../../../utils/utf8';

/** Why a key cannot be changed here. */
export type LockReason =
  /** Some instance's remote-config.allow does not name it: change it in argos-agent.yml. */
  | 'local'
  /** The kill switch key: the kill switch section changes it. */
  | 'killSwitch';

export interface ConfigRow {
  readonly key: string;
  /** The value the reference instance runs from the server (masked), null when local or unknown. */
  readonly value: Untrusted | null;
  /** `server` when the instance runs the server-held value, null for a local value. */
  readonly source: string | null;
  /** The server-held value (masked), null when the server holds none. */
  readonly held: Untrusted | null;
  /** null: editable; otherwise why the key is locked. */
  readonly lock: LockReason | null;
  /** Instances whose state of the held value is not applied (not_allowed, rejected, pending). */
  readonly drift: readonly { readonly instanceId: number; readonly reason: string }[];
}

/**
 * The rows of the current configuration: the reference instance's effective configuration (every
 * remotable key, docs/14 §4.10) or, without one, the remotely allowed and the held keys. A key is
 * editable when every instance allows it (`remote_allowed_keys`), except the kill switch key.
 */
export function configRows(
  config: AppConfig,
  effective: readonly EffectiveConfigEntry[] | null,
): ConfigRow[] {
  const allowed = new Set(config.remote_allowed_keys);
  const current: Readonly<Record<string, Untrusted>> = config.current;
  const held = (key: string): Untrusted | null =>
    Object.prototype.hasOwnProperty.call(current, key) ? (current[key] ?? null) : null;
  const drift = (key: string) =>
    config.drift
      .filter((entry) => entry.key === key)
      .map((entry) => ({ instanceId: entry.instance_id, reason: entry.reason }));
  const lock = (key: string): LockReason | null =>
    key === KILL_SWITCH_KEY ? 'killSwitch' : allowed.has(key) ? null : 'local';
  if (effective !== null && effective.length > 0) {
    return effective.map((entry) => ({
      key: entry.key,
      value: entry.value,
      source: entry.source,
      held: entry.server_value ?? held(entry.key),
      lock: lock(entry.key),
      drift: drift(entry.key),
    }));
  }
  const keys = [...new Set([...config.remote_allowed_keys, ...Object.keys(current)])].sort();
  return keys.map((key) => ({
    key,
    value: null,
    source: null,
    held: held(key),
    lock: lock(key),
    drift: drift(key),
  }));
}

/** Instances whose configuration hash differs from the application's majority (05 §2.11-2). */
export function hashDriftInstances(config: AppConfig): number[] {
  return config.drift.filter((entry) => entry.key === null).map((entry) => entry.instance_id);
}

/** The reference instance of the effective configuration: the first online one, else the first. */
export function referenceInstance(config: AppConfig): number | null {
  const online = config.remote_allowed_by_instance.find((instance) => instance.online);
  return (online ?? config.remote_allowed_by_instance[0])?.instance_id ?? null;
}

/** Instance id → name (entered strings: render through SafeText). */
export function instanceNames(config: AppConfig | undefined): Map<number, Untrusted> {
  return new Map(
    (config?.remote_allowed_by_instance ?? []).map(
      (instance) => [instance.instance_id, instance.name] as const,
    ),
  );
}

/** Option text of an instance (entered string; React sets it as text, never markup). */
export function instanceOptionText(instance: { readonly name: Untrusted }, suffix = ''): string {
  return `${String(instance.name)}${suffix}`;
}

/** A draft change: key → typed value ('' = no change), or null = remove the server-held value. */
export type ConfigDraft = Readonly<Record<string, string | null>>;

/** The `changes` of a draft: typed values trimmed, untouched keys left out. */
export function draftChanges(draft: ConfigDraft): Record<string, string | null> {
  const out: Record<string, string | null> = {};
  for (const [key, value] of Object.entries(draft)) {
    if (value === null) {
      out[key] = null;
    } else if (value.trim() !== '') {
      out[key] = value.trim();
    }
  }
  return out;
}

export type ReasonProblem = 'required' | 'tooLong';

/** The reason is required and at most 1 KiB of UTF-8 (docs/14 §4.14). */
export function reasonProblem(reason: string): ReasonProblem | null {
  if (reason.trim() === '') {
    return 'required';
  }
  return utf8ByteLength(reason) > CONFIG_REASON_MAX_BYTES ? 'tooLong' : null;
}

export type PreviewProblem =
  ReasonProblem | 'noChanges' | 'tooManyKeys' | 'noTargets' | 'tooManyTargets';

/** The first reason a preview cannot be requested, or null. */
export function previewProblem(
  draft: ConfigDraft,
  reason: string,
  targets: ConfigTargetChoice,
): PreviewProblem | null {
  const changes = Object.keys(draftChanges(draft));
  if (changes.length === 0) {
    return 'noChanges';
  }
  if (changes.length > CONFIG_KEYS_MAX) {
    return 'tooManyKeys';
  }
  if (!targets.all) {
    if (targets.instanceIds.length === 0) {
      return 'noTargets';
    }
    if (targets.instanceIds.length > CONFIG_TARGETS_MAX) {
      return 'tooManyTargets';
    }
  }
  return reasonProblem(reason);
}

/** True when the kill switch is on for the application (the held `enabled` is false). */
export function killSwitchOn(config: AppConfig | undefined): boolean {
  const current: Readonly<Record<string, string>> | undefined = config?.current;
  const value =
    current !== undefined && Object.prototype.hasOwnProperty.call(current, KILL_SWITCH_KEY)
      ? current[KILL_SWITCH_KEY]
      : undefined;
  return value !== undefined && value.trim().toLowerCase() === 'false';
}
