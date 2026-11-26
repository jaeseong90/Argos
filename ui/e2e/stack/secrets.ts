// SPDX-License-Identifier: Apache-2.0
//
// Per-run secrets of the E2E stack (SEC-74): every password, token and key is generated with the
// CSPRNG for one run, written to a file under ui/e2e/.stack/<run>/secrets/ (git-ignored, owner-only
// where the file system supports it, deleted at teardown) and handed to containers as a file, as a
// SHA-256 hash or on stdin. Nothing here logs a value.
import { createHash, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

/** 32 random bytes as unpadded base64url (43 characters, like ServerTestEnvironment's passwords). */
export function newSecret(bytes = 32): string {
  return randomBytes(bytes).toString('base64url');
}

/** Lower-case hex SHA-256 of the UTF-8 bytes of `value`. */
export function sha256Hex(value: string): string {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

/**
 * A new agent token in the format of 16 §2.1 (`argos_` + base64url of 32 random bytes), the same as
 * the server fixture AgentTokens.generate().
 */
export function newAgentToken(): string {
  return `argos_${newSecret(32)}`;
}

/** Writes `value` to `dir/name` (mode 0600 on POSIX) and returns the path. */
export function writeSecret(dir: string, name: string, value: string): string {
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const path = join(dir, name);
  writeFileSync(path, value, { encoding: 'utf8', mode: 0o600 });
  return path;
}

/** Reads a secret file written by writeSecret. */
export function readSecret(dir: string, name: string): string {
  return readFileSync(join(dir, name), 'utf8');
}

/** The secrets of one stack run. */
export interface StackSecrets {
  readonly pgSuperuser: string;
  readonly pgOwner: string;
  readonly pgApp: string;
  readonly pgAudit: string;
  readonly pgBackup: string;
  readonly chWriter: string;
  readonly chReader: string;
  readonly chMigrator: string;
  readonly adminInitial: string;
  readonly metricsToken: string;
  /** argos.master-key of the server: encrypts the alert channel secrets (16 §8.2, WO-38). */
  readonly masterKey: string;
}

/** File names inside the secrets directory (also the names the server's *_FILE keys point at). */
export const SECRET_FILES: Readonly<Record<keyof StackSecrets, string>> = {
  pgSuperuser: 'pg-postgres.password',
  pgOwner: 'pg-argos.password',
  pgApp: 'pg-argos_app.password',
  pgAudit: 'pg-argos_audit.password',
  pgBackup: 'pg-argos_backup.password',
  chWriter: 'ch-argos_writer.password',
  chReader: 'ch-argos_reader.password',
  chMigrator: 'ch-argos_migrator.password',
  adminInitial: 'admin-initial.password',
  metricsToken: 'metrics.token',
  masterKey: 'master.key',
};

/** Generates and writes every secret of a run. */
export function createStackSecrets(dir: string): StackSecrets {
  const secrets: StackSecrets = {
    pgSuperuser: newSecret(),
    pgOwner: newSecret(),
    pgApp: newSecret(),
    pgAudit: newSecret(),
    pgBackup: newSecret(),
    chWriter: newSecret(),
    chReader: newSecret(),
    chMigrator: newSecret(),
    adminInitial: newSecret(),
    metricsToken: newSecret(),
    masterKey: newSecret(),
  };
  for (const key of Object.keys(SECRET_FILES) as (keyof StackSecrets)[]) {
    writeSecret(dir, SECRET_FILES[key], secrets[key]);
  }
  return secrets;
}

/** Reads the secrets of a run written by createStackSecrets. */
export function loadStackSecrets(dir: string): StackSecrets {
  const read = (key: keyof StackSecrets): string => readSecret(dir, SECRET_FILES[key]);
  return {
    pgSuperuser: read('pgSuperuser'),
    pgOwner: read('pgOwner'),
    pgApp: read('pgApp'),
    pgAudit: read('pgAudit'),
    pgBackup: read('pgBackup'),
    chWriter: read('chWriter'),
    chReader: read('chReader'),
    chMigrator: read('chMigrator'),
    adminInitial: read('adminInitial'),
    metricsToken: read('metricsToken'),
    // A stack started before WO-38 has no master key file (its server runs without one).
    masterKey: existsSync(join(dir, SECRET_FILES.masterKey)) ? read('masterKey') : '',
  };
}

/**
 * The setup token of a fresh stack (WO-46, e2e/stack/fresh.ts), copied from the server's
 * /data/setup-token into the secrets directory. Absent for a seeded stack.
 */
export const SETUP_TOKEN_FILE = 'setup.token';

/** The values of those of `names` that exist in `dir` (for redaction; never printed). */
export function optionalSecrets(dir: string, names: readonly string[]): string[] {
  return names
    .filter((name) => existsSync(join(dir, name)))
    .map((name) => readSecret(dir, name).trim())
    .filter((value) => value !== '');
}

/** Every value of `secrets` (for redaction of error messages). */
export function secretValues(secrets: StackSecrets): string[] {
  return (Object.keys(SECRET_FILES) as (keyof StackSecrets)[])
    .map((key) => secrets[key])
    .filter((value) => value !== '');
}
