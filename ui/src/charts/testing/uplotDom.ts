// SPDX-License-Identifier: Apache-2.0
//
// jsdom gaps that uPlot needs in component tests. uPlot reads matchMedia when it is imported (pixel
// ratio), builds paths with Path2D and draws on a 2D context; jsdom has none of these. Install
// before uPlot is imported:
//
//   await vi.hoisted(async () => {
//     (await import('./testing/uplotDom')).installUplotDom();
//   });
//
// The 2D context records the calls tests assert on (dash patterns, strokes) and ignores the rest.
// Test-only module: nothing in the application imports it.

/** One recorded canvas call. */
export interface CanvasCall {
  readonly name: string;
  readonly args: readonly unknown[];
  readonly strokeStyle: unknown;
  readonly globalAlpha: unknown;
}

/** Calls recorded on every canvas context since the last reset. */
export const canvasCalls: CanvasCall[] = [];

class InertPath2D {
  moveTo(): void {
    // paths are not rasterised in jsdom
  }
  lineTo(): void {
    // paths are not rasterised in jsdom
  }
  rect(): void {
    // paths are not rasterised in jsdom
  }
  arc(): void {
    // paths are not rasterised in jsdom
  }
  closePath(): void {
    // paths are not rasterised in jsdom
  }
  bezierCurveTo(): void {
    // paths are not rasterised in jsdom
  }
  quadraticCurveTo(): void {
    // paths are not rasterised in jsdom
  }
  addPath(): void {
    // paths are not rasterised in jsdom
  }
}

function createContext(canvas: HTMLCanvasElement): CanvasRenderingContext2D {
  const state: Record<string | symbol, unknown> = {
    canvas,
    strokeStyle: '#000000',
    fillStyle: '#000000',
    globalAlpha: 1,
    lineWidth: 1,
    font: '10px sans-serif',
  };
  const methods = new Map<string | symbol, (...args: unknown[]) => unknown>();
  const proxy = new Proxy(state, {
    get(target, property) {
      if (property in target) {
        return target[property];
      }
      if (property === 'measureText') {
        return (text: string) => ({ width: text.length * 6 });
      }
      let method = methods.get(property);
      if (method === undefined) {
        method = (...args: unknown[]) => {
          canvasCalls.push({
            name: String(property),
            args,
            strokeStyle: target.strokeStyle,
            globalAlpha: target.globalAlpha,
          });
          return undefined;
        };
        methods.set(property, method);
      }
      return method;
    },
    set(target, property, value) {
      target[property] = value;
      return true;
    },
  });
  return proxy as unknown as CanvasRenderingContext2D;
}

/** Installs matchMedia, Path2D and a recording 2D canvas context (idempotent). */
export function installUplotDom(): void {
  const target = globalThis as {
    matchMedia?: unknown;
    Path2D?: unknown;
    ResizeObserver?: unknown;
  };
  target.matchMedia ??= (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  });
  target.Path2D ??= InertPath2D;
  const contexts = new WeakMap<HTMLCanvasElement, CanvasRenderingContext2D>();
  Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', {
    configurable: true,
    value: function getContext(this: HTMLCanvasElement) {
      let context = contexts.get(this);
      if (context === undefined) {
        context = createContext(this);
        contexts.set(this, context);
      }
      return context;
    },
  });
}
