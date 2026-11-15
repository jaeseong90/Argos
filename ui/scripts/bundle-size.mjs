// SPDX-License-Identifier: Apache-2.0
//
// Bundle size report against the budget of docs/15 §14 (N-14; docs/19 §4 bundle check). The
// numbers are a reference: going over budget prints a warning and still exits 0. The report fails
// (exit 1) only when it cannot measure: the chunk graph is missing or unreadable, it does not match
// dist/ (a stale graph from another build), or a module of the initial path is not in any chunk.
//
// Input: dist/ and the chunk graph that the bundleGraph() plugin of vite.config.ts writes on every
// `vite build` to build/bundle-graph.json (outside dist/, so it never ships in the server jar).
//
// Definitions (also in ui/README.md, "Bundle size"):
//
//   initial path  the first screen after login (docs/15 §14 "shell + overview chunk"): the entry
//                 chunk of index.html, the chunks holding INITIAL_LAZY_MODULES (the lazy routes
//                 a signed-in user passes on the way to /overview: Ant Design root, session gate,
//                 shell, screen route with its route-pending placeholder), the transitive closure
//                 of their static imports, and the CSS files Vite loads with any of these chunks.
//                 The login page itself is not part of it.
//   total         every JavaScript and CSS file in dist/.
//
// Sizes are gzip sizes of each file on its own (Node zlib, default level, as Vite's own report).
// Budgets: initial path 350 KiB, total 2.5 MiB (2,560 KiB), both gzip (docs/15 §14).
//
// Sent sizes (ADR-0010 C-190): what the server's response compression puts on the wire for a
// browser that accepts gzip. application.yml compresses JavaScript and CSS of 2 KB (2,048 bytes)
// or more (server.compression.min-response-size) and sends smaller files as they are, so a file
// counts with its gzip size from 2,048 bytes and with its raw size below. The server's gzip is the
// JDK's deflater at its default level like Node's here, so the numbers are an estimate within a few
// bytes per file. They are compared with the same budgets.
//
// Output: a table on stdout and the same data as JSON in build/bundle-size.json.
//
// Usage: node scripts/bundle-size.mjs [--dist <dir>] [--graph <file>] [--out <file>]
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { gzipSync } from 'node:zlib';

const KIB = 1024;

/** docs/15 §14: shell + overview chunk ≤ 350 KB gzip, whole bundle ≤ 2.5 MB gzip. */
export const BUDGET = Object.freeze({ initialBytes: 350 * KIB, totalBytes: 2560 * KIB });

/**
 * Lazy modules on the way from login to the first screen (/overview). WO-31 adds the overview
 * screen module here when it replaces the route-pending placeholder (docs/15 §14 counts the
 * overview chunk in the 350 KiB).
 */
export const INITIAL_LAZY_MODULES = Object.freeze([
  'src/app/AntdRoot.tsx',
  'src/routes/AuthGate.tsx',
  'src/routes/Shell.tsx',
  'src/routes/ScreenRoute.tsx',
]);

const MEASURED_EXTENSIONS = new Set(['.js', '.mjs', '.css']);

/**
 * The server compresses a JavaScript or CSS response of at least this many bytes (application.yml
 * server.compression.min-response-size 2KB, ADR-0010 C-190) and sends a smaller one as it is.
 */
export const SERVER_GZIP_MIN_BYTES = 2 * KIB;

