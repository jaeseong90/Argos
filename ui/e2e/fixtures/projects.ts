// SPDX-License-Identifier: Apache-2.0
//
// Which Playwright projects a run selects (playwright.config.ts). The `preview` project needs
// `vite preview` serving dist/ (webServer) and a fresh dist/ (global setup); the stack projects do
// not: the server of the E2E stack serves the UI from its boot jar, and the Linux Playwright
// container of e2e:docker has no Vite at all. Playwright gives the configuration no other way to
// know the selection than the command line it was started with.

/** Every project of playwright.config.ts. */
export const PROJECTS = [
  'preview',
  'stack-setup',
  'stack',
  'stack-teardown',
  'initial-load',
] as const;
export type ProjectName = (typeof PROJECTS)[number];

/**
 * The project names given with `--project <name...>` (variadic, as Playwright reads it: every
 * following argument up to the next option) or `--project=<name>`, repeatable; null when none is
 * given (Playwright then runs every project).
 */
export function selectedProjects(argv: readonly string[]): string[] | null {
  const names: string[] = [];
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i] ?? '';
    if (arg === '--project') {
      while (i + 1 < argv.length && !(argv[i + 1] ?? '-').startsWith('-')) {
        names.push(argv[i + 1] ?? '');
        i += 1;
      }
    } else if (arg.startsWith('--project=')) {
      names.push(arg.slice('--project='.length));
    }
  }
  return names.length === 0 ? null : names;
}

/** Whether the run includes the `preview` project (it needs vite preview and a fresh dist/). */
export function runsPreview(argv: readonly string[]): boolean {
  const selected = selectedProjects(argv);
  return selected === null || selected.includes('preview');
}

/** Environment variable that hands the runner's selection to the worker processes. */
export const SELECTION_ENV = 'ARGOS_E2E_PROJECT_SELECTION';

/**
 * The selection of this run, the same in the runner and in its workers: the runner reads its
 * command line and stores the result in the environment, which the workers inherit (their own
 * command line names no project). The configuration is evaluated in every process, and a project
 * list that differed between them would break the run.
 */
export function projectSelection(
  argv: readonly string[],
  env: Record<string, string | undefined>,
): string[] | null {
  const stored = env[SELECTION_ENV];
  if (stored !== undefined && stored !== '') {
    const parsed: unknown = JSON.parse(stored);
    return Array.isArray(parsed) ? parsed.map(String) : null;
  }
  const selection = selectedProjects(argv);
  env[SELECTION_ENV] = JSON.stringify(selection);
  return selection;
}
