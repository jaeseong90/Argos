// SPDX-License-Identifier: Apache-2.0
//
// The fake notification receivers of the alert channel E2E tests (e2e/fixtures/fakeReceivers.ts):
// the HTTP receiver records webhook / Slack posts with the signature header's presence and form
// (never its value), the SMTP receiver takes one message the way the server's SmtpClient sends it
// (EHLO without STARTTLS, MAIL, RCPT, DATA with a base64 body and an RFC 2047 subject), and the
// reachability plan says where a delivery can arrive and why it is skipped elsewhere.
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { connect } from 'node:net';

import { afterEach, describe, expect, it } from 'vitest';

import { runDir } from '../stack/paths';
import {
  E2E_SMTP_FROM,
  gatewayAddresses,
  hostCidr,
  receiverArguments,
  receiverConfig,
  serverArguments,
} from '../stack/stack';

import {
  decodeHeader,
  envelopeAddress,
  MAX_HTTP_BODY,
  parseHeaders,
  readReceiverConfig,
  RECEIVER_HOST_ALIAS,
  receiverConfigFile,
  receiverPlan,
  signatureShape,
  startHttpReceiver,
  startSmtpReceiver,
  writeReceiverConfig,
  type HttpReceiver,
  type ReceiverStackConfig,
  type SmtpReceiver,
} from './fakeReceivers';

const open: (HttpReceiver | SmtpReceiver)[] = [];
const runs: string[] = [];

afterEach(async () => {
  for (const receiver of open.splice(0)) {
    await receiver.close();
  }
  for (const run of runs.splice(0)) {
    rmSync(runDir(run), { recursive: true, force: true });
  }
});

const HEX = 'a'.repeat(64);
const HEX2 = '0123456789abcdef'.repeat(4);

describe('signature header shape (16 §8.4)', () => {
  it('accepts t=<unix seconds>,v1=<64 hex> with one or two keys', () => {
    expect(signatureShape(`t=1700000000,v1=${HEX}`)).toEqual({
      present: true,
      wellFormed: true,
      v1Count: 1,
      t: 1700000000,
    });
    expect(signatureShape(`t=1700000000,v1=${HEX},v1=${HEX2}`).v1Count).toBe(2);
  });

  it('tells an absent header from a malformed one', () => {
    expect(signatureShape(undefined)).toEqual({
      present: false,
      wellFormed: false,
      v1Count: 0,
      t: null,
    });
    for (const bad of [
      '',
      `v1=${HEX}`,
      't=1700000000',
      `t=abc,v1=${HEX}`,
      `t=1700000000,v1=${HEX.slice(1)}`,
      `t=1700000000,v1=${HEX.toUpperCase()}`,
      `t=1700000000,v2=${HEX}`,
      `t=1700000000;v1=${HEX}`,
    ]) {
      const shape = signatureShape(bad);
      expect(shape.present, bad).toBe(true);
      expect(shape.wellFormed, bad).toBe(false);
    }
  });
});

