// SPDX-License-Identifier: Apache-2.0
//
// Defensive parsers of the text command results (docs/10 §12.1, WO-37 PM question 1): the self
// diagnosis (SelfDiagnostics format 1 gauges, counters, modules, effective_config with sources,
// unknown keys kept as text, absent gauges not 0) and the environment dump (env, system
// properties, JVM arguments as maps or lists, masked values kept exactly as sent), and raw text for
// anything that is not a JSON object.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U05' }, { type: 'req', description: 'SEC-20' }
import { describe, expect, it } from 'vitest';

import { hasMaskedPart, isMasked, parseEnvDump } from './envDump';
import { parseSelfDiagnosis } from './selfDiagnosis';

describe('self diagnosis parser', () => {
  it('reads format 1 gauges, counters, modules and the effective configuration with sources', () => {
    const parsed = parseSelfDiagnosis(
      JSON.stringify({
        format: 1,
        threadlocal_contexts_live: 0,
        weakmap_entries: 12,
        agent_threads_cpu_seconds_total: 1.25,
        counters: { argos_agent_dropped_total: 0, argos_agent_spans_total: 42 },
        modules: [
          { id: 3, name: 'servlet', state: 'ACTIVE', errors: 0 },
          { id: 5, name: 'jdbc', state: 'DISABLED', errors: 7 },
          { id: 9, state: 'ACTIVE' },
        ],
        effective_config: [
          { key: 'sampling.normal-rate', value: 0.1, source: 'server' },
          { key: 'server.address', value: 'collector:9100', source: 'file' },
          { key: 'server.token', value: '****(len=40)', source: 'env' },
          { key: 'nosource', value: true },
        ],
        future_key: { nested: [1, 2] },
      }),
    );
    expect(parsed.kind).toBe('report');
    if (parsed.kind !== 'report') {
      return;
    }
    const { report } = parsed;
    expect(report.format).toBe(1);
    // Absent gauges (not measured) are left out, never 0.
    expect(report.gauges.map((g) => g.key)).toEqual([
      'threadlocal_contexts_live',
      'weakmap_entries',
      'agent_threads_cpu_seconds_total',
    ]);
    expect(report.counters).toEqual([
      { name: 'argos_agent_dropped_total', value: 0 },
      { name: 'argos_agent_spans_total', value: 42 },
    ]);
    expect(report.modules).toEqual([
      { id: 3, name: 'servlet', state: 'ACTIVE', errors: 0, reason: null },
      { id: 5, name: 'jdbc', state: 'DISABLED', errors: 7, reason: null },
      { id: 9, name: '9', state: 'ACTIVE', errors: null, reason: null },
    ]);
    expect(report.effectiveConfig).toEqual([
      { key: 'sampling.normal-rate', value: '0.1', source: 'server' },
      { key: 'server.address', value: 'collector:9100', source: 'file' },
      { key: 'server.token', value: '****(len=40)', source: 'env' },
      { key: 'nosource', value: 'true', source: null },
    ]);
    expect(report.other).toEqual([{ key: 'future_key', value: '{"nested":[1,2]}' }]);
  });

  it('accepts effective_config as a map and keeps non-JSON or non-object results as text', () => {
    const map = parseSelfDiagnosis(
      JSON.stringify({ effective_config: { a: 1, b: { value: 'x', source: 'env' } } }),
    );
    expect(map.kind === 'report' ? map.report.effectiveConfig : null).toEqual([
      { key: 'a', value: '1', source: null },
      { key: 'b', value: 'x', source: 'env' },
    ]);
    expect(parseSelfDiagnosis('agent says hi')).toEqual({ kind: 'text', text: 'agent says hi' });
    expect(parseSelfDiagnosis('[1,2]')).toEqual({ kind: 'text', text: '[1,2]' });
    expect(parseSelfDiagnosis(null)).toEqual({ kind: 'text', text: '' });
  });

  it('reads the real agent result: effective_config map, modules with reason (C-234 (1))', () => {
    // The shape SelfDiagCommand writes (agent-core, WO-17): snapshot members first, then the rest.
    const thresholdReason =
      'error threshold reached (instrumentation.error-threshold or' +
      ' instrumentation.transform-error-threshold)';
    const parsed = parseSelfDiagnosis(
      JSON.stringify({
        format: 1,
        threadlocal_contexts_live: 0,
        active_registry_size: 2,
        premain_ms: 412.5,
        counters: { 'argos_agent_module_errors_total{module="jdbc"}': 11 },
        modules: [
          { id: 0, name: 'servlet', state: 'ENABLED', errors: 0 },
          { id: 1, name: 'jdbc', state: 'DISABLED_BY_ERRORS', errors: 11, reason: thresholdReason },
          {
            id: 2,
            name: 'okhttp',
            state: 'DISABLED_BY_REMOTE',
            errors: 0,
            reason: 'instrumentation.disabled (remote configuration)',
          },
        ],
        agent: { version: '0.1.0', commit: 'abc1234', protocol: '1', release_build: false },
        jvm: { java_version: '21.0.5', vm_name: 'OpenJDK 64-Bit Server VM', feature_version: 21 },
        config_hash: '00112233445566aa',
        effective_config: {
          'server.token': { value: '****', source: 'env' },
          'log.level': { value: 'info', source: 'default' },
          'sampling.profile.normal-rate': { value: '0.3', source: 'remote' },
          'app.name': { value: 'shop', source: 'sysprop' },
          'server.address': { value: 'collector:9100', source: 'file' },
        },
        remote_config_allow: ['sampling.**', 'log.level'],
        truncated: false,
      }),
    );
    expect(parsed.kind).toBe('report');
    if (parsed.kind !== 'report') {
      return;
    }
    const { report } = parsed;
    expect(report.effectiveConfig).toEqual([
      { key: 'server.token', value: '****', source: 'env' },
      { key: 'log.level', value: 'info', source: 'default' },
      { key: 'sampling.profile.normal-rate', value: '0.3', source: 'remote' },
      { key: 'app.name', value: 'shop', source: 'sysprop' },
      { key: 'server.address', value: 'collector:9100', source: 'file' },
    ]);
    expect(report.modules.map((m) => [m.name, m.state, m.errors, m.reason])).toEqual([
      ['servlet', 'ENABLED', 0, null],
      ['jdbc', 'DISABLED_BY_ERRORS', 11, thresholdReason],
      ['okhttp', 'DISABLED_BY_REMOTE', 0, 'instrumentation.disabled (remote configuration)'],
    ]);
    expect(report.gauges.map((g) => g.key)).toEqual([
      'threadlocal_contexts_live',
      'active_registry_size',
      'premain_ms',
    ]);
    // WO-17 members this UI does not name stay readable as key/value text.
    expect(report.other.map((o) => o.key)).toEqual([
      'agent',
      'jvm',
      'config_hash',
      'remote_config_allow',
      'truncated',
    ]);
  });
});

