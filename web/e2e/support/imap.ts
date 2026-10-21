import { connect, Socket } from 'node:net';

/** GreenMail of the e2e stack as seen from the host (deploy/docker-compose.e2e.yml). */
export const GREENMAIL_IMAP = {
  host: '127.0.0.1',
  port: Number(process.env['E2E_IMAP_PORT'] ?? 18143),
};

/** GreenMail as seen from the server container: the settings of a mail account on it. */
export const GREENMAIL_SERVERS = {
  imap: { host: 'greenmail', port: 3143, security: 'NONE' },
  smtp: { host: 'greenmail', port: 3025, security: 'NONE' },
} as const;

/**
 * A minimal IMAP client (plain text, no literals in responses) for preparing and checking mailboxes
 * on GreenMail: login, CREATE, APPEND and SEARCH. GreenMail accepts any login and creates its mailbox.
 */
export class Imap {
  private buffer = '';
  private waiters: (() => void)[] = [];
  private tag = 0;

  private constructor(private readonly socket: Socket) {
    socket.setEncoding('utf8');
    socket.on('data', (chunk: string) => {
      this.buffer += chunk;
      this.waiters.splice(0).forEach((wake) => wake());
    });
  }

  /** Connects and logs in; retries while GreenMail is still starting. */
  static async login(user: string, password: string, timeoutMs = 30_000): Promise<Imap> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      try {
        const imap = new Imap(await open());
        await imap.line(); // greeting
        await imap.command(`LOGIN ${quote(user)} ${quote(password)}`);
        return imap;
      } catch (error) {
        if (Date.now() > deadline) throw error;
        await new Promise((resolve) => setTimeout(resolve, 500));
      }
    }
  }

  create(folder: string): Promise<string[]> {
    return this.command(`CREATE ${quote(folder)}`);
  }

  /** Adds a plain-text message to a folder, unread. */
  async append(folder: string, mail: { from: string; to: string; subject: string; text: string }) {
    const message = [
      `From: ${mail.from}`,
      `To: ${mail.to}`,
      `Subject: ${mail.subject}`,
      `Date: ${new Date().toUTCString()}`,
      `Message-ID: <${Date.now()}.${Math.random().toString(36).slice(2)}@cockpit.test>`,
      'MIME-Version: 1.0',
      'Content-Type: text/plain; charset=utf-8',
      '',
      mail.text,
      '',
    ].join('\r\n');
    const tag = this.nextTag();
    this.socket.write(`${tag} APPEND ${quote(folder)} {${Buffer.byteLength(message)}}\r\n`);
    const ready = await this.line();
    if (!ready.startsWith('+')) throw new Error(`APPEND refused: ${ready}`);
    this.socket.write(`${message}\r\n`);
    await this.responses(tag);
  }

  /** The sequence numbers in `folder` matching an IMAP search, e.g. `SEEN SUBJECT "x"`. */
  async search(folder: string, criteria: string): Promise<number[]> {
    await this.command(`EXAMINE ${quote(folder)}`);
    const lines = await this.command(`SEARCH ${criteria}`);
    const result = lines.find((line) => line.startsWith('* SEARCH'));
    return (result ?? '').replace('* SEARCH', '').trim().split(/\s+/).filter(Boolean).map(Number);
  }

  async logout(): Promise<void> {
    try {
      await this.command('LOGOUT');
    } finally {
      this.socket.destroy();
    }
  }

  /** Runs a command; returns its untagged responses, throws unless it ends with OK. */
  async command(command: string): Promise<string[]> {
    const tag = this.nextTag();
    this.socket.write(`${tag} ${command}\r\n`);
    return this.responses(tag);
  }

  private async responses(tag: string): Promise<string[]> {
    const lines: string[] = [];
    for (;;) {
      const line = await this.line();
      if (line.startsWith(`${tag} `)) {
        if (!line.startsWith(`${tag} OK`)) throw new Error(`IMAP: ${line}`);
        return lines;
      }
      lines.push(line);
    }
  }

  private async line(): Promise<string> {
    for (;;) {
      const end = this.buffer.indexOf('\r\n');
      if (end >= 0) {
        const line = this.buffer.slice(0, end);
        this.buffer = this.buffer.slice(end + 2);
        return line;
      }
      if (this.socket.destroyed) throw new Error('IMAP connection closed');
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('IMAP timeout')), 10_000);
        this.waiters.push(() => {
          clearTimeout(timer);
          resolve();
        });
      });
    }
  }

  private nextTag(): string {
    return `t${++this.tag}`;
  }
}

function open(): Promise<Socket> {
  return new Promise((resolve, reject) => {
    const socket = connect(GREENMAIL_IMAP.port, GREENMAIL_IMAP.host);
    socket.once('connect', () => resolve(socket));
    socket.once('error', reject);
  });
}

function quote(value: string): string {
  return `"${value.replace(/["\\]/g, '\\$&')}"`;
}
