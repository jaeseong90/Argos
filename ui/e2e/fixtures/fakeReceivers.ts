// SPDX-License-Identifier: Apache-2.0
//
// Fake notification receivers of the alert channel E2E tests (WO-38, docs/05 §2.4-5, docs/16 §8):
// a webhook / Slack receiver (node:http) and a plain SMTP receiver (node:net), both dependency free,
// on 127.0.0.1 and a random port, recording what the server sent. Nothing leaves the machine: the
// stack server may deliver only to these (argos.alert.webhook.allow-cidrs, argos.alert.smtp.host;
// e2e/stack/stack.ts receiverArguments).
//
// - The HTTP receiver answers every POST with a configurable status and records the method, path,
//   content type, body (at most 64 KiB) and whether the X-Argos-Signature header is present and
//   has the documented form `t=<unix seconds>,v1=<64 hex>[,v1=...]` (16 §8.4). The header value
//   itself (an HMAC) is not kept: the tests check presence and format only.
// - The SMTP receiver speaks just enough RFC 5321 for the server's SmtpClient: greeting, EHLO
//   without STARTTLS or AUTH (the test stack sets starttls=optional with insecure=true), MAIL,
//   RCPT, DATA (dot-unstuffed, at most 1 MiB), RSET, NOOP, QUIT. It records the envelope and the
//   message headers (the Subject decoded from RFC 2047 base64).
//
// Reachability (receiverPlan): the server runs in a container, the receivers where Playwright runs.
// - e2e:stack on Windows or macOS (Docker Desktop): the server reaches the host's 127.0.0.1 through
//   the host-gateway alias `argos-e2e-receivers` (--add-host, stack.ts), whose address the stack
//   allows (receivers.json records it). Webhook URLs and the SMTP host use that alias.
// - e2e:docker: Playwright shares the server's network namespace (cli.ts --network container:...),
//   so a webhook receiver on 127.0.0.1 is the server's 127.0.0.1 (allowed by 127.0.0.1/32); the
//   SMTP host of the server is the host gateway, not this container: e-mail delivery is skipped.
// - Linux Docker Engine on the host: the host gateway does not forward to the host's loopback, so
//   deliveries are skipped; a reused stack without receivers.json (started before WO-38) as well.
// Each skip names its reason; the destination validation (422) never needs a receiver.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { createServer as createTcpServer, type Socket } from 'node:net';
import { join } from 'node:path';

import { runDir } from '../stack/paths.ts';

/** Bind address of every receiver. */
export const RECEIVER_BIND = '127.0.0.1';

/** Host name of the receivers as the stack server sees them (host-gateway alias, stack.ts). */
export const RECEIVER_HOST_ALIAS = 'argos-e2e-receivers';

/** Largest recorded HTTP body (bytes); the rest is drained and dropped. */
export const MAX_HTTP_BODY = 64 * 1024;

/** Largest accepted SMTP message (bytes). */
export const MAX_SMTP_MESSAGE = 1024 * 1024;

const MAX_SMTP_LINE = 4096;

// ---------------------------------------------------------------------------------- signature

/** Presence and form of the X-Argos-Signature header (16 §8.4), never its value. */
export interface SignatureShape {
  readonly present: boolean;
  /** `t=<digits>,v1=<64 hex>[,v1=<64 hex>]...` */
  readonly wellFormed: boolean;
  /** Number of v1= values (one per valid signing key; two during a key rotation). */
  readonly v1Count: number;
  /** The t= value (unix seconds), or null. */
  readonly t: number | null;
}

const SIGNATURE = /^t=(\d{1,12})((?:,v1=[0-9a-f]{64})+)$/;

/** The shape of a signature header value (undefined or absent: not present). */
export function signatureShape(value: string | readonly string[] | undefined): SignatureShape {
  if (value === undefined) {
    return { present: false, wellFormed: false, v1Count: 0, t: null };
  }
  const text = typeof value === 'string' ? value : value.join(',');
  const match = SIGNATURE.exec(text.trim());
  if (match === null) {
    return { present: true, wellFormed: false, v1Count: 0, t: null };
  }
  return {
    present: true,
    wellFormed: true,
    v1Count: (match[2] ?? '').split(',v1=').length - 1,
    t: Number(match[1]),
  };
}

