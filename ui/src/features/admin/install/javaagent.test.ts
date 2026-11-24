// SPDX-License-Identifier: Apache-2.0
//
// Install snippets (05 §2.11-7, docs/agent-config.md): the collector address from the server's
// public address when the token answer has one (ADR-0010 C-231 (9)), else the browser's host and
// port 9100; the yml carries the token file path, never a token; names that need quoting are
// quoted in both forms; the --check command reads the same yml.
// Requirements (test/README.md §5): { type: 'req', description: 'F-A27' }
import { describe, expect, it } from 'vitest';

import {
  agentYaml,
  checkCommand,
  collectorAddress,
  javaagentLine,
  javaagentOptions,
  TOKEN_FILE,
  yamlScalar,
} from './javaagent';

describe('install snippets (05 §2.11-7)', () => {
  it('uses argos.collector.public-address when known, else host:9100', () => {
    expect(collectorAddress('argos.example', null)).toBe('argos.example:9100');
    expect(collectorAddress('argos.example', '  collector.corp:443 ')).toBe('collector.corp:443');
    expect(collectorAddress('h', '')).toBe('h:9100');
    expect(javaagentOptions('order-api', 'prod', 'h', 'c.corp:9443')).toContain(
      '-Dargos.server.address=c.corp:9443',
    );
  });

  it('writes argos-agent.yml with name, env, address and the token file, never a token', () => {
    expect(agentYaml('order-api', 'prod', 'argos.example')).toBe(
      [
        'server:',
        '  address: argos.example:9100',
        `  token-file: ${TOKEN_FILE}`,
        'app:',
        '  name: order-api',
        '  env: prod',
      ].join('\n'),
    );
    expect(agentYaml('a', 'b', 'h', 'collector:9100')).toContain('  address: collector:9100');
    expect(agentYaml('a', 'b', '[::1]')).toContain('  address: "[::1]:9100"');
    expect(agentYaml('a', 'b')).not.toMatch(/argos_[A-Za-z0-9_-]{8,}/);
  });

  it('quotes YAML values that are not plain-safe', () => {
    expect(yamlScalar('order "api" #1')).toBe('"order \\"api\\" #1"');
    expect(yamlScalar('true')).toBe('"true"');
    expect(yamlScalar('a\\b')).toBe('"a\\\\b"');
    expect(yamlScalar('host:')).toBe('"host:"');
    expect(yamlScalar('order-api.v2')).toBe('order-api.v2');
  });

  it('points -javaagent and --check at the same argos-agent.yml', () => {
    expect(javaagentLine()).toBe(
      '-javaagent:/opt/argos-agent/argos-agent.jar=config=/opt/argos-agent/argos-agent.yml',
    );
    expect(checkCommand()).toBe(
      'java -jar /opt/argos-agent/argos-agent.jar --check --config /opt/argos-agent/argos-agent.yml',
    );
  });
});
