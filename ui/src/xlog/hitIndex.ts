// SPDX-License-Identifier: Apache-2.0
//
// Hit testing on an 8×8 px grid (docs/15 §7.3). Points are bucketed by their plot coordinates with
// a counting sort into one Uint32Array (`items`) and an offset table (`offsets`, one entry per
// cell + 1), so no Map or per-cell array is allocated. Hover looks at the cursor cell and its 8
// neighbours; a drag selection walks the cells the rectangle covers and checks the data-space
// condition `t1 <= ts <= t2 && e1 <= elapsed <= e2`.
import {
  inDataRect,
  pixelRectToData,
  type DataRect,
  type PixelRect,
  type PlotScale,
  yFraction,
} from './scale';
import type { XLogBuffer } from './XLogBuffer';

export const CELL_SIZE = 8;
/** Default hover radius in CSS px; must not exceed CELL_SIZE (only neighbour cells are searched). */
export const HOVER_RADIUS = 6;

const NO_CELL = 0xffff_ffff;

export class HitIndex {
  private cols = 0;
  private rows = 0;
  private offsets = new Uint32Array(1);
  private items = new Uint32Array(0);
  private cellOf = new Uint32Array(0);
  private xs = new Float32Array(0);
  private ys = new Float32Array(0);
  /** Scratch of `queryData`. */
  private matches = new Uint32Array(0);
  private count = 0;
  private builtFor: { buffer: XLogBuffer | null; version: number; key: string } = {
    buffer: null,
    version: -1,
    key: '',
  };

  /** Number of indexed (visible) points. */
  get size(): number {
    return this.count;
  }

  /** True when the index matches this buffer state and scale. */
  isCurrent(buffer: XLogBuffer, scale: PlotScale): boolean {
    return (
      this.builtFor.buffer === buffer &&
      this.builtFor.version === buffer.version &&
      this.builtFor.key === scaleKey(scale)
    );
  }

  /** Rebuilds the index unless it is current. */
  ensure(buffer: XLogBuffer, scale: PlotScale): void {
    if (!this.isCurrent(buffer, scale)) {
      this.build(buffer, scale);
    }
  }

  build(buffer: XLogBuffer, scale: PlotScale): void {
    const capacity = buffer.capacity;
    if (this.cellOf.length < capacity) {
      this.cellOf = new Uint32Array(capacity);
      this.xs = new Float32Array(capacity);
      this.ys = new Float32Array(capacity);
      this.items = new Uint32Array(capacity);
    }
    const width = scale.width;
    const height = scale.height;
    const cols = Math.max(1, Math.ceil(width / CELL_SIZE));
    const rows = Math.max(1, Math.ceil(height / CELL_SIZE));
    const cells = cols * rows;
    if (this.offsets.length < cells + 1) {
      this.offsets = new Uint32Array(cells + 1);
    } else {
      this.offsets.fill(0, 0, cells + 1);
    }
    this.cols = cols;
    this.rows = rows;
    const offsets = this.offsets;
    const { ts, elapsed } = buffer;
    const from = scale.from;
    const to = scale.to;
    const pxPerMs = width / (to - from);

    // Pass 1: coordinates, cell and per-cell counts (shifted by one for the prefix sum).
    let count = 0;
    for (let logical = 0; logical < buffer.size; logical += 1) {
      const p = buffer.physical(logical);
      const t = ts[p] ?? 0;
      if (t < from || t > to || width <= 0 || height <= 0) {
        this.cellOf[p] = NO_CELL;
        continue;
      }
      const x = (t - from) * pxPerMs;
      const y = height - yFraction(scale, elapsed[p] ?? 0) * height;
      this.xs[p] = x;
      this.ys[p] = y;
      const col = Math.min(cols - 1, Math.floor(x / CELL_SIZE));
      const row = Math.min(rows - 1, Math.floor(y / CELL_SIZE));
      const cell = row * cols + col;
      this.cellOf[p] = cell;
      offsets[cell + 1] = (offsets[cell + 1] ?? 0) + 1;
      count += 1;
    }
    for (let c = 0; c < cells; c += 1) {
      offsets[c + 1] = (offsets[c + 1] ?? 0) + (offsets[c] ?? 0);
    }

    // Pass 2: place point indices; `cursor` reuses the cell's start offset.
    const cursor = offsets.slice(0, cells);
    for (let logical = 0; logical < buffer.size; logical += 1) {
      const p = buffer.physical(logical);
      const cell = this.cellOf[p] ?? NO_CELL;
      if (cell === NO_CELL) {
        continue;
      }
      const at = cursor[cell] ?? 0;
      this.items[at] = p;
      cursor[cell] = at + 1;
    }
    this.count = count;
    this.builtFor = { buffer, version: buffer.version, key: scaleKey(scale) };
  }

