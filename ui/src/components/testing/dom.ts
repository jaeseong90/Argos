// SPDX-License-Identifier: Apache-2.0
//
// jsdom gaps that Ant Design overlays (Tooltip, Select popups) need in component tests: jsdom has
// no ResizeObserver and no matchMedia. Test-only module: nothing in the application imports it.

class NoopResizeObserver {
  observe(): void {
    // jsdom has no layout: nothing ever resizes.
  }

  unobserve(): void {
    // nothing observed
  }

  disconnect(): void {
    // nothing observed
  }
}

/** Installs inert ResizeObserver and matchMedia implementations when jsdom lacks them. */
export function installDomStubs(): void {
  const target = globalThis as {
    ResizeObserver?: unknown;
    matchMedia?: unknown;
  };
  target.ResizeObserver ??= NoopResizeObserver;
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
}