// ------------------------------------------------------------------------------ HTTP receiver

export interface RecordedRequest {
  readonly method: string;
  /** Path and query of the request line. */
  readonly path: string;
  readonly contentType: string | null;
  readonly signature: SignatureShape;
  /** UTF-8 body, at most MAX_HTTP_BODY bytes. */
  readonly body: string;
  readonly truncated: boolean;
  /** The status the receiver answered. */
  readonly status: number;
  readonly receivedAt: number;
}

export interface HttpReceiver {
  readonly port: number;
  /** http://<host>:<port><path>; host defaults to the bind address. */
  url(path: string, host?: string): string;
  /** Requests recorded so far, oldest first. */
  requests(): readonly RecordedRequest[];
  /** Resolves with the first recorded request (earlier ones included) that matches. */
  waitForRequest(
    predicate: (request: RecordedRequest) => boolean,
    timeoutMs?: number,
  ): Promise<RecordedRequest>;
  /** The status of the next answers (default 200). */
  setStatus(status: number): void;
  close(): Promise<void>;
}

export interface ReceiverOptions {
  readonly bindHost?: string;
  /** 0 (default): a random free port. */
  readonly port?: number;
}

/** A waiter list shared by both receivers. */
class Waiters<T> {
  private readonly items: T[] = [];
  private readonly waiting: { predicate: (item: T) => boolean; resolve: (item: T) => void }[] = [];

  all(): readonly T[] {
    return [...this.items];
  }

  push(item: T): void {
    this.items.push(item);
    for (const waiter of [...this.waiting]) {
      if (waiter.predicate(item)) {
        this.waiting.splice(this.waiting.indexOf(waiter), 1);
        waiter.resolve(item);
      }
    }
  }

  wait(predicate: (item: T) => boolean, timeoutMs: number, what: string): Promise<T> {
    const found = this.items.find(predicate);
    if (found !== undefined) {
      return Promise.resolve(found);
    }
    return new Promise<T>((resolve, reject) => {
      const waiter = {
        predicate,
        resolve: (item: T) => {
          clearTimeout(timer);
          resolve(item);
        },
      };
      const timer = setTimeout(() => {
        this.waiting.splice(this.waiting.indexOf(waiter), 1);
        reject(
          new Error(
            `${what}: nothing matching within ${String(timeoutMs)} ms (${String(this.items.length)} recorded)`,
          ),
        );
      }, timeoutMs);
      this.waiting.push(waiter);
    });
  }
}

/** Starts the webhook / Slack receiver. */
export function startHttpReceiver(options: ReceiverOptions = {}): Promise<HttpReceiver> {
  const bindHost = options.bindHost ?? RECEIVER_BIND;
  const recorded = new Waiters<RecordedRequest>();
  let status = 200;
  const server = createServer((request: IncomingMessage, response: ServerResponse) => {
    const chunks: Buffer[] = [];
    let size = 0;
    let truncated = false;
    request.on('data', (chunk: Buffer) => {
      if (size < MAX_HTTP_BODY) {
        const room = MAX_HTTP_BODY - size;
        chunks.push(chunk.length > room ? chunk.subarray(0, room) : chunk);
        truncated ||= chunk.length > room;
      } else {
        truncated = true;
      }
      size += chunk.length;
    });
    request.on('end', () => {
      const answered = status;
      recorded.push({
        method: request.method ?? '',
        path: request.url ?? '',
        contentType: request.headers['content-type'] ?? null,
        signature: signatureShape(request.headers['x-argos-signature']),
        body: Buffer.concat(chunks).toString('utf8'),
        truncated,
        status: answered,
        receivedAt: Date.now(),
      });
      response.writeHead(answered, { 'Content-Type': 'text/plain', Connection: 'close' });
      response.end(answered < 300 ? 'ok' : 'refused');
    });
    request.on('error', () => {
      response.destroy();
    });
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(options.port ?? 0, bindHost, () => {
      server.off('error', reject);
      const address = server.address();
      if (address === null || typeof address === 'string') {
        reject(new Error('the HTTP receiver has no TCP address'));
        return;
      }
      resolve({
        port: address.port,
        url: (path, host = bindHost) =>
          `http://${host.includes(':') ? `[${host}]` : host}:${String(address.port)}${path}`,
        requests: () => recorded.all(),
        waitForRequest: (predicate, timeoutMs = 30_000) =>
          recorded.wait(predicate, timeoutMs, `HTTP receiver :${String(address.port)}`),
        setStatus: (next) => {
          status = next;
        },
        close: () =>
          new Promise<void>((done) => {
            server.closeAllConnections();
            server.close(() => {
              done();
            });
          }),
      });
    });
  });
}

