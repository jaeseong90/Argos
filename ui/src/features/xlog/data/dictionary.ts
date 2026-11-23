// SPDX-License-Identifier: Apache-2.0
//
// Local dictionary of the XLog screen (docs/15 §7.4, docs/14 §4.5 `data.dictionary`): the server
// sends service keys (unsigned 64-bit decimal strings, ADR-0010 C-175) and instance ids, the canvas
// columns hold small local indices (XLogBuffer.service Uint32, .instance Uint16). This class maps
// keys to indices and back, and resolves names for the tooltip, legend, summary and list.
//
// Lifecycle (docs/15 §7.5): a REST load starts a new dictionary (a new object, so the canvas ranks
// its colours again); realtime pushes extend the current one in place (`dict` of docs/14 §5.2), so
// the colour ranking and every index already in the buffer stay valid. Names are collected
// strings (Untrusted): render them as text only (SEC-60).
import { untrusted, type Untrusted } from '../../../api/untrusted';
import type { XLogDictionary } from '../../../xlog/XLogBuffer';

/** Largest local index of the instance column (Uint16) and an upper bound for services. */
export const MAX_INSTANCES = 0xffff;
export const MAX_SERVICES = 1_000_000;

export class LocalDictionary implements XLogDictionary {
  /** The XLog points carry no error class (ADR-0010 C-202): server summary only. */
  readonly errorClasses = null;
  /** Incremented when an entry is added or named. */
  version = 0;

  /** Names by local index; undefined when the server sent no name (shown as "Unknown"). */
  private readonly serviceNames: (Untrusted | undefined)[] = [];
  private readonly instanceNames: (Untrusted | undefined)[] = [];
  private readonly serviceKeys: string[] = [];
  private readonly serviceByKey = new Map<string, number>();
  private readonly instanceIdList: number[] = [];
  private readonly instanceById = new Map<number, number>();

  /**
   * Service names by local index (XLogDictionary). A missing name reads as undefined, which every
   * reader turns into "Unknown (#index)" (names[i] ?? null); the same array object for the life of
   * the dictionary.
   */
  get services(): readonly string[] {
    return this.serviceNames as unknown as readonly string[];
  }

  get instances(): readonly string[] {
    return this.instanceNames as unknown as readonly string[];
  }

  /** Service key (decimal string, C-175) of each local index (summary SummaryDictionary). */
  get serviceIds(): readonly string[] {
    return this.serviceKeys;
  }

  /** Instance id of each local index (summary SummaryDictionary). */
  get instanceIds(): readonly number[] {
    return this.instanceIdList;
  }

  /** Local index of a service key, added when new; -1 when the dictionary is full. */
  serviceIndex(key: string): number {
    const known = this.serviceByKey.get(key);
    if (known !== undefined) {
      return known;
    }
    if (this.serviceKeys.length >= MAX_SERVICES) {
      return -1;
    }
    const index = this.serviceKeys.length;
    this.serviceKeys.push(key);
    this.serviceByKey.set(key, index);
    this.serviceNames.push(undefined);
    this.version += 1;
    return index;
  }

  /** Local index of an instance id, added when new; -1 when the Uint16 column is full. */
  instanceIndex(id: number): number {
    const known = this.instanceById.get(id);
    if (known !== undefined) {
      return known;
    }
    if (this.instanceIdList.length >= MAX_INSTANCES) {
      return -1;
    }
    const index = this.instanceIdList.length;
    this.instanceIdList.push(id);
    this.instanceById.set(id, index);
    this.instanceNames.push(undefined);
    this.version += 1;
    return index;
  }

  /** Records names from a `{key: name}` map (docs/14 §4.5 dictionary, §5.2 push `dict`). */
  nameServices(names: Readonly<Record<string, string>> | undefined): void {
    if (names === undefined) {
      return;
    }
    for (const [key, name] of Object.entries(names)) {
      const index = this.serviceIndex(key);
      if (index >= 0 && this.serviceNames[index] !== name) {
        this.serviceNames[index] = untrusted(name);
        this.version += 1;
      }
    }
  }

  nameInstances(names: Readonly<Record<string, string>> | undefined): void {
    if (names === undefined) {
      return;
    }
    for (const [key, name] of Object.entries(names)) {
      if (!/^[1-9]\d{0,9}$/.test(key)) {
        continue;
      }
      const index = this.instanceIndex(Number(key));
      if (index >= 0 && this.instanceNames[index] !== name) {
        this.instanceNames[index] = untrusted(name);
        this.version += 1;
      }
    }
  }

  /** Service key (decimal string) of a local index, for filters and links. */
  serviceKey(index: number): string | undefined {
    return this.serviceKeys[index];
  }

  /** Instance id of a local index. */
  instanceId(index: number): number | undefined {
    return this.instanceIdList[index];
  }

  serviceName(index: number): Untrusted | null {
    return this.serviceNames[index] ?? null;
  }

  instanceName(index: number): Untrusted | null {
    return this.instanceNames[index] ?? null;
  }

  /** Local index of a known service key, or -1 (no entry is added). */
  findService(key: string): number {
    return this.serviceByKey.get(key) ?? -1;
  }

  /** Local index of a known instance id, or -1 (no entry is added). */
  findInstance(id: number): number {
    return this.instanceById.get(id) ?? -1;
  }

  get serviceCount(): number {
    return this.serviceKeys.length;
  }

  get instanceCount(): number {
    return this.instanceIdList.length;
  }
}
