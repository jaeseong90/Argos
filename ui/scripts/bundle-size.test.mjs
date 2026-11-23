// SPDX-License-Identifier: Apache-2.0
//
// Bundle size report (docs/15 §14): the initial path, the totals and the warning wording on a fixed
// fixture graph, and the fail-closed exits when the graph cannot be read or does not match dist/.
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

import { afterEach, describe, expect, it } from 'vitest';

import {
  BUDGET,
  formatReport,
  graphMismatches,
  initialPath,
  INITIAL_LAZY_MODULES,
  measureBundle,
  parseGraph,
  pocModulesInChunks,
  sentSize,
  SERVER_GZIP_MIN_BYTES,
} from './bundle-size.mjs';
import { createFixture } from './test-fixtures.mjs';

const SCRIPT = fileURLToPath(new URL('./bundle-size.mjs', import.meta.url));

/** @type {(() => void)[]} */
const cleanups = [];
afterEach(() => {
  cleanups.splice(0).forEach((cleanup) => {
    cleanup();
  });
});

/**
 * @param {string} fileName
 * @param {Partial<import('./bundle-size.mjs').GraphChunk> & { isDynamicEntry?: boolean }} [rest]
 */
function chunk(fileName, rest = {}) {
  return {
    fileName,
    name: fileName,
    isEntry: false,
    isDynamicEntry: false,
    facadeModule: null,
    modules: [],
    imports: [],
    dynamicImports: [],
    importedCss: [],
    ...rest,
  };
}

/**
 * A build shaped like the real one: index entry with a vendor chunk, the lazy routes of the
 * initial path (AuthGate bundled into a shared chunk, not a chunk of its own; the XLog screen),
 * and the login page outside it. No XLog PoC (production build, ADR-0010 C-203).
 */
const GRAPH = {
  version: 1,
  outDir: 'dist',
  chunks: [
    chunk('assets/index-a.js', {
      isEntry: true,
      facadeModule: 'index.html',
      modules: ['src/main.tsx'],
      imports: ['assets/vendor-b.js'],
      dynamicImports: [
        'assets/AntdRoot-c.js',
        'assets/gate-d.js',
        'assets/Shell-e.js',
        'assets/ScreenRoute-f.js',
        'assets/LoginPage-g.js',
        'assets/overview-m.js',
        'assets/xlog-h.js',
      ],
      importedCss: ['assets/index-a.css'],
    }),
    chunk('assets/vendor-b.js'),
    chunk('assets/AntdRoot-c.js', {
      isDynamicEntry: true,
      facadeModule: 'src/app/AntdRoot.tsx',
      modules: ['src/app/AntdRoot.tsx'],
      imports: ['assets/vendor-b.js', 'assets/config-i.js'],
    }),
    chunk('assets/config-i.js'),
    chunk('assets/gate-d.js', {
      isDynamicEntry: true,
      facadeModule: 'src/features/auth/session.ts',
      modules: ['src/features/auth/session.ts', 'src/routes/AuthGate.tsx'],
    }),
    chunk('assets/Shell-e.js', {
      isDynamicEntry: true,
      facadeModule: 'src/routes/Shell.tsx',
      modules: ['src/routes/Shell.tsx', 'src/routes/TopBar.tsx'],
      imports: ['assets/states-j.js'],
      importedCss: ['assets/Shell-e.css'],
    }),
    chunk('assets/states-j.js', { importedCss: ['assets/states-j.css'] }),
    chunk('assets/ScreenRoute-f.js', {
      isDynamicEntry: true,
      facadeModule: 'src/routes/ScreenRoute.tsx',
      modules: ['src/routes/ScreenRoute.tsx', 'src/routes/RoutePending.tsx'],
      imports: ['assets/states-j.js'],
    }),
    chunk('assets/LoginPage-g.js', {
      isDynamicEntry: true,
      facadeModule: 'src/features/auth/LoginPage.tsx',
      modules: ['src/features/auth/LoginPage.tsx'],
      imports: ['assets/states-j.js'],
      importedCss: ['assets/auth-k.css'],
    }),
    chunk('assets/xlog-h.js', {
      isDynamicEntry: true,
      facadeModule: 'src/features/xlog/index.ts',
      modules: ['src/features/xlog/index.ts', 'src/features/xlog/XLogPage.tsx'],
      imports: ['assets/states-j.js'],
    }),
    chunk('assets/overview-m.js', {
      isDynamicEntry: true,
      facadeModule: 'src/features/overview/index.ts',
      modules: ['src/features/overview/index.ts', 'src/features/overview/OverviewPage.tsx'],
      imports: ['assets/states-j.js'],
    }),
  ],
  assets: [
    { fileName: '.vite/license.json' },
    { fileName: 'assets/Shell-e.css' },
    { fileName: 'assets/auth-k.css' },
    { fileName: 'assets/index-a.css' },
    { fileName: 'assets/states-j.css' },
    { fileName: 'index.html' },
  ],
};