describe('environment dump parser', () => {
  it('reads env, system properties and JVM arguments, masked values exactly as sent', () => {
    const parsed = parseEnvDump(
      JSON.stringify({
        env: { PATH: '/usr/bin', DB_PASSWORD: '****(len=12)' },
        system_properties: [
          { key: 'java.version', value: '21.0.5' },
          { name: 'javax.net.ssl.keyStorePassword', value: '****(len=8)' },
        ],
        jvm_arguments: ['-Xmx2g', '-javaagent:/opt/argos/argos-agent.jar'],
        host_extra: 'x',
      }),
    );
    expect(parsed.kind).toBe('report');
    if (parsed.kind !== 'report') {
      return;
    }
    expect(parsed.report.env).toEqual([
      { key: 'DB_PASSWORD', value: '****(len=12)' },
      { key: 'PATH', value: '/usr/bin' },
    ]);
    expect(parsed.report.systemProperties.map((e) => e.key)).toEqual([
      'java.version',
      'javax.net.ssl.keyStorePassword',
    ]);
    expect(parsed.report.jvmArguments).toEqual(['-Xmx2g', '-javaagent:/opt/argos/argos-agent.jar']);
    expect(parsed.report.other).toEqual([{ key: 'host_extra', value: 'x' }]);
    expect(isMasked('****(len=12)')).toBe(true);
    expect(isMasked('****')).toBe(false);
    expect(isMasked('secret')).toBe(false);
  });

  it('reads the agent sectioned text: env, properties, args, masked as sent (C-234 (1))', () => {
    // EnvDumpCommand's text/plain form (agent-core, WO-17).
    const parsed = parseEnvDump(
      [
        '# env (4)',
        'DB_PASSWORD=****(len=6)',
        'JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64',
        'PATH=/usr/bin:/bin',
        'WEIRD=a=b\\nc',
        '',
        '# properties (2)',
        'java.version=21.0.5',
        'wo17.api.token=****(len=13)',
        '',
        '# args (3)',
        '-Xmx512m',
        '-Dspring.datasource.password=****(len=1)',
        '-javaagent:/opt/argos/argos-agent.jar',
        '',
      ].join('\n'),
    );
    expect(parsed.kind).toBe('report');
    if (parsed.kind !== 'report') {
      return;
    }
    const { report } = parsed;
    expect(report.env).toEqual([
      { key: 'DB_PASSWORD', value: '****(len=6)' },
      { key: 'JAVA_HOME', value: '/usr/lib/jvm/java-21-openjdk-amd64' },
      { key: 'PATH', value: '/usr/bin:/bin' },
      // Split at the first '='; escaped control characters stay as the agent wrote them.
      { key: 'WEIRD', value: 'a=b\\nc' },
    ]);
    expect(report.systemProperties).toEqual([
      { key: 'java.version', value: '21.0.5' },
      { key: 'wo17.api.token', value: '****(len=13)' },
    ]);
    expect(report.jvmArguments).toEqual([
      '-Xmx512m',
      '-Dspring.datasource.password=****(len=1)',
      '-javaagent:/opt/argos/argos-agent.jar',
    ]);
    expect(report.other).toEqual([]);
    expect(report.truncated).toBe(false);
    expect(report.unavailable).toEqual([]);
    expect(isMasked(report.env[0]?.value ?? '')).toBe(true);
    expect(hasMaskedPart(report.jvmArguments[1] ?? '')).toBe(true);
    expect(hasMaskedPart(report.jvmArguments[0] ?? '')).toBe(false);
  });

  it('marks a cut dump and unavailable sections of the sectioned text', () => {
    const parsed = parseEnvDump(
      [
        '# env (unavailable)',
        '',
        '# properties (1)',
        'a=1',
        '',
        '# args (9)',
        '-Xmx1g',
        '# truncated',
        '',
      ].join('\n'),
    );
    expect(parsed.kind === 'report' ? parsed.report.truncated : null).toBe(true);
    expect(parsed.kind === 'report' ? parsed.report.unavailable : null).toEqual(['env']);
    expect(parsed.kind === 'report' ? parsed.report.env : null).toEqual([]);
    expect(parsed.kind === 'report' ? parsed.report.jvmArguments : null).toEqual(['-Xmx1g']);
    // A line that is not key=value is kept as text, not dropped.
    const odd = parseEnvDump('# env (1)\nno-equals-sign\n');
    expect(odd.kind === 'report' ? odd.report.other : null).toEqual([
      { key: 'env', value: 'no-equals-sign' },
    ]);
    // Text that does not start with a section header stays raw.
    expect(parseEnvDump('hello\n# env (1)\nA=1')).toEqual({
      kind: 'text',
      text: 'hello\n# env (1)\nA=1',
    });
  });

  it('keeps a text result as text and a single JVM argument string as one entry', () => {
    expect(parseEnvDump('PATH=/usr/bin')).toEqual({ kind: 'text', text: 'PATH=/usr/bin' });
    const one = parseEnvDump(JSON.stringify({ jvm_arguments: '-Xmx1g -Xms1g' }));
    expect(one.kind === 'report' ? one.report.jvmArguments : null).toEqual(['-Xmx1g -Xms1g']);
  });
});
