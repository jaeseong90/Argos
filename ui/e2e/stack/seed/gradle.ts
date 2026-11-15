// SPDX-License-Identifier: Apache-2.0
//
// Runs the repository's Gradle wrapper and bash scripts from Node on Linux, macOS and Windows (Git
// Bash: never the WSL launcher that C:\Windows\System32\bash.exe is).
import { existsSync } from 'node:fs';
import { join } from 'node:path';

import { run, type RunOptions, type RunResult } from '../docker.ts';
import { REPO_ROOT } from '../paths.ts';

/** The bash of Git for Windows, or `bash` elsewhere. */
export function bashExecutable(
  env: Readonly<Record<string, string | undefined>> = process.env,
): string {
  if (process.platform !== 'win32') {
    return 'bash';
  }
  const candidates = [
    env.ARGOS_E2E_BASH,
    join(env.ProgramFiles ?? 'C:\\Program Files', 'Git', 'bin', 'bash.exe'),
    join(env['ProgramFiles(x86)'] ?? 'C:\\Program Files (x86)', 'Git', 'bin', 'bash.exe'),
  ];
  for (const candidate of candidates) {
    if (candidate !== undefined && existsSync(candidate)) {
      return candidate;
    }
  }
  throw new Error('Git Bash not found (set ARGOS_E2E_BASH to bash.exe of Git for Windows)');
}

/** `./gradlew <args>` in the repository root. */
export function gradle(args: readonly string[], options: RunOptions = {}): Promise<RunResult> {
  return run(bashExecutable(), ['gradlew', '--console=plain', ...args], {
    cwd: REPO_ROOT,
    timeoutMs: 20 * 60_000,
    ...options,
  });
}

/** `bash <script> <args>` in the repository root. */
export function bashScript(
  script: string,
  args: readonly string[],
  options: RunOptions = {},
): Promise<RunResult> {
  return run(bashExecutable(), [script, ...args], { cwd: REPO_ROOT, ...options });
}

/** The java executable of JAVA_HOME (17+ for the sample apps), else `java` on PATH. */
export function javaExecutable(
  env: Readonly<Record<string, string | undefined>> = process.env,
): string {
  const home = env.JAVA_HOME;
  if (home !== undefined && home !== '') {
    const exe = join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (existsSync(exe)) {
      return exe;
    }
  }
  return 'java';
}