const INITIAL_JS = [
  'assets/AntdRoot-c.js',
  'assets/ScreenRoute-f.js',
  'assets/Shell-e.js',
  'assets/config-i.js',
  'assets/gate-d.js',
  'assets/index-a.js',
  'assets/overview-m.js',
  'assets/states-j.js',
  'assets/vendor-b.js',
  'assets/xlog-h.js',
];
const INITIAL_CSS = ['assets/Shell-e.css', 'assets/index-a.css', 'assets/states-j.css'];
const OTHER_FILES = ['assets/LoginPage-g.js', 'assets/auth-k.css'];

/** The same build with the XLog PoC route in it (what C-203 forbids in production). */
const GRAPH_WITH_POC = {
  ...GRAPH,
  chunks: [
    ...GRAPH.chunks.map((c) =>
      c.isEntry ? { ...c, dynamicImports: [...c.dynamicImports, 'assets/XLogPocPage-p.js'] } : c,
    ),
    chunk('assets/XLogPocPage-p.js', {
      isDynamicEntry: true,
      facadeModule: 'src/features/xlog-poc/XLogPocPage.tsx',
      modules: ['src/features/xlog-poc/XLogPocPage.tsx', 'src/features/xlog-poc/dummyData.ts'],
    }),
  ],
};

/**
 * Deterministic 32-bit pseudo-random numbers (xorshift32).
 *
 * @param {number} seed non-zero
 * @returns {() => number}
 */
function xorshift(seed) {
  let x = seed | 0;
  return () => {
    x ^= x << 13;
    x ^= x >>> 17;
    x ^= x << 5;
    return x >>> 0;
  };
}

/**
 * Deterministic text of a given length (compresses, but not to nothing).
 *
 * @param {number} seed
 * @param {number} length
 */
function content(seed, length) {
  const next = xorshift(seed);
  let text = '';
  while (text.length < length) {
    const value = next();
    text += `const v${String(value % 997)} = ${String(value)};\n`;
  }
  return text.slice(0, length);
}

/**
 * Deterministic characters that do not compress (gzip size ≥ length).
 *
 * @param {number} length
 */
function noise(length) {
  const next = xorshift(42);
  const bytes = Buffer.alloc(length);
  for (let i = 0; i < length; i += 1) {
    bytes[i] = next() & 0xff;
  }
  return bytes.toString('latin1');
}

/**
 * Writes dist/ for GRAPH plus the graph file.
 *
 * @param {{ graph?: unknown, extra?: Record<string, string>, sizes?: Record<string, string> }} [options]
 */
function fixture({ graph = GRAPH, extra = {}, sizes = {} } = {}) {
  /** @type {Record<string, string | object>} */
  const files = {
    'build/bundle-graph.json': /** @type {object} */ (graph),
    'dist/index.html': '<!doctype html><title>Argos</title>',
    'dist/.vite/license.json': '[]',
  };
  [...INITIAL_JS, ...INITIAL_CSS, ...OTHER_FILES].forEach((file, index) => {
    files[`dist/${file}`] = sizes[file] ?? content(index + 1, 2000 + index * 500);
  });
  for (const [file, text] of Object.entries(extra)) {
    files[`dist/${file}`] = text;
  }
  const created = createFixture(files);
  cleanups.push(created.cleanup);
  return {
    root: created.root,
    distDir: join(created.root, 'dist'),
    graphFile: join(created.root, 'build', 'bundle-graph.json'),
    outFile: join(created.root, 'build', 'bundle-size.json'),
  };
}

/** @param {string} distDir @param {string[]} files */
function gzipTotal(distDir, files) {
  return files.reduce(
    (total, file) => total + gzipSync(readFileSync(join(distDir, file))).length,
    0,
  );
}

/** @param {string[]} args */
function run(args) {
  return spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' });
}