describe('HTTP receiver (webhook, Slack)', () => {
  it('records a signed JSON post on 127.0.0.1 and answers 200', async () => {
    const receiver = await startHttpReceiver();
    open.push(receiver);
    expect(receiver.port).toBeGreaterThan(0);
    const url = receiver.url('/hook/abc?x=1');
    expect(url).toBe(`http://127.0.0.1:${String(receiver.port)}/hook/abc?x=1`);
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Argos-Signature': `t=1,v1=${HEX}` },
      body: JSON.stringify({ schema_version: 1, test: true }),
    });
    expect(response.status).toBe(200);
    const request = await receiver.waitForRequest((r) => r.path.startsWith('/hook/abc'), 2000);
    expect(request).toMatchObject({
      method: 'POST',
      path: '/hook/abc?x=1',
      contentType: 'application/json',
      body: '{"schema_version":1,"test":true}',
      truncated: false,
      status: 200,
      signature: { present: true, wellFormed: true, v1Count: 1 },
    });
    expect(JSON.stringify(receiver.requests())).not.toContain(HEX);
  });

  it('answers with the configured status and records an unsigned post (Slack)', async () => {
    const receiver = await startHttpReceiver();
    open.push(receiver);
    receiver.setStatus(503);
    const response = await fetch(receiver.url('/slack'), { method: 'POST', body: '{"text":"x"}' });
    expect(response.status).toBe(503);
    const [request] = receiver.requests();
    expect(request?.status).toBe(503);
    expect(request?.signature.present).toBe(false);
  });

  it('keeps at most 64 KiB of a body', async () => {
    const receiver = await startHttpReceiver();
    open.push(receiver);
    await fetch(receiver.url('/big'), { method: 'POST', body: 'x'.repeat(MAX_HTTP_BODY + 10) });
    const request = await receiver.waitForRequest(() => true, 2000);
    expect(request.body).toHaveLength(MAX_HTTP_BODY);
    expect(request.truncated).toBe(true);
  });

  it('waitForRequest names the receiver when nothing arrives', async () => {
    const receiver = await startHttpReceiver();
    open.push(receiver);
    await expect(receiver.waitForRequest(() => true, 50)).rejects.toThrow(
      /HTTP receiver :\d+: nothing matching within 50 ms \(0 recorded\)/,
    );
  });

  it('brackets an IPv6 host in a URL', async () => {
    const receiver = await startHttpReceiver();
    open.push(receiver);
    expect(receiver.url('/h', 'fdc4::254')).toBe(`http://[fdc4::254]:${String(receiver.port)}/h`);
    expect(receiver.url('/h', RECEIVER_HOST_ALIAS)).toBe(
      `http://argos-e2e-receivers:${String(receiver.port)}/h`,
    );
  });
});

/** A line-oriented SMTP client session: sends each command after the previous reply. */
function smtpSession(port: number, commands: readonly string[]): Promise<string[]> {
  return new Promise((resolve, reject) => {
    const socket = connect(port, '127.0.0.1');
    const replies: string[] = [];
    let buffer = '';
    let next = 0;
    socket.setEncoding('latin1');
    socket.on('data', (chunk: string) => {
      buffer += chunk;
      for (;;) {
        const at = buffer.indexOf('\r\n');
        if (at < 0) {
          return;
        }
        const line = buffer.slice(0, at);
        buffer = buffer.slice(at + 2);
        replies.push(line);
        // A multi-line reply continues while the fourth character is '-'.
        if (line.charAt(3) === '-') {
          continue;
        }
        const command = commands[next++];
        if (command === undefined) {
          socket.end();
          return;
        }
        socket.write(command);
      }
    });
    socket.on('error', reject);
    socket.on('close', () => {
      resolve(replies);
    });
  });
}

describe('SMTP receiver (plain, no STARTTLS)', () => {
  it('takes one message as SmtpClient sends it and records envelope and headers', async () => {
    const receiver = await startSmtpReceiver();
    open.push(receiver);
    const subject = `=?UTF-8?B?${Buffer.from('[WARN] 알림 test', 'utf8').toString('base64')}?=`;
    const body = Buffer.from('line one\n.line two', 'utf8').toString('base64');
    const replies = await smtpSession(receiver.port, [
      'EHLO argos-server\r\n',
      'MAIL FROM:<argos-e2e@example.test>\r\n',
      'RCPT TO:<ops@example.test>\r\n',
      'RCPT TO:<oncall@example.test>\r\n',
      'DATA\r\n',
      `From: argos-e2e@example.test\r\nTo: ops@example.test, oncall@example.test\r\nSubject: ${subject}\r\nContent-Transfer-Encoding: base64\r\n\r\n${body}\r\n..dot-stuffed\r\n.\r\n`,
      'QUIT\r\n',
    ]);
    expect(replies[0]).toMatch(/^220 argos-e2e-receivers /);
    expect(replies).toContain('250-8BITMIME');
    expect(replies.some((r) => r.includes('STARTTLS'))).toBe(false);
    expect(replies.some((r) => r.startsWith('354 '))).toBe(true);
    expect(replies).toContain('250 2.0.0 queued');
    expect(replies.at(-1)).toBe('221 2.0.0 bye');
    const message = await receiver.waitForMessage((m) => m.recipients.length > 0, 2000);
    expect(message).toMatchObject({
      heloName: 'argos-server',
      from: 'argos-e2e@example.test',
      recipients: ['ops@example.test', 'oncall@example.test'],
      subject: '[WARN] 알림 test',
    });
    expect(message.headers['content-transfer-encoding']).toBe('base64');
    expect(message.size).toBeGreaterThan(0);
  });

  it('refuses STARTTLS, DATA before RCPT and unknown commands', async () => {
    const receiver = await startSmtpReceiver();
    open.push(receiver);
    const replies = await smtpSession(receiver.port, [
      'EHLO x\r\n',
      'STARTTLS\r\n',
      'DATA\r\n',
      'RCPT TO:<a@example.test>\r\n',
      'MAIL FROM:bad\r\n',
      'VRFY root\r\n',
      'NOOP\r\n',
      'RSET\r\n',
      'QUIT\r\n',
    ]);
    expect(replies).toEqual(
      expect.arrayContaining([
        '454 4.7.0 TLS not available',
        '503 MAIL and RCPT first',
        '503 MAIL first',
        '501 syntax: MAIL FROM:<address>',
        '502 5.5.2 command not implemented',
      ]),
    );
    expect(receiver.messages()).toEqual([]);
  });

  it('parses envelope addresses, folded headers and encoded words', () => {
    expect(envelopeAddress('<a@b.test> SIZE=10')).toBe('a@b.test');
    expect(envelopeAddress('<>')).toBe('');
    expect(envelopeAddress('a@b.test')).toBeNull();
    expect(parseHeaders('Subject: one\r\n two\r\nX-A: b\r\n\r\nbody: no')).toEqual({
      subject: 'one two',
      'x-a': 'b',
    });
    expect(decodeHeader('=?UTF-8?Q?caf=C3=A9_ok?=')).toBe('café ok');
    expect(decodeHeader('plain')).toBe('plain');
  });
});

