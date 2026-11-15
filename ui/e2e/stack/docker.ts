// SPDX-License-Identifier: Apache-2.0
//
// The docker CLI for the E2E stack (no Docker library: ADR-0010 C-168, WO-30 foundation note).
// Every call passes its arguments as an array (no shell), so paths with spaces and Windows drive
// letters reach docker unchanged. Secrets never appear in arguments: passwords go into containers
// as files (bind mounts), as SHA-256 hashes (ClickHouse from_env) or on stdin (psql), and every
// error message is passed through `redact` with the run's secrets before it is thrown.
import { spawn } from 'node:child_process';

/** Label on every container, network and volume of the stack; the value is the run id. */
export const STACK_LABEL = 'io.argos.e2e';

/** Name prefix of every container, network and volume of the stack. */
export const NAME_PREFIX = 'argos-e2e-';

export interface RunOptions {
  /** Written to the process's stdin, then stdin is closed. */
  readonly input?: string;
  /** Extra environment of the child process. */
  readonly env?: Readonly<Record<string, string>>;
  /** Kill the process after this many milliseconds (default 10 minutes). */
  readonly timeoutMs?: number;
  /** Resolve with the result instead of rejecting when the exit code is not 0. */
  readonly allowFailure?: boolean;
  /** Working directory of the process. */
  readonly cwd?: string;
  /** Strings replaced by **** in error messages and in the returned output. */
  readonly secrets?: readonly string[];
  /** Called with every chunk of stdout and stderr (progress output of long commands). */
  readonly onOutput?: (chunk: string) => void;
}

export interface RunResult {
  readonly code: number;
  readonly stdout: string;
  readonly stderr: string;
}

/** Replaces every secret (at least 8 characters, so that short values cannot mask text) by ****. */
export function redact(text: string, secrets: readonly string[] = []): string {
  let out = text;
  for (const secret of secrets) {
    if (secret.length >= 8) {
      out = out.split(secret).join('****');
    }
  }
  return out;
}

/** At most `max` characters of the end of `text` (error messages keep the last lines). */
export function tail(text: string, max = 4000): string {
  return text.length <= max ? text : `…${text.slice(text.length - max)}`;
}

/** Runs `command` with `args` and collects its output. */
export function run(
  command: string,
  args: readonly string[],
  options: RunOptions = {},
): Promise<RunResult> {
  const secrets = options.secrets ?? [];
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, [...args], {
      cwd: options.cwd,
      env: { ...process.env, ...options.env },
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => {
      child.kill();
    }, options.timeoutMs ?? 600_000);
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk: string) => {
      stdout += chunk;
      options.onOutput?.(redact(chunk, secrets));
    });
    child.stderr.on('data', (chunk: string) => {
      stderr += chunk;
      options.onOutput?.(redact(chunk, secrets));
    });
    child.on('error', (error) => {
      clearTimeout(timer);
      reject(new Error(`${command}: ${redact(error.message, secrets)}`));
    });
    child.on('close', (code, signal) => {
      clearTimeout(timer);
      const result: RunResult = {
        code: code ?? (signal === null ? 1 : 128),
        stdout: redact(stdout, secrets),
        stderr: redact(stderr, secrets),
      };
      if (result.code !== 0 && options.allowFailure !== true) {
        const shown = args.map((a) => redact(a, secrets)).join(' ');
        reject(
          new Error(
            `${command} ${shown} failed (exit ${String(result.code)}${signal === null ? '' : `, ${signal}`}): ${tail(result.stderr.trim() || result.stdout.trim())}`,
          ),
        );
        return;
      }
      resolvePromise(result);
    });
    if (options.input !== undefined) {
      child.stdin.end(options.input, 'utf8');
    } else {
      child.stdin.end();
    }
  });
}

/** `docker <args>`; resolves with stdout (trimmed). */
export async function docker(args: readonly string[], options: RunOptions = {}): Promise<string> {
  const result = await run('docker', args, options);
  return result.stdout.trim();
}

/** Ids of the containers that carry `label=value` (running or not). */
export async function containersWithLabel(label: string, value: string): Promise<string[]> {
  const out = await docker(['ps', '-aq', '--filter', `label=${label}=${value}`]);
  return out.split(/\s+/).filter((id) => id !== '');
}

/**
 * Removes the containers, networks and volumes of one run: only objects that carry
 * `io.argos.e2e=<runId>` and whose name starts with `argos-e2e-` (never anything else).
 */
export async function removeRun(runId: string): Promise<string[]> {
  if (!/^[a-z0-9-]{4,64}$/.test(runId)) {
    throw new Error(`Refusing to remove objects of an invalid run id: ${JSON.stringify(runId)}`);
  }
  const removed: string[] = [];
  const filter = `label=${STACK_LABEL}=${runId}`;
  const containers = await docker(['ps', '-a', '--filter', filter, '--format', '{{.Names}}']);
  for (const name of containers.split(/\s+/).filter((n) => n.startsWith(NAME_PREFIX))) {
    await docker(['rm', '-f', '-v', name], { allowFailure: true });
    removed.push(`container ${name}`);
  }
  const networks = await docker(['network', 'ls', '--filter', filter, '--format', '{{.Name}}']);
  for (const name of networks.split(/\s+/).filter((n) => n.startsWith(NAME_PREFIX))) {
    await docker(['network', 'rm', name], { allowFailure: true });
    removed.push(`network ${name}`);
  }
  const volumes = await docker(['volume', 'ls', '--filter', filter, '--format', '{{.Name}}']);
  for (const name of volumes.split(/\s+/).filter((n) => n.startsWith(NAME_PREFIX))) {
    await docker(['volume', 'rm', '-f', name], { allowFailure: true });
    removed.push(`volume ${name}`);
  }
  return removed;
}

/** The host port that `containerPort/tcp` of `container` is published on (127.0.0.1 only). */
export async function publishedPort(container: string, containerPort: number): Promise<number> {
  const out = await docker(['port', container, `${String(containerPort)}/tcp`]);
  for (const line of out.split(/\r?\n/)) {
    const match = /^127\.0\.0\.1:(\d+)$/.exec(line.trim());
    if (match?.[1] !== undefined) {
      return Number.parseInt(match[1], 10);
    }
  }
  throw new Error(
    `${container}: port ${String(containerPort)} is not published on 127.0.0.1 (docker port: ${out})`,
  );
}

/** The last lines of a container's log (stdout and stderr), redacted. */
export async function containerLogs(
  container: string,
  lines = 200,
  secrets: readonly string[] = [],
): Promise<string> {
  const result = await run('docker', ['logs', '--tail', String(lines), container], {
    allowFailure: true,
    secrets,
  });
  return `${result.stdout}${result.stderr}`;
}