describe('bundle-size: initial path (docs/15 §14)', () => {
  it('defines the initial path by the lazy routes of the first screen after login and the XLog screen', () => {
    expect(INITIAL_LAZY_MODULES).toEqual([
      'src/app/AntdRoot.tsx',
      'src/routes/AuthGate.tsx',
      'src/routes/Shell.tsx',
      'src/routes/ScreenRoute.tsx',
      'src/features/overview/index.ts',
      'src/features/xlog/index.ts',
    ]);
    expect(BUDGET).toEqual({ initialBytes: 350 * 1024, totalBytes: 2560 * 1024 });
  });

  it('takes the entry, the chunks holding the lazy modules, their static imports and their CSS', () => {
    const path = initialPath(parseGraph(GRAPH), INITIAL_LAZY_MODULES);
    expect(path).toEqual({ js: INITIAL_JS, css: INITIAL_CSS, missingModules: [] });
  });

  it('reports lazy modules that are in no chunk', () => {
    const path = initialPath(parseGraph(GRAPH), [
      ...INITIAL_LAZY_MODULES,
      'src/features/active/ActivePage.tsx',
    ]);
    expect(path.missingModules).toEqual(['src/features/active/ActivePage.tsx']);
  });

  it('sums the gzip sizes of the initial path and of every JS/CSS file of dist/', () => {
    const { distDir } = fixture();
    const report = measureBundle({ distDir, graph: parseGraph(GRAPH) });
    const initialGzip = gzipTotal(distDir, [...INITIAL_JS, ...INITIAL_CSS]);
    const totalGzip = gzipTotal(distDir, [...INITIAL_JS, ...INITIAL_CSS, ...OTHER_FILES]);
    expect(report.initial.files.map((file) => file.file).sort()).toEqual(
      [...INITIAL_JS, ...INITIAL_CSS].sort(),
    );
    expect(report.initial.gzipBytes).toBe(initialGzip);
    expect(report.initial.remainingBytes).toBe(350 * 1024 - initialGzip);
    expect(report.total).toEqual({
      files: INITIAL_JS.length + INITIAL_CSS.length + OTHER_FILES.length,
      bytes: [...INITIAL_JS, ...INITIAL_CSS, ...OTHER_FILES].reduce(
        (total, file) => total + readFileSync(join(distDir, file)).length,
        0,
      ),
      gzipBytes: totalGzip,
      remainingBytes: 2560 * 1024 - totalGzip,
    });
    expect(report.warnings).toEqual([]);
    expect(report.pocModules).toBe(0);
    const text = formatReport(report).join('\n');
    expect(text).toContain('budget 350.0 KiB gzip, remaining');
    expect(text).toContain('budget 2560.0 KiB gzip, remaining');
    expect(text).toContain('PoC modules in production chunks: 0 (ADR-0010 C-203)');
    expect(text).not.toContain('WARN');
  });

  it('warns when a budget is exceeded, with the amount over it', () => {
    const { distDir } = fixture();
    const graph = parseGraph(GRAPH);
    const report = measureBundle({
      distDir,
      graph,
      budget: { initialBytes: 1024, totalBytes: 2048 },
    });
    const initial = (report.initial.gzipBytes / 1024).toFixed(1);
    const total = (report.total.gzipBytes / 1024).toFixed(1);
    const initialSent = (report.sent.initialBytes / 1024).toFixed(1);
    const totalSent = (report.sent.totalBytes / 1024).toFixed(1);
    expect(report.warnings).toEqual([
      `initial path ${initial} KiB gzip is over the 1.0 KiB budget by ${((report.initial.gzipBytes - 1024) / 1024).toFixed(1)} KiB (docs/15 §14)`,
      `total ${total} KiB gzip is over the 2.0 KiB budget by ${((report.total.gzipBytes - 2048) / 1024).toFixed(1)} KiB (docs/15 §14)`,
      `initial path sent by the server ${initialSent} KiB is over the 1.0 KiB budget by ${((report.sent.initialBytes - 1024) / 1024).toFixed(1)} KiB (docs/15 §14, C-190)`,
      `total sent by the server ${totalSent} KiB is over the 2.0 KiB budget by ${((report.sent.totalBytes - 2048) / 1024).toFixed(1)} KiB (docs/15 §14, C-190)`,
    ]);
    expect(formatReport(report).filter((line) => line.startsWith('WARN  '))).toHaveLength(4);
  });
});