describe('stack server settings of the alert channels (test stack only)', () => {
  it('allows plain http to 127.0.0.1 and the host gateway only, and mail to the receiver alias', () => {
    const config = receiverConfig('fdc4:f303:9324::254', 52525);
    expect(config).toEqual({
      host_alias: 'argos-e2e-receivers',
      gateway_address: 'fdc4:f303:9324::254',
      allow_cidrs: ['127.0.0.1/32', 'fdc4:f303:9324::254/128'],
      smtp_port: 52525,
      smtp_from: E2E_SMTP_FROM,
    });
    expect(receiverArguments(config)).toEqual([
      '--argos.alert.webhook.allow-http=true',
      '--argos.alert.webhook.allow-cidrs=127.0.0.1/32,fdc4:f303:9324::254/128',
      '--argos.alert.smtp.host=argos-e2e-receivers',
      '--argos.alert.smtp.port=52525',
      '--argos.alert.smtp.starttls=optional',
      '--argos.alert.smtp.insecure=true',
      '--argos.alert.smtp.from=argos-e2e@example.test',
    ]);
    const args = serverArguments('http://127.0.0.1:1234', config);
    expect(args).toEqual(expect.arrayContaining(receiverArguments(config)));
    // The blocked ranges of the 422 tests stay blocked: no private or link-local range is allowed.
    const allow = args.find((a) => a.startsWith('--argos.alert.webhook.allow-cidrs='));
    expect(allow).not.toMatch(/10\.0\.0\.0|169\.254|0\.0\.0\.0\/0|::\/0/);
    expect(serverArguments('http://127.0.0.1:1234')).not.toEqual(
      expect.arrayContaining(['--argos.alert.webhook.allow-http=true']),
    );
  });

  it('allows only the loopback when the gateway did not resolve to an IP literal', () => {
    expect(receiverConfig(null, 1).allow_cidrs).toEqual(['127.0.0.1/32']);
    expect(receiverConfig('not-an-ip', 1)).toMatchObject({
      gateway_address: null,
      allow_cidrs: ['127.0.0.1/32'],
    });
    expect(receiverConfig('192.168.65.254', 1).allow_cidrs).toEqual([
      '127.0.0.1/32',
      '192.168.65.254/32',
    ]);
  });

  it('allows every address of the gateway alias (Docker Desktop writes IPv4 and IPv6, the server checks both)', () => {
    const hosts = [
      '127.0.0.1\tlocalhost',
      '::1\tlocalhost ip6-localhost ip6-loopback',
      '# 10.9.9.9 argos-e2e-receivers',
      '192.168.65.254\targos-e2e-receivers',
      'fdc4:f303:9324::254\targos-e2e-receivers',
      '192.168.65.254\targos-e2e-receivers # again',
      '172.18.0.4\tabcdef012345',
      'not-an-ip argos-e2e-receivers',
      '',
    ].join('\r\n');
    const addresses = gatewayAddresses(hosts, 'argos-e2e-receivers');
    expect(addresses).toEqual(['192.168.65.254', 'fdc4:f303:9324::254']);
    const config = receiverConfig(addresses, 1);
    expect(config.gateway_address).toBe('192.168.65.254');
    expect(config.allow_cidrs).toEqual([
      '127.0.0.1/32',
      '192.168.65.254/32',
      'fdc4:f303:9324::254/128',
    ]);
    expect(receiverConfig([], 1).allow_cidrs).toEqual(['127.0.0.1/32']);
    expect(gatewayAddresses('', 'argos-e2e-receivers')).toEqual([]);
  });

  it('turns one address into a single-host range', () => {
    expect(hostCidr('192.168.65.254')).toBe('192.168.65.254/32');
    expect(hostCidr('FDC4::254')).toBe('fdc4::254/128');
    expect(hostCidr('256.1.1.1')).toBeNull();
    expect(hostCidr('host.docker.internal')).toBeNull();
    expect(hostCidr('10.0.0.0/8')).toBeNull();
  });
});