/**
 * @typedef {object} GraphChunk
 * @property {string} fileName
 * @property {boolean} isEntry
 * @property {string | null} facadeModule
 * @property {string[]} modules
 * @property {string[]} imports
 * @property {string[]} dynamicImports
 * @property {string[]} importedCss
 *
 * @typedef {object} BundleGraph
 * @property {GraphChunk[]} chunks
 * @property {string[]} assets
 *
 * @typedef {object} MeasuredFile
 * @property {string} file
 * @property {'js' | 'css'} kind
 * @property {number} bytes
 * @property {number} gzipBytes
 * @property {number} sentBytes bytes the server sends with its response compression (C-190)
 *
 * @typedef {object} SentSizes
 * @property {number} minBytes the server's compression threshold (SERVER_GZIP_MIN_BYTES)
 * @property {number} initialBytes
 * @property {number} initialRemainingBytes
 * @property {number} totalBytes
 * @property {number} totalRemainingBytes
 *
 * @typedef {object} BundleReport
 * @property {{ initialBytes: number, totalBytes: number }} budget
 * @property {string[]} initialModules
 * @property {{ files: MeasuredFile[], bytes: number, gzipBytes: number, remainingBytes: number }} initial
 * @property {{ files: number, bytes: number, gzipBytes: number, remainingBytes: number }} total
 * @property {SentSizes} sent sizes on the wire with the server's response compression (C-190)
 * @property {string[]} warnings
 */

/**
 * @param {unknown} value
 * @returns {value is string[]}
 */
function isStringArray(value) {
  return Array.isArray(value) && value.every((item) => typeof item === 'string');
}

/**
 * Validates the JSON written by the bundleGraph() plugin (vite.config.ts).
 *
 * @param {unknown} data
 * @returns {BundleGraph}
 */
export function parseGraph(data) {
  if (typeof data !== 'object' || data === null || Reflect.get(data, 'version') !== 1) {
    throw new Error('bundle graph: expected an object with "version": 1');
  }
  const chunks = Reflect.get(data, 'chunks');
  const assets = Reflect.get(data, 'assets');
  if (!Array.isArray(chunks) || chunks.length === 0 || !Array.isArray(assets)) {
    throw new Error('bundle graph: expected non-empty "chunks" and an "assets" array');
  }
  /** @type {GraphChunk[]} */
  const parsedChunks = chunks.map((chunk, index) => {
    const where = `bundle graph: chunks[${String(index)}]`;
    if (typeof chunk !== 'object' || chunk === null) {
      throw new Error(`${where} must be an object`);
    }
    const fileName = Reflect.get(chunk, 'fileName');
    const isEntry = Reflect.get(chunk, 'isEntry');
    const facadeModule = Reflect.get(chunk, 'facadeModule');
    const fields = ['modules', 'imports', 'dynamicImports', 'importedCss'].map((name) =>
      Reflect.get(chunk, name),
    );
    if (
      typeof fileName !== 'string' ||
      fileName.length === 0 ||
      typeof isEntry !== 'boolean' ||
      (facadeModule !== null && typeof facadeModule !== 'string') ||
      !fields.every(isStringArray)
    ) {
      throw new Error(
        `${where} needs fileName, isEntry, facadeModule, modules, imports, dynamicImports and importedCss`,
      );
    }
    const [modules, imports, dynamicImports, importedCss] = /** @type {string[][]} */ (fields);
    return {
      fileName,
      isEntry,
      facadeModule,
      modules: modules ?? [],
      imports: imports ?? [],
      dynamicImports: dynamicImports ?? [],
      importedCss: importedCss ?? [],
    };
  });
  const parsedAssets = assets.map((asset, index) => {
    const fileName =
      typeof asset === 'object' && asset !== null ? Reflect.get(asset, 'fileName') : null;
    if (typeof fileName !== 'string' || fileName.length === 0) {
      throw new Error(`bundle graph: assets[${String(index)}] needs a fileName`);
    }
    return fileName;
  });
  if (!parsedChunks.some((chunk) => chunk.isEntry)) {
    throw new Error('bundle graph: no entry chunk');
  }
  return { chunks: parsedChunks, assets: parsedAssets };
}

/**
 * The files of the initial path (see the header): entry chunks, the chunks holding the lazy
 * modules, the closure of their static imports and the CSS of all of them.
 *
 * @param {BundleGraph} graph
 * @param {readonly string[]} lazyModules
 * @returns {{ js: string[], css: string[], missingModules: string[] }}
 */