describe('bundle-size: sizes sent with the server response compression (ADR-0010 C-190)', () => {
  it('counts a file of 2,048 bytes or more with its gzip size and a smaller one as it is', () => {
    expect(SERVER_GZIP_MIN_BYTES).toBe(2048);
    expect(sentSize(2047, 900)).toBe(2047);
    expect(sentSize(2048, 900)).toBe(900);
    expect(sentSize(100_000, 30_000)).toBe(30_000);
  });

  it('sums the sent sizes of the initial path and of every JS/CSS file, against the same budgets', () => {
    // index-a.css below the threshold (sent raw); the fixture's other files have 2,000 + n * 500
    // bytes, so only the first one (AntdRoot-c.js, 2,000 bytes) is below it too.
    const small = content(99, 1500);
    const { distDir } = fixture({ sizes: { 'assets/index-a.css': small } });
    const report = measureBundle({ distDir, graph: parseGraph(GRAPH) });
    /** @param {string[]} files */
    const sentTotal = (files) =>
      files.reduce((total, file) => {
        const bytes = readFileSync(join(distDir, file));
        return total + (bytes.length >= 2048 ? gzipSync(bytes).length : bytes.length);
      }, 0);
    const initialSent = sentTotal([...INITIAL_JS, ...INITIAL_CSS]);
    const totalSent = sentTotal([...INITIAL_JS, ...INITIAL_CSS, ...OTHER_FILES]);
    expect(report.sent).toEqual({
      minBytes: 2048,
      initialBytes: initialSent,
      initialRemainingBytes: 350 * 1024 - initialSent,
      totalBytes: totalSent,
      totalRemainingBytes: 2560 * 1024 - totalSent,
    });
    const byFile = new Map(report.initial.files.map((file) => [file.file, file]));
    const css = byFile.get('assets/index-a.css');
    const root = byFile.get('assets/AntdRoot-c.js');
    const vendor = byFile.get('assets/vendor-b.js');
    expect(css).toMatchObject({ bytes: 1500, sentBytes: 1500 });
    expect(root).toMatchObject({ bytes: 2000, sentBytes: 2000 });
    expect(vendor?.bytes).toBeGreaterThanOrEqual(2048);
    expect(vendor?.sentBytes).toBe(vendor?.gzipBytes);
    // Below the threshold the raw size counts, so the sent total exceeds the gzip total by the
    // savings gzip would have made on those two files.
    expect(report.sent.initialBytes - report.initial.gzipBytes).toBe(
      1500 - (css?.gzipBytes ?? 0) + 2000 - (root?.gzipBytes ?? 0),
    );
    const text = formatReport(report).join('\n');
    expect(text).toContain(
      `sent ${(initialSent / 1024).toFixed(1)} KiB (server gzip from 2.0 KiB, smaller files as they are, C-190), remaining`,
    );
    expect(text).toContain(`${(totalSent / 1024).toFixed(1)} KiB sent`);
    expect(report.warnings).toEqual([]);
  });
});

describe('bundle-size: graph validation (fail closed)', () => {
  it.each([
    ['not an object', null],
    ['wrong version', { ...GRAPH, version: 2 }],
    ['no chunks', { ...GRAPH, chunks: [] }],
    ['a chunk without imports', { ...GRAPH, chunks: [{ ...GRAPH.chunks[0], imports: undefined }] }],
    ['no entry chunk', { ...GRAPH, chunks: GRAPH.chunks.map((c) => ({ ...c, isEntry: false })) }],
  ])('refuses a graph with %s', (_name, data) => {
    expect(() => parseGraph(data)).toThrow(/bundle graph/);
  });

  it('finds files the graph names but dist/ lacks, and JS/CSS files the graph does not know', () => {
    const graph = parseGraph(GRAPH);
    const all = [...INITIAL_JS, ...INITIAL_CSS, ...OTHER_FILES];
    expect(graphMismatches(graph, all)).toEqual([]);
    expect(
      graphMismatches(graph, [
        ...all.filter((f) => f !== 'assets/vendor-b.js'),
        'assets/old-x.js',
        'assets/old-y.css',
      ]),
    ).toEqual([
      'the graph names assets/vendor-b.js, which is not in dist/',
      'dist/assets/old-x.js is not a chunk of the graph',
      'dist/assets/old-y.css is not an asset of the graph',
    ]);
  });
});