// ------------------------------------------------------------------------------ SMTP receiver

export interface SmtpMessage {
  readonly heloName: string | null;
  readonly from: string;
  readonly recipients: readonly string[];
  /** Header name (lower case) → value of the message header block. */
  readonly headers: Readonly<Record<string, string>>;
  /** The Subject header, RFC 2047 =?UTF-8?B?...?= decoded. */
  readonly subject: string | null;
  readonly size: number;
  readonly receivedAt: number;
}

export interface SmtpReceiver {
  readonly port: number;
  messages(): readonly SmtpMessage[];
  waitForMessage(
    predicate: (message: SmtpMessage) => boolean,
    timeoutMs?: number,
  ): Promise<SmtpMessage>;
  close(): Promise<void>;
}

/** Decodes RFC 2047 encoded words of the UTF-8 B (base64) and Q forms. */
export function decodeHeader(value: string): string {
  return value.replace(/=\?UTF-8\?([BbQq])\?([^?]*)\?=/g, (_, encoding: string, text: string) => {
    if (encoding.toUpperCase() === 'B') {
      return Buffer.from(text, 'base64').toString('utf8');
    }
    const bytes = text
      .replace(/_/g, ' ')
      .replace(/=([0-9A-Fa-f]{2})/g, (_m, hex: string) => String.fromCharCode(parseInt(hex, 16)));
    return Buffer.from(bytes, 'latin1').toString('utf8');
  });
}

/** The header block of a message (folded lines joined), names lower case. */
export function parseHeaders(message: string): Record<string, string> {
  const end = message.search(/\r?\n\r?\n/);
  const block = end < 0 ? message : message.slice(0, end);
  const headers: Record<string, string> = {};
  let last: string | null = null;
  for (const line of block.split(/\r?\n/)) {
    if (/^[ \t]/.test(line) && last !== null) {
      headers[last] = `${headers[last] ?? ''} ${line.trim()}`;
      continue;
    }
    const colon = line.indexOf(':');
    if (colon <= 0) {
      continue;
    }
    last = line.slice(0, colon).trim().toLowerCase();
    headers[last] = line.slice(colon + 1).trim();
  }
  return headers;
}

/** The address of `MAIL FROM:<a>` / `RCPT TO:<a>` (parameters after it ignored), or null. */
export function envelopeAddress(argument: string): string | null {
  const match = /^\s*<([^<>]*)>/.exec(argument);
  return match === null ? null : (match[1] ?? '');
}

