// SPDX-License-Identifier: Apache-2.0
//
// A TCP proxy between the seeded agent and the stack's gRPC port (WO-22, docs/05 §2.1-10, ADR-0010
// C-205 (2)): the traffic seed points the agent at it, so the setup can cut the collection path
// without stopping the agent process. It forwards bytes only (TLS or plaintext h2 pass through
// unchanged) with Node's `net`, no dependency.
//
// freeze() stops forwarding in both directions: open connections stay open but nothing they send
// reaches the other side (bytes are held, at most `maxHeldBytes` per direction, beyond that the
// connection is cut), and connections accepted while frozen wait unconnected. To the server the
// agent goes silent (no heartbeat: the instance becomes STALE after `stale-after`); to the agent
// nothing happens. thaw() connects the waiting connections, delivers the held bytes and forwards
// again; a connection whose other side ended meanwhile is closed. A close of one side is always
// forwarded to the other.
import { createConnection, createServer, type Server, type Socket } from 'node:net';

export interface GrpcProxyOptions {
  /** The upstream gRPC port on 127.0.0.1. */
  readonly targetPort: number;
  readonly targetHost?: string;
  /** Bytes held per direction while frozen before the connection is cut (default 8 MiB). */
  readonly maxHeldBytes?: number;
}

export interface GrpcProxyStats {
  /** Connections accepted so far. */
  readonly accepted: number;
  /** Connections open now. */
  readonly open: number;
  /** Bytes forwarded agent -> server and server -> agent. */
  readonly up: number;
  readonly down: number;
}

/** One direction of a connection: reads `from`, writes `to`, holds while frozen. */
class Pipe {
  private held: Buffer[] = [];
  private heldBytes = 0;
  private ended = false;
  bytes = 0;
  private readonly from: Socket;
  private readonly to: () => Socket | null;
  private readonly frozen: () => boolean;
  private readonly maxHeld: number;
  private readonly cut: () => void;

  constructor(
    from: Socket,
    to: () => Socket | null,
    frozen: () => boolean,
    maxHeld: number,
    cut: () => void,
  ) {
    this.from = from;
    this.to = to;
    this.frozen = frozen;
    this.maxHeld = maxHeld;
    this.cut = cut;
    from.on('data', (chunk: Buffer) => {
      this.onData(chunk);
    });
    from.on('end', () => {
      this.ended = true;
      this.flush();
    });
  }

  private onData(chunk: Buffer): void {
    const to = this.to();
    if (this.frozen() || to === null) {
      this.held.push(chunk);
      this.heldBytes += chunk.length;
      if (this.heldBytes > this.maxHeld) {
        this.cut();
      }
      return;
    }
    this.write(to, chunk);
  }

  private write(to: Socket, chunk: Buffer): void {
    this.bytes += chunk.length;
    if (!to.write(chunk)) {
      this.from.pause();
      to.once('drain', () => {
        if (!this.frozen()) {
          this.from.resume();
        }
      });
    }
  }

  /** Delivers the held bytes (after a thaw or once the upstream socket is connected). */
  flush(): void {
    const to = this.to();
    if (this.frozen() || to === null) {
      return;
    }
    const held = this.held;
    this.held = [];
    this.heldBytes = 0;
    for (const chunk of held) {
      this.write(to, chunk);
    }
    if (this.ended) {
      to.end();
    }
  }
}

interface ProxiedConnection {
  thaw(): void;
  destroy(): void;
  /** Bytes forwarded so far: [agent -> server, server -> agent]. */
  counts(): [number, number];
}

export class GrpcProxy {
  private server: Server | null = null;
  private frozenNow = false;
  private accepted = 0;
  private readonly connections = new Set<ProxiedConnection>();
  private up = 0;
  private down = 0;
  private readonly host: string;
  private readonly maxHeld: number;
  private readonly options: GrpcProxyOptions;

  constructor(options: GrpcProxyOptions) {
    this.options = options;
    this.host = options.targetHost ?? '127.0.0.1';
    this.maxHeld = options.maxHeldBytes ?? 8 * 1024 * 1024;
  }

  /** Listens on 127.0.0.1 (a random port unless `port` is given); resolves with the port. */
  async start(port = 0): Promise<number> {
    const server = createServer((client) => {
      this.accept(client);
    });
    this.server = server;
    await new Promise<void>((resolve, reject) => {
      server.once('error', reject);
      server.listen(port, '127.0.0.1', () => {
        server.off('error', reject);
        resolve();
      });
    });
    const address = server.address();
    if (address === null || typeof address === 'string') {
      throw new Error('grpc proxy: no TCP address');
    }
    return address.port;
  }

  get frozen(): boolean {
    return this.frozenNow;
  }

  /** Stops forwarding (see the file comment). */
  freeze(): void {
    this.frozenNow = true;
  }

  /** Forwards again: connects the waiting connections and delivers what was held. */
  thaw(): void {
    this.frozenNow = false;
    for (const c of [...this.connections]) {
      c.thaw();
    }
  }

  stats(): GrpcProxyStats {
    let up = this.up;
    let down = this.down;
    for (const c of this.connections) {
      const [u, d] = c.counts();
      up += u;
      down += d;
    }
    return { accepted: this.accepted, open: this.connections.size, up, down };
  }

  /** Closes the listener and every connection. */
  async close(): Promise<void> {
    for (const c of [...this.connections]) {
      c.destroy();
    }
    const server = this.server;
    this.server = null;
    if (server !== null) {
      await new Promise<void>((resolve) => {
        server.close(() => {
          resolve();
        });
      });
    }
  }

  private accept(client: Socket): void {
    this.accepted += 1;
    client.setNoDelay(true);
    let upstream: Socket | null = null;
    let closed = false;
    const entry: ProxiedConnection = {
      thaw: () => {
        connect();
        client.resume();
        upstream?.resume();
        toServer.flush();
        toClient?.flush();
      },
      destroy: () => {
        finish();
      },
      counts: () => [toServer.bytes, toClient?.bytes ?? 0],
    };
    const finish = (): void => {
      if (closed) {
        return;
      }
      closed = true;
      this.up += toServer.bytes;
      this.down += toClient?.bytes ?? 0;
      this.connections.delete(entry);
      client.destroy();
      upstream?.destroy();
    };
    const frozen = (): boolean => this.frozenNow;
    const toServer = new Pipe(client, () => upstream, frozen, this.maxHeld, finish);
    let toClient: Pipe | null = null;
    const connect = (): void => {
      if (upstream !== null || closed) {
        return;
      }
      const socket = createConnection({ port: this.options.targetPort, host: this.host });
      socket.setNoDelay(true);
      upstream = socket;
      toClient = new Pipe(socket, () => client, frozen, this.maxHeld, finish);
      socket.on('connect', () => {
        toServer.flush();
      });
      socket.on('close', finish);
      socket.on('error', finish);
    };
    client.on('close', finish);
    client.on('error', finish);
    this.connections.add(entry);
    if (!this.frozenNow) {
      connect();
    }
  }
}