describe('bundle-size: command line', () => {
  it('prints the table, writes the JSON report and exits 0', () => {
    const { distDir, graphFile, outFile } = fixture();
    const result = run(['--dist', distDir, '--graph', graphFile, '--out', outFile]);
    expect(result.stderr).toBe('');
    expect(result.status).toBe(0);
    expect(result.stdout).toContain('initial path: entry + static imports + src/app/AntdRoot.tsx');
    const report = JSON.parse(readFileSync(outFile, 'utf8'));
    expect(report.initial.files).toHaveLength(INITIAL_JS.length + INITIAL_CSS.length);
    expect(report.warnings).toEqual([]);
  });

  it('only warns (exit 0) when the initial path is over 350 KiB', () => {
    const { distDir, graphFile, outFile } = fixture({
      sizes: { 'assets/vendor-b.js': noise(360 * 1024) },
    });
    const result = run(['--dist', distDir, '--graph', graphFile, '--out', outFile]);
    expect(result.status).toBe(0);
    expect(result.stderr).toMatch(
      /^WARN {2}initial path \d+\.\d KiB gzip is over the 350\.0 KiB budget by \d+\.\d KiB \(docs\/15 §14\)$/m,
    );
  });

  it('exits 1 without a graph', () => {
    const { distDir, root, outFile } = fixture();
    const result = run([
      '--dist',
      distDir,
      '--graph',
      join(root, 'missing.json'),
      '--out',
      outFile,
    ]);
    expect(result.status).toBe(1);
    expect(result.stderr).toContain('ERROR bundle-size: no bundle graph');
  });

  it('exits 1 for a graph that is not JSON or not a bundle graph', () => {
    const broken = fixture({ graph: { version: 1, chunks: 'x' } });
    const result = run([
      '--dist',
      broken.distDir,
      '--graph',
      broken.graphFile,
      '--out',
      broken.outFile,
    ]);
    expect(result.status).toBe(1);
    expect(result.stderr).toContain('ERROR bundle-size: bundle graph:');
  });

  it('exits 1 for a stale graph (dist/ holds a chunk the graph does not know)', () => {
    const { distDir, graphFile, outFile } = fixture({ extra: { 'assets/other-z.js': 'x' } });
    const result = run(['--dist', distDir, '--graph', graphFile, '--out', outFile]);
    expect(result.status).toBe(1);
    expect(result.stderr).toContain('dist/assets/other-z.js is not a chunk of the graph');
  });

  it('exits 1 when a production chunk holds the XLog PoC or its synthetic data generator (C-203)', () => {
    expect(pocModulesInChunks(parseGraph(GRAPH))).toEqual([]);
    expect(pocModulesInChunks(parseGraph(GRAPH_WITH_POC))).toEqual([
      'assets/XLogPocPage-p.js: src/features/xlog-poc/XLogPocPage.tsx',
      'assets/XLogPocPage-p.js: src/features/xlog-poc/dummyData.ts',
    ]);
    const { distDir, graphFile, outFile } = fixture({
      graph: GRAPH_WITH_POC,
      extra: { 'assets/XLogPocPage-p.js': content(77, 3000) },
    });
    expect(() => measureBundle({ distDir, graph: parseGraph(GRAPH_WITH_POC) })).toThrow(
      /PoC modules in production chunks: 2/,
    );
    const result = run(['--dist', distDir, '--graph', graphFile, '--out', outFile]);
    expect(result.status).toBe(1);
    expect(result.stderr).toContain('ERROR bundle-size: PoC modules in production chunks: 2');
    expect(result.stderr).toContain('assets/XLogPocPage-p.js: src/features/xlog-poc/dummyData.ts');
  });

  it('prints the PoC line of a production build', () => {
    const { distDir, graphFile, outFile } = fixture();
    const result = run(['--dist', distDir, '--graph', graphFile, '--out', outFile]);
    expect(result.status).toBe(0);
    expect(result.stdout).toContain('PoC modules in production chunks: 0 (ADR-0010 C-203)');
    expect(JSON.parse(readFileSync(outFile, 'utf8')).pocModules).toBe(0);
  });

  it('exits 1 when an initial-path module is in no chunk', () => {
    const graph = {
      ...GRAPH,
      chunks: GRAPH.chunks.map((c) =>
        c.fileName === 'assets/Shell-e.js'
          ? { ...c, facadeModule: 'src/x.tsx', modules: ['src/x.tsx'] }
          : c,
      ),
    };
    const { distDir, graphFile, outFile } = fixture({ graph });
    const result = run(['--dist', distDir, '--graph', graphFile, '--out', outFile]);
    expect(result.status).toBe(1);
    expect(result.stderr).toContain('initial-path modules are in no chunk');
    expect(result.stderr).toContain('src/routes/Shell.tsx');
  });
});