/** Starts the plain SMTP receiver (no STARTTLS, no AUTH). */
export function startSmtpReceiver(
  options: ReceiverOptions & { readonly hostname?: string } = {},
): Promise<SmtpReceiver> {
  const bindHost = options.bindHost ?? RECEIVER_BIND;
  const hostname = options.hostname ?? RECEIVER_HOST_ALIAS;
  const recorded = new Waiters<SmtpMessage>();
  const sockets = new Set<Socket>();
  const server = createTcpServer((socket) => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    socket.on('error', () => {
      socket.destroy();
    });
    let buffer = '';
    let helo: string | null = null;
    let from: string | null = null;
    let recipients: string[] = [];
    let data: string[] | null = null;
    let dataSize = 0;
    let tooLarge = false;
    const reply = (text: string): void => {
      socket.write(`${text}\r\n`);
    };
    const reset = (): void => {
      from = null;
      recipients = [];
      data = null;
      dataSize = 0;
      tooLarge = false;
    };
    const finishData = (): void => {
      const lines = data ?? [];
      data = null;
      if (tooLarge) {
        reset();
        reply('552 message too large');
        return;
      }
      const message = lines.join('\r\n');
      const headers = parseHeaders(message);
      recorded.push({
        heloName: helo,
        from: from ?? '',
        recipients: [...recipients],
        headers,
        subject: headers.subject === undefined ? null : decodeHeader(headers.subject),
        size: dataSize,
        receivedAt: Date.now(),
      });
      reset();
      reply('250 2.0.0 queued');
    };
    const command = (line: string): void => {
      const verb = (/^\S+/.exec(line)?.[0] ?? '').toUpperCase();
      const rest = line.slice(verb.length).trim();
      switch (verb) {
        case 'EHLO':
          helo = rest;
          reset();
          reply(`250-${hostname} greets ${rest}`);
          reply('250-8BITMIME');
          reply(`250 SIZE ${String(MAX_SMTP_MESSAGE)}`);
          return;
        case 'HELO':
          helo = rest;
          reset();
          reply(`250 ${hostname}`);
          return;
        case 'MAIL': {
          const address = /^FROM:/i.test(rest) ? envelopeAddress(rest.slice(5)) : null;
          if (address === null) {
            reply('501 syntax: MAIL FROM:<address>');
            return;
          }
          reset();
          from = address;
          reply('250 2.1.0 ok');
          return;
        }
        case 'RCPT': {
          const address = /^TO:/i.test(rest) ? envelopeAddress(rest.slice(3)) : null;
          if (from === null) {
            reply('503 MAIL first');
            return;
          }
          if (address === null || address === '') {
            reply('501 syntax: RCPT TO:<address>');
            return;
          }
          recipients.push(address);
          reply('250 2.1.5 ok');
          return;
        }
        case 'DATA':
          if (from === null || recipients.length === 0) {
            reply('503 MAIL and RCPT first');
            return;
          }
          data = [];
          reply('354 end with <CRLF>.<CRLF>');
          return;
        case 'RSET':
          reset();
          reply('250 2.0.0 ok');
          return;
        case 'NOOP':
          reply('250 2.0.0 ok');
          return;
        case 'QUIT':
          reply('221 2.0.0 bye');
          socket.end();
          return;
        case 'STARTTLS':
          reply('454 4.7.0 TLS not available');
          return;
        default:
          reply('502 5.5.2 command not implemented');
      }
    };
    socket.on('data', (chunk: Buffer) => {
      buffer += chunk.toString('latin1');
      for (;;) {
        const at = buffer.indexOf('\n');
        if (at < 0) {
          if (buffer.length > MAX_SMTP_LINE && data === null) {
            reply('500 line too long');
            socket.end();
            buffer = '';
          }
          return;
        }
        const line = buffer.slice(0, at).replace(/\r$/, '');
        buffer = buffer.slice(at + 1);
        if (data !== null) {
          if (line === '.') {
            finishData();
            continue;
          }
          dataSize += line.length + 2;
          if (dataSize > MAX_SMTP_MESSAGE) {
            tooLarge = true;
            continue;
          }
          data.push(line.startsWith('.') ? line.slice(1) : line);
          continue;
        }
        command(line);
      }
    });
    reply(`220 ${hostname} ESMTP fake receiver`);
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(options.port ?? 0, bindHost, () => {
      server.off('error', reject);
      const address = server.address();
      if (address === null || typeof address === 'string') {
        reject(new Error('the SMTP receiver has no TCP address'));
        return;
      }
      resolve({
        port: address.port,
        messages: () => recorded.all(),
        waitForMessage: (predicate, timeoutMs = 30_000) =>
          recorded.wait(predicate, timeoutMs, `SMTP receiver :${String(address.port)}`),
        close: () =>
          new Promise<void>((done) => {
            for (const socket of sockets) {
              socket.destroy();
            }
            server.close(() => {
              done();
            });
          }),
      });
    });
  });
}

// ------------------------------------------------------------------- stack configuration

/** The receiver settings the stack started its server with (ui/e2e/.stack/<run>/receivers.json). */
export interface ReceiverStackConfig {
  /** Host name of the receivers in the server container (--add-host <alias>:host-gateway). */
  readonly host_alias: string;
  /** The address the alias resolved to at stack start, or null when the probe failed. */
  readonly gateway_address: string | null;
  /** argos.alert.webhook.allow-cidrs of the server. */
  readonly allow_cidrs: readonly string[];
  /** argos.alert.smtp.port of the server (the SMTP receiver binds it on 127.0.0.1). */
  readonly smtp_port: number;
  /** argos.alert.smtp.from of the server. */
  readonly smtp_from: string;
}