describe('receiver reachability plan', () => {
  const config: ReceiverStackConfig = {
    host_alias: RECEIVER_HOST_ALIAS,
    gateway_address: 'fdc4:f303:9324::254',
    allow_cidrs: ['127.0.0.1/32', 'fdc4:f303:9324::254/128'],
    smtp_port: 52525,
    smtp_from: 'argos-e2e@example.test',
  };

  it('e2e:stack on Docker Desktop: the host-gateway alias for webhooks, the configured SMTP port', () => {
    expect(receiverPlan(config, {}, 'win32')).toEqual({
      webhookHost: RECEIVER_HOST_ALIAS,
      webhookSkip: null,
      smtpPort: 52525,
      smtpSkip: null,
    });
    expect(receiverPlan(config, {}, 'darwin').webhookHost).toBe(RECEIVER_HOST_ALIAS);
  });

  it('e2e:docker: webhooks on the shared loopback, mail skipped with its reason', () => {
    const plan = receiverPlan(config, { ARGOS_E2E_LINUX_IMAGE: 'img@sha256:x' }, 'linux');
    expect(plan.webhookHost).toBe('127.0.0.1');
    expect(plan.webhookSkip).toBeNull();
    expect(plan.smtpPort).toBeNull();
    expect(plan.smtpSkip).toMatch(/^e2e:docker: /);
    const noLoopback = receiverPlan(
      { ...config, allow_cidrs: ['fdc4:f303:9324::254/128'] },
      { ARGOS_E2E_LINUX_IMAGE: 'img' },
      'linux',
    );
    expect(noLoopback.webhookHost).toBeNull();
    expect(noLoopback.webhookSkip).toContain('127.0.0.1/32');
  });

  it('skips with a reason on Linux Docker Engine, without receivers.json, or without the gateway', () => {
    const linux = receiverPlan(config, {}, 'linux');
    expect(linux.webhookHost).toBeNull();
    expect(linux.smtpPort).toBeNull();
    expect(linux.webhookSkip).toContain('Linux Docker Engine');
    const none = receiverPlan(null, {}, 'win32');
    expect(none.webhookSkip).toContain('receivers.json');
    expect(none.smtpSkip).toContain('receivers.json');
    const noGateway = receiverPlan(
      { ...config, gateway_address: null, allow_cidrs: ['127.0.0.1/32'] },
      {},
      'win32',
    );
    expect(noGateway.webhookHost).toBeNull();
    expect(noGateway.webhookSkip).toContain('host gateway');
    // Mail needs no allow-list entry: the alias resolves in the server container anyway.
    expect(noGateway.smtpPort).toBe(52525);
  });

  it('writes and reads receivers.json of a run, and refuses a broken one', () => {
    const run = `unit-receivers-${String(process.pid)}`;
    runs.push(run);
    mkdirSync(runDir(run), { recursive: true });
    expect(readReceiverConfig(run)).toBeNull();
    writeReceiverConfig(run, config);
    expect(readReceiverConfig(run)).toEqual(config);
    writeFileSync(receiverConfigFile(run), '{"host_alias": 1}', 'utf8');
    expect(() => readReceiverConfig(run)).toThrow(/not a receiver configuration/);
  });
});