export function initialPath(graph, lazyModules) {
  const byFile = new Map(graph.chunks.map((chunk) => [chunk.fileName, chunk]));
  /** @type {string[]} */
  const missingModules = [];
  const start = graph.chunks.filter((chunk) => chunk.isEntry);
  for (const module of lazyModules) {
    // The chunk whose facade is the module (its own lazy chunk), otherwise the chunk it was
    // bundled into (a module also imported statically elsewhere has no chunk of its own).
    const holder =
      graph.chunks.find((chunk) => chunk.facadeModule === module) ??
      graph.chunks.find((chunk) => chunk.modules.includes(module));
    if (holder === undefined) {
      missingModules.push(module);
    } else {
      start.push(holder);
    }
  }
  /** @type {Set<string>} */
  const seen = new Set();
  const queue = start.map((chunk) => chunk.fileName);
  while (queue.length > 0) {
    const file = /** @type {string} */ (queue.shift());
    if (seen.has(file)) {
      continue;
    }
    seen.add(file);
    for (const imported of byFile.get(file)?.imports ?? []) {
      queue.push(imported);
    }
  }
  const js = [...seen].sort();
  const css = [...new Set(js.flatMap((file) => byFile.get(file)?.importedCss ?? []))].sort();
  return { js, css, missingModules };
}

/**
 * @param {string} dir
 * @returns {string[]} paths relative to dir, with '/'
 */
function listFiles(dir) {
  /** @type {string[]} */
  const files = [];
  /** @param {string} current */
  const walk = (current) => {
    for (const entry of readdirSync(current).sort()) {
      const path = join(current, entry);
      if (statSync(path).isDirectory()) {
        walk(path);
      } else {
        files.push(relative(dir, path).split(sep).join('/'));
      }
    }
  };
  walk(dir);
  return files;
}

/**
 * Checks that the graph describes this dist/ (every file it names exists, every JS file of dist/
 * is one of its chunks); returns the problems.
 *
 * @param {BundleGraph} graph
 * @param {readonly string[]} distFiles
 * @returns {string[]}
 */
export function graphMismatches(graph, distFiles) {
  const present = new Set(distFiles);
  const named = new Set([
    ...graph.chunks.flatMap((chunk) => [chunk.fileName, ...chunk.imports, ...chunk.importedCss]),
    ...graph.chunks.flatMap((chunk) => chunk.dynamicImports),
  ]);
  const problems = [...named]
    .filter((file) => !present.has(file))
    .sort()
    .map((file) => `the graph names ${file}, which is not in dist/`);
  const chunkFiles = new Set(graph.chunks.map((chunk) => chunk.fileName));
  const assetFiles = new Set(graph.assets);
  for (const file of distFiles) {
    const extension = extname(file);
    if ((extension === '.js' || extension === '.mjs') && !chunkFiles.has(file)) {
      problems.push(`dist/${file} is not a chunk of the graph`);
    } else if (extension === '.css' && !assetFiles.has(file)) {
      problems.push(`dist/${file} is not an asset of the graph`);
    }
  }
  return problems;
}

/**
 * @param {string} distDir
 * @param {string} file relative to distDir
 * @returns {MeasuredFile}
 */
function measureFile(distDir, file) {
  const content = readFileSync(join(distDir, file));
  const gzipBytes = gzipSync(content).length;
  return {
    file,
    kind: extname(file) === '.css' ? 'css' : 'js',
    bytes: content.length,
    gzipBytes,
    sentBytes: sentSize(content.length, gzipBytes),
  };
}

/**
 * What the server sends for a JavaScript or CSS file (C-190): gzip from SERVER_GZIP_MIN_BYTES on,
 * the file as it is below.
 *
 * @param {number} bytes
 * @param {number} gzipBytes
 * @returns {number}
 */
export function sentSize(bytes, gzipBytes) {
  return bytes >= SERVER_GZIP_MIN_BYTES ? gzipBytes : bytes;
}