export const RECEIVERS_FILE = 'receivers.json';

export function receiverConfigFile(runId: string): string {
  return join(runDir(runId), RECEIVERS_FILE);
}

export function writeReceiverConfig(runId: string, config: ReceiverStackConfig): void {
  writeFileSync(receiverConfigFile(runId), `${JSON.stringify(config, null, 2)}\n`, 'utf8');
}

function isConfig(value: unknown): value is ReceiverStackConfig {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const v = value as Record<string, unknown>;
  return (
    typeof v.host_alias === 'string' &&
    (v.gateway_address === null || typeof v.gateway_address === 'string') &&
    Array.isArray(v.allow_cidrs) &&
    v.allow_cidrs.every((c) => typeof c === 'string') &&
    typeof v.smtp_port === 'number' &&
    Number.isInteger(v.smtp_port) &&
    typeof v.smtp_from === 'string'
  );
}

/** The receiver settings of a run, or null (no file: a stack started before WO-38). */
export function readReceiverConfig(runId: string): ReceiverStackConfig | null {
  const file = receiverConfigFile(runId);
  if (!existsSync(file)) {
    return null;
  }
  const parsed: unknown = JSON.parse(readFileSync(file, 'utf8'));
  if (!isConfig(parsed)) {
    throw new Error(`${file} is not a receiver configuration`);
  }
  return parsed;
}

/** Where a test sends and whether a delivery can reach the receivers (file comment). */
export interface ReceiverPlan {
  /** Host of webhook and Slack URLs as the server must call it, or null (skip, webhookSkip). */
  readonly webhookHost: string | null;
  readonly webhookSkip: string | null;
  /** Port the SMTP receiver binds (the server's smtp.port), or null (skip, smtpSkip). */
  readonly smtpPort: number | null;
  readonly smtpSkip: string | null;
}

/**
 * Decides where the receivers are reachable from the stack server: `config` from receivers.json,
 * `env` and `platform` of the Playwright process (e2e:docker sets ARGOS_E2E_LINUX_IMAGE).
 */
export function receiverPlan(
  config: ReceiverStackConfig | null,
  env: Readonly<Record<string, string | undefined>>,
  platform: NodeJS.Platform,
): ReceiverPlan {
  if (config === null) {
    const reason =
      'the stack has no fake receiver configuration (receivers.json): a reused stack started before WO-38; restart it';
    return { webhookHost: null, webhookSkip: reason, smtpPort: null, smtpSkip: reason };
  }
  const inDockerImage =
    platform === 'linux' &&
    env.ARGOS_E2E_LINUX_IMAGE !== undefined &&
    env.ARGOS_E2E_LINUX_IMAGE !== '';
  if (inDockerImage) {
    const loopbackAllowed = config.allow_cidrs.includes('127.0.0.1/32');
    return {
      webhookHost: loopbackAllowed ? RECEIVER_BIND : null,
      webhookSkip: loopbackAllowed
        ? null
        : 'e2e:docker: the server does not allow 127.0.0.1/32 as a webhook destination',
      smtpPort: null,
      smtpSkip:
        'e2e:docker: the server delivers mail to the Docker host gateway, not to this Playwright container (the webhook receiver shares the server network namespace instead)',
    };
  }
  if (platform === 'linux') {
    const reason =
      'Linux Docker Engine: the host gateway does not forward to the host 127.0.0.1 where the receivers listen (run e2e:docker, or Docker Desktop)';
    return { webhookHost: null, webhookSkip: reason, smtpPort: null, smtpSkip: reason };
  }
  const gateway = config.gateway_address;
  const allowed = gateway !== null && config.allow_cidrs.some((c) => c.startsWith(`${gateway}/`));
  return {
    webhookHost: allowed ? config.host_alias : null,
    webhookSkip: allowed
      ? null
      : 'the Docker host gateway address could not be resolved at stack start, so the server does not allow it as a webhook destination',
    smtpPort: config.smtp_port,
    smtpSkip: null,
  };
}
