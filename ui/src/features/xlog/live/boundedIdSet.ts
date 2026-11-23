// SPDX-License-Identifier: Apache-2.0
//
// Realtime de-duplication by tx_id (docs/15 §5, §10: "중복 제거(tx_id 기준)") with a bounded
// memory: at most `capacity` ids, the oldest forgotten first. The capacity is the XLog ring's, so
// every point that can still be in the buffer is remembered (docs/15 §14 tab heap budget).

export class BoundedIdSet {
  readonly capacity: number;
  private readonly ids = new Set<string>();
  private readonly ring: (string | undefined)[];
  private next = 0;

  constructor(capacity: number) {
    if (!Number.isInteger(capacity) || capacity < 1) {
      throw new RangeError(`BoundedIdSet capacity must be a positive integer: ${String(capacity)}`);
    }
    this.capacity = capacity;
    this.ring = new Array<string | undefined>(capacity);
  }

  get size(): number {
    return this.ids.size;
  }

  has(id: string): boolean {
    return this.ids.has(id);
  }

  /** Adds `id`; returns false when it was already known (a duplicate). */
  add(id: string): boolean {
    if (this.ids.has(id)) {
      return false;
    }
    const evicted = this.ring[this.next];
    if (evicted !== undefined) {
      this.ids.delete(evicted);
    }
    this.ring[this.next] = id;
    this.next = (this.next + 1) % this.capacity;
    this.ids.add(id);
    return true;
  }

  clear(): void {
    this.ids.clear();
    this.ring.fill(undefined);
    this.next = 0;
  }
}