/**
 * Measures dist/ with a chunk graph. Throws when the graph does not match dist/ or a lazy module
 * is in no chunk (the report would be wrong).
 *
 * @param {{ distDir: string, graph: BundleGraph, lazyModules?: readonly string[], budget?: { initialBytes: number, totalBytes: number } }} options
 * @returns {BundleReport}
 */
export function measureBundle({
  distDir,
  graph,
  lazyModules = INITIAL_LAZY_MODULES,
  budget = BUDGET,
}) {
  const distFiles = listFiles(distDir);
  const mismatches = graphMismatches(graph, distFiles);
  if (mismatches.length > 0) {
    throw new Error(
      `bundle graph does not match dist/ (stale graph? run vite build again):\n  ${mismatches.join('\n  ')}`,
    );
  }
  const path = initialPath(graph, lazyModules);
  if (path.missingModules.length > 0) {
    throw new Error(
      `initial-path modules are in no chunk (renamed? update INITIAL_LAZY_MODULES): ${path.missingModules.join(', ')}`,
    );
  }
  const initialFiles = [...path.js, ...path.css].map((file) => measureFile(distDir, file));
  const allFiles = distFiles
    .filter((file) => MEASURED_EXTENSIONS.has(extname(file)))
    .map((file) => measureFile(distDir, file));
  const sum = (
    /** @type {MeasuredFile[]} */ files,
    /** @type {'bytes' | 'gzipBytes' | 'sentBytes'} */ key,
  ) => files.reduce((total, file) => total + file[key], 0);
  const initialGzip = sum(initialFiles, 'gzipBytes');
  const totalGzip = sum(allFiles, 'gzipBytes');
  const initialSent = sum(initialFiles, 'sentBytes');
  const totalSent = sum(allFiles, 'sentBytes');
  /** @type {string[]} */
  const warnings = [];
  if (initialGzip > budget.initialBytes) {
    warnings.push(
      `initial path ${kib(initialGzip)} KiB gzip is over the ${kib(budget.initialBytes)} KiB budget ` +
        `by ${kib(initialGzip - budget.initialBytes)} KiB (docs/15 §14)`,
    );
  }
  if (totalGzip > budget.totalBytes) {
    warnings.push(
      `total ${kib(totalGzip)} KiB gzip is over the ${kib(budget.totalBytes)} KiB budget ` +
        `by ${kib(totalGzip - budget.totalBytes)} KiB (docs/15 §14)`,
    );
  }
  if (initialSent > budget.initialBytes) {
    warnings.push(
      `initial path sent by the server ${kib(initialSent)} KiB is over the ${kib(budget.initialBytes)} KiB ` +
        `budget by ${kib(initialSent - budget.initialBytes)} KiB (docs/15 §14, C-190)`,
    );
  }
  if (totalSent > budget.totalBytes) {
    warnings.push(
      `total sent by the server ${kib(totalSent)} KiB is over the ${kib(budget.totalBytes)} KiB ` +
        `budget by ${kib(totalSent - budget.totalBytes)} KiB (docs/15 §14, C-190)`,
    );
  }
  return {
    budget: { ...budget },
    initialModules: [...lazyModules],
    initial: {
      files: initialFiles.sort((a, b) => b.gzipBytes - a.gzipBytes || a.file.localeCompare(b.file)),
      bytes: sum(initialFiles, 'bytes'),
      gzipBytes: initialGzip,
      remainingBytes: budget.initialBytes - initialGzip,
    },
    total: {
      files: allFiles.length,
      bytes: sum(allFiles, 'bytes'),
      gzipBytes: totalGzip,
      remainingBytes: budget.totalBytes - totalGzip,
    },
    sent: {
      minBytes: SERVER_GZIP_MIN_BYTES,
      initialBytes: initialSent,
      initialRemainingBytes: budget.initialBytes - initialSent,
      totalBytes: totalSent,
      totalRemainingBytes: budget.totalBytes - totalSent,
    },
    warnings,
  };
}

/**
 * @param {number} bytes
 * @returns {string} KiB with one decimal
 */
