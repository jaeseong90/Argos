// SPDX-License-Identifier: Apache-2.0
//
// Agent install snippets (docs/agent-config.md, 05 §2.11-7, docs/15 §9.10 "설치 안내", §9.11): the
// -javaagent JVM options, the argos-agent.yml and the `--check` command for an application. Shared by
// the setup wizard's first application step (features/auth/SetupFirstApp.tsx) and the install
// guide drawer of the application list (WO-39). Pure functions: no token value ever enters them,
// the token is a file path (`server.token-file`) the operator fills.

/** Where the guide puts the token file, the agent jar and its configuration (docs/install). */
export const TOKEN_FILE = '/opt/argos-agent/token';
export const AGENT_JAR = '/opt/argos-agent/argos-agent.jar';
export const AGENT_CONFIG = '/opt/argos-agent/argos-agent.yml';
export const COLLECTOR_PORT = 9100;

/** A JVM option value: plain when it needs no quoting, else in double quotes. */
function optionValue(value: string): string {
  return /^[A-Za-z0-9._-]+$/.test(value) ? value : `"${value.replace(/(["\\$`])/g, '\\$1')}"`;
}

/** A YAML scalar: plain when it is safe as is, else double-quoted with escapes (agent subset). */
export function yamlScalar(value: string): string {
  if (
    /^[A-Za-z0-9][A-Za-z0-9._/:-]*$/.test(value) &&
    !value.endsWith(':') &&
    !/^(true|false|null|~|yes|no)$/i.test(value)
  ) {
    return value;
  }
  const escaped = value.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  return `"${escaped}"`;
}

/** The collector host as this browser reached the server (IPv6 literals in brackets). */
export function collectorHost(): string {
  const host = typeof window === 'undefined' ? '' : window.location.hostname;
  if (host === '') {
    return '<argos-host>';
  }
  return host.includes(':') && !host.startsWith('[') ? `[${host}]` : host;
}

/**
 * The collector address the agents use: the server setting argos.collector.public-address when
 * known (ADR-0010 C-231 (9): a reverse proxy or NAT in front of the server), else the host this
 * browser reached the server on with port 9100.
 */
export function collectorAddress(
  host: string = collectorHost(),
  publicAddress: string | null = null,
): string {
  return publicAddress !== null && publicAddress.trim() !== ''
    ? publicAddress.trim()
    : `${host}:${String(COLLECTOR_PORT)}`;
}

/** The JVM options of the agent for this application (docs/agent-config.md, system properties). */
export function javaagentOptions(
  name: string,
  env: string,
  host = collectorHost(),
  publicAddress: string | null = null,
): string {
  return [
    `-javaagent:${AGENT_JAR}`,
    `-Dargos.server.address=${collectorAddress(host, publicAddress)}`,
    `-Dargos.server.token-file=${TOKEN_FILE}`,
    `-Dargos.app.name=${optionValue(name)}`,
    `-Dargos.app.env=${optionValue(env)}`,
  ].join('\n');
}

/** The single -javaagent option that reads everything else from argos-agent.yml. */
export function javaagentLine(): string {
  return `-javaagent:${AGENT_JAR}=config=${AGENT_CONFIG}`;
}

/**
 * argos-agent.yml for the application (docs/agent-config.md "The YAML file"): collector address,
 * token file (never the token itself) and the application name and environment.
 */
export function agentYaml(
  name: string,
  env: string,
  host = collectorHost(),
  publicAddress: string | null = null,
): string {
  return [
    'server:',
    `  address: ${yamlScalar(collectorAddress(host, publicAddress))}`,
    `  token-file: ${TOKEN_FILE}`,
    'app:',
    `  name: ${yamlScalar(name)}`,
    `  env: ${yamlScalar(env)}`,
  ].join('\n');
}

/** The pre-flight check of the agent (docs/10 §13.3, F-A27; the command itself is WO-19). */
export function checkCommand(): string {
  return `java -jar ${AGENT_JAR} --check --config ${AGENT_CONFIG}`;
}
