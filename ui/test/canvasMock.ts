// SPDX-License-Identifier: Apache-2.0
//
// Recording 2D context for canvas tests. jsdom has no canvas implementation (getContext returns
// null and logs "not implemented"), so tests install this recorder instead and assert on the
// drawing commands (passes, fills, shifts) rather than on pixels.
import { vi } from 'vitest';

export interface RecordedCall {
  readonly name: string;
  readonly args: readonly unknown[];
  /** fillStyle when the call was made. */
  readonly fillStyle: string;
}

export class RecordingContext {
  readonly calls: RecordedCall[] = [];
  fillStyle: string | CanvasGradient | CanvasPattern = '#000000';
  strokeStyle: string | CanvasGradient | CanvasPattern = '#000000';
  lineWidth = 1;
  font = '10px sans-serif';
  textAlign: CanvasTextAlign = 'start';
  textBaseline: CanvasTextBaseline = 'alphabetic';
  globalCompositeOperation = 'source-over';
  lastImage: ImageData | null = null;
  /** Context attributes passed to the first getContext('2d', …) call. */
  attributes: unknown = undefined;
  readonly texts: string[] = [];

  private record(name: string, args: readonly unknown[]): void {
    this.calls.push({
      name,
      args,
      fillStyle: typeof this.fillStyle === 'string' ? this.fillStyle : 'pattern',
    });
  }

  count(name: string): number {
    return this.calls.filter((call) => call.name === name).length;
  }

  named(name: string): RecordedCall[] {
    return this.calls.filter((call) => call.name === name);
  }

  reset(): void {
    this.calls.length = 0;
    this.texts.length = 0;
  }

  setTransform(...args: unknown[]): void {
    this.record('setTransform', args);
  }
  clearRect(...args: unknown[]): void {
    this.record('clearRect', args);
  }
  beginPath(): void {
    this.record('beginPath', []);
  }
  rect(...args: unknown[]): void {
    this.record('rect', args);
  }
  fill(): void {
    this.record('fill', []);
  }
  fillRect(...args: unknown[]): void {
    this.record('fillRect', args);
  }
  strokeRect(...args: unknown[]): void {
    this.record('strokeRect', args);
  }
  drawImage(...args: unknown[]): void {
    this.record('drawImage', [...args, this.globalCompositeOperation]);
  }
  moveTo(...args: unknown[]): void {
    this.record('moveTo', args);
  }
  lineTo(...args: unknown[]): void {
    this.record('lineTo', args);
  }
  stroke(): void {
    this.record('stroke', []);
  }
  arc(...args: unknown[]): void {
    this.record('arc', args);
  }
  setLineDash(...args: unknown[]): void {
    this.record('setLineDash', args);
  }
  fillText(text: string, ...args: unknown[]): void {
    this.texts.push(text);
    this.record('fillText', [text, ...args]);
  }
  measureText(text: string): TextMetrics {
    return { width: text.length * 6 } as TextMetrics;
  }
  createImageData(width: number, height: number): ImageData {
    return {
      width,
      height,
      data: new Uint8ClampedArray(width * height * 4),
      colorSpace: 'srgb',
    };
  }
  putImageData(image: ImageData, ...args: unknown[]): void {
    this.lastImage = image;
    this.record('putImageData', args);
  }
  getImageData(...args: unknown[]): ImageData {
    this.record('getImageData', args);
    return this.createImageData(1, 1);
  }
}

/**
 * Makes every canvas return its own RecordingContext from getContext('2d'). Returns the map from
 * canvas to context; restore with vi.restoreAllMocks().
 */
export function installCanvasMock(): Map<HTMLCanvasElement, RecordingContext> {
  const contexts = new Map<HTMLCanvasElement, RecordingContext>();
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(function (
    this: HTMLCanvasElement,
    _type: string,
    attributes?: unknown,
  ) {
    let context = contexts.get(this);
    if (context === undefined) {
      context = new RecordingContext();
      context.attributes = attributes;
      contexts.set(this, context);
    }
    return context as unknown as CanvasRenderingContext2D;
  });
  return contexts;
}

/** Gives every element the same bounding box (jsdom lays nothing out). */
export function mockLayout(width: number, height: number): void {
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({
    x: 0,
    y: 0,
    left: 0,
    top: 0,
    right: width,
    bottom: height,
    width,
    height,
    toJSON: () => ({}),
  });
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(width);
}