export function kib(bytes) {
  return (bytes / KIB).toFixed(1);
}

/**
 * The report as text lines (table of the initial path, totals, budgets and remaining margin).
 *
 * @param {BundleReport} report
 * @returns {string[]}
 */
export function formatReport(report) {
  const width = Math.max(12, ...report.initial.files.map((file) => file.file.length));
  /** @param {string} file @param {string} raw @param {string} gzip @param {string} sent */
  const row = (file, raw, gzip, sent) =>
    `  ${file.padEnd(width)}  ${raw.padStart(9)}  ${gzip.padStart(9)}  ${sent.padStart(9)}`;
  const sentRule = `server gzip from ${kib(report.sent.minBytes)} KiB, smaller files as they are, C-190`;
  return [
    'bundle-size (reference, docs/15 §14; sizes in KiB, gzip per file; sent = on the wire with the ' +
      'server response compression)',
    `initial path: entry + static imports + ${report.initialModules.join(', ')} + their CSS`,
    row('file', 'raw', 'gzip', 'sent'),
    ...report.initial.files.map((file) =>
      row(file.file, kib(file.bytes), kib(file.gzipBytes), kib(file.sentBytes)),
    ),
    row(
      'initial path',
      kib(report.initial.bytes),
      kib(report.initial.gzipBytes),
      kib(report.sent.initialBytes),
    ),
    `  budget ${kib(report.budget.initialBytes)} KiB gzip, remaining ${kib(report.initial.remainingBytes)} KiB`,
    `  sent ${kib(report.sent.initialBytes)} KiB (${sentRule}), remaining ${kib(report.sent.initialRemainingBytes)} KiB of the budget`,
    `total (${String(report.total.files)} JS/CSS files): ${kib(report.total.bytes)} KiB raw, ` +
      `${kib(report.total.gzipBytes)} KiB gzip, ${kib(report.sent.totalBytes)} KiB sent`,
    `  budget ${kib(report.budget.totalBytes)} KiB gzip, remaining ${kib(report.total.remainingBytes)} KiB`,
    `  sent ${kib(report.sent.totalBytes)} KiB (${sentRule}), remaining ${kib(report.sent.totalRemainingBytes)} KiB of the budget`,
    ...report.warnings.map((warning) => `WARN  ${warning}`),
  ];
}

/**
 * @param {string[]} argv
 * @returns {number} exit code
 */
export function main(argv) {
  const uiRoot = fileURLToPath(new URL('..', import.meta.url));
  const { values } = parseArgs({
    args: argv,
    options: {
      dist: { type: 'string', default: join(uiRoot, 'dist') },
      graph: { type: 'string', default: join(uiRoot, 'build', 'bundle-graph.json') },
      out: { type: 'string', default: join(uiRoot, 'build', 'bundle-size.json') },
    },
    strict: true,
  });
  const distDir = resolve(values.dist);
  const graphFile = resolve(values.graph);
  /** @type {BundleReport} */
  let report;
  try {
    if (!existsSync(graphFile)) {
      throw new Error(`no bundle graph at ${graphFile}: run vite build (pnpm run build) first`);
    }
    if (!existsSync(distDir)) {
      throw new Error(`no dist directory at ${distDir}`);
    }
    const graph = parseGraph(JSON.parse(readFileSync(graphFile, 'utf8')));
    report = measureBundle({ distDir, graph });
  } catch (error) {
    console.error(`ERROR bundle-size: ${error instanceof Error ? error.message : String(error)}`);
    return 1;
  }
  for (const line of formatReport(report)) {
    if (line.startsWith('WARN')) {
      console.warn(line);
    } else {
      console.log(line);
    }
  }
  const out = resolve(values.out);
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, `${JSON.stringify(report, null, 2)}\n`);
  console.log(`bundle-size: report written to ${relative(uiRoot, out).split(sep).join('/')}`);
  return 0;
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = main(process.argv.slice(2));
}