  /** Plot coordinates of an indexed point. */
  position(index: number): { x: number; y: number } {
    return { x: this.xs[index] ?? 0, y: this.ys[index] ?? 0 };
  }

  /**
   * Nearest point to (x, y) within `radius` px, searching the cursor cell and its 8 neighbours.
   *
   * @returns the buffer's physical index, or -1
   */
  nearest(x: number, y: number, radius: number = HOVER_RADIUS): number {
    if (this.count === 0) {
      return -1;
    }
    const r = Math.min(radius, CELL_SIZE);
    const col = Math.floor(x / CELL_SIZE);
    const row = Math.floor(y / CELL_SIZE);
    let best = -1;
    let bestDistance = r * r;
    for (let dr = -1; dr <= 1; dr += 1) {
      const rr = row + dr;
      if (rr < 0 || rr >= this.rows) {
        continue;
      }
      for (let dc = -1; dc <= 1; dc += 1) {
        const cc = col + dc;
        if (cc < 0 || cc >= this.cols) {
          continue;
        }
        const cell = rr * this.cols + cc;
        const end = this.offsets[cell + 1] ?? 0;
        for (let i = this.offsets[cell] ?? 0; i < end; i += 1) {
          const p = this.items[i] ?? 0;
          const dx = (this.xs[p] ?? 0) - x;
          const dy = (this.ys[p] ?? 0) - y;
          const d = dx * dx + dy * dy;
          // Ties go to the later point in the item order (drawn later, so on top).
          if (d <= bestDistance) {
            bestDistance = d;
            best = p;
          }
        }
      }
    }
    return best;
  }

  /**
   * Points inside a drag rectangle (plot px). The rectangle is converted to a data-space selection
   * and every point of the covered cells (plus one cell of margin for rounding) is checked
   * against it.
   */
  queryRect(buffer: XLogBuffer, scale: PlotScale, rect: PixelRect): Uint32Array {
    return this.queryData(buffer, rect, pixelRectToData(scale, rect));
  }

  /**
   * Like `queryRect` with an explicit data-space selection. Matches are written into a reused
   * scratch array (capacity = indexed points, grown only when the index grows) and returned as a
   * copy of exactly the matches, so a 100,000-point selection does not grow a JS array.
   */
  queryData(buffer: XLogBuffer, rect: PixelRect, data: DataRect): Uint32Array {
    if (this.count === 0) {
      return new Uint32Array(0);
    }
    if (this.matches.length < this.count) {
      this.matches = new Uint32Array(this.count);
    }
    const matches = this.matches;
    let found = 0;
    const c1 = Math.max(0, Math.floor(rect.x1 / CELL_SIZE) - 1);
    const c2 = Math.min(this.cols - 1, Math.floor(rect.x2 / CELL_SIZE) + 1);
    const r1 = Math.max(0, Math.floor(rect.y1 / CELL_SIZE) - 1);
    const r2 = Math.min(this.rows - 1, Math.floor(rect.y2 / CELL_SIZE) + 1);
    const { ts, elapsed } = buffer;
    for (let row = r1; row <= r2; row += 1) {
      const base = row * this.cols;
      const start = this.offsets[base + c1] ?? 0;
      const end = this.offsets[base + c2 + 1] ?? 0;
      // Cells of one row are contiguous in `items`.
      for (let i = start; i < end; i += 1) {
        const p = this.items[i] ?? 0;
        if (inDataRect(data, ts[p] ?? 0, elapsed[p] ?? 0)) {
          // At most `count` matches: every indexed point is in exactly one cell.
          matches[found] = p;
          found += 1;
        }
      }
    }
    return matches.slice(0, found);
  }
}

function scaleKey(scale: PlotScale): string {
  return [scale.from, scale.to, scale.width, scale.height, scale.yMax, scale.yType].join('|');
}
