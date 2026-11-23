// SPDX-License-Identifier: Apache-2.0
//
// The TCP proxy of the collection outage seed (e2e/stack/seed/grpcProxy.ts) forwards both ways,
// holds everything while frozen (also new connections), delivers it in order on thaw, forwards a
// close, and cuts a connection that holds too much.
import { createConnection, createServer, type Server, type Socket } from 'node:net';

import { afterEach, describe, expect, it } from 'vitest';

import { GrpcProxy } from './grpcProxy';

interface Upstream {
  readonly server: Server;
  readonly port: number;
  readonly sockets: Socket[];
  /** Everything received per accepted socket. */
  readonly received: string[];
}

async function upstream(): Promise<Upstream> {
  const sockets: Socket[] = [];
  const received: string[] = [];
  const server = createServer((socket) => {
    const index = sockets.length;
    sockets.push(socket);
    received.push('');
    socket.on('data', (chunk: Buffer) => {
      received[index] = (received[index] ?? '') + chunk.toString('utf8');
    });
    socket.on('error', () => undefined);
  });
  await new Promise<void>((resolve) => {
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  if (address === null || typeof address === 'string') {
    throw new Error('no address');
  }
  return { server, port: address.port, sockets, received };
}

interface Client {
  readonly socket: Socket;
  text(): string;
  closed(): boolean;
}

async function client(port: number): Promise<Client> {
  let text = '';
  let closed = false;
  const socket = createConnection({ port, host: '127.0.0.1' });
  socket.on('data', (chunk: Buffer) => {
    text += chunk.toString('utf8');
  });
  socket.on('close', () => {
    closed = true;
  });
  socket.on('error', () => undefined);
  await new Promise<void>((resolve) => socket.once('connect', resolve));
  return { socket, text: () => text, closed: () => closed };
}

async function until(check: () => boolean, what: string, timeoutMs = 3000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!check()) {
    if (Date.now() > deadline) {
      throw new Error(`timed out waiting for ${what}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 150));

const cleanup: (() => Promise<void> | void)[] = [];
afterEach(async () => {
  for (const step of cleanup.splice(0).reverse()) {
    await step();
  }
});

async function setUp(
  maxHeldBytes?: number,
): Promise<{ up: Upstream; proxy: GrpcProxy; port: number }> {
  const up = await upstream();
  cleanup.push(
    () =>
      new Promise<void>((resolve) => {
        for (const s of up.sockets) {
          s.destroy();
        }
        up.server.close(() => {
          resolve();
        });
      }),
  );
  const proxy = new GrpcProxy({ targetPort: up.port, maxHeldBytes });
  const port = await proxy.start();
  cleanup.push(() => proxy.close());
  return { up, proxy, port };
}

describe('collection outage proxy (WO-22, 05 §2.1-10)', () => {
  it('forwards bytes both ways', async () => {
    const { up, proxy, port } = await setUp();
    const c = await client(port);
    c.socket.write('hello');
    await until(() => up.received[0] === 'hello', 'upstream receives hello');
    up.sockets[0]?.write('world');
    await until(() => c.text() === 'world', 'client receives world');
    expect(proxy.stats()).toMatchObject({ accepted: 1, open: 1, up: 5, down: 5 });
  });

  it('holds both directions while frozen and delivers them in order on thaw', async () => {
    const { up, proxy, port } = await setUp();
    const c = await client(port);
    c.socket.write('a');
    await until(() => up.received[0] === 'a', 'first byte');
    proxy.freeze();
    c.socket.write('b');
    c.socket.write('c');
    up.sockets[0]?.write('x');
    await settle();
    expect(up.received[0]).toBe('a');
    expect(c.text()).toBe('');
    expect(c.closed()).toBe(false);
    proxy.thaw();
    await until(() => up.received[0] === 'abc', 'held bytes after thaw');
    await until(() => c.text() === 'x', 'held answer after thaw');
    c.socket.write('d');
    await until(() => up.received[0] === 'abcd', 'forwarding again');
  });

  it('does not connect a connection accepted while frozen until the thaw', async () => {
    const { up, proxy, port } = await setUp();
    proxy.freeze();
    const c = await client(port);
    c.socket.write('hello');
    await settle();
    expect(up.sockets).toHaveLength(0);
    proxy.thaw();
    await until(() => up.received[0] === 'hello', 'the waiting connection connects');
    up.sockets[0]?.write('ok');
    await until(() => c.text() === 'ok', 'answer');
  });

  it('forwards a close of the server to the agent, also while frozen', async () => {
    const { up, proxy, port } = await setUp();
    const c = await client(port);
    c.socket.write('x');
    await until(() => up.sockets.length === 1, 'upstream connection');
    proxy.freeze();
    up.sockets[0]?.destroy();
    await until(() => c.closed(), 'client closed');
    expect(proxy.stats().open).toBe(0);
  });

  it('cuts a connection that holds more than the limit while frozen', async () => {
    const { up, proxy, port } = await setUp(1024);
    const c = await client(port);
    await until(() => up.sockets.length === 1, 'upstream connection');
    proxy.freeze();
    c.socket.write('y'.repeat(4096));
    await until(() => c.closed(), 'client cut');
    expect(proxy.stats().open).toBe(0);
  });
});
