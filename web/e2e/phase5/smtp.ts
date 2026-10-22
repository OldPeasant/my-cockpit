import { connect, Socket } from 'node:net';

/** GreenMail's SMTP of the e2e stack as seen from the host (deploy/docker-compose.e2e.yml). */
const GREENMAIL_SMTP = {
  host: '127.0.0.1',
  port: Number(process.env['E2E_SMTP_PORT'] ?? 18025),
};

/**
 * Delivers [message] (complete RFC 5322, CRLF line ends) from [from] to [to] through GreenMail's SMTP, the way mail from
 * outside reaches the mailbox (no TLS, no login). Throws unless the server accepts it.
 */
export async function sendSmtp(from: string, to: string, message: string): Promise<void> {
  const socket = await new Promise<Socket>((resolve, reject) => {
    const s = connect(GREENMAIL_SMTP.port, GREENMAIL_SMTP.host);
    s.once('connect', () => resolve(s));
    s.once('error', reject);
  });
  socket.setEncoding('utf8');
  let buffer = '';
  const waiters: (() => void)[] = [];
  socket.on('data', (chunk: string) => {
    buffer += chunk;
    waiters.splice(0).forEach((wake) => wake());
  });
  /** The next complete reply (the last line of a multi-line reply has a space after the code). */
  const reply = async (): Promise<string> => {
    for (;;) {
      const match = /^(\d{3}) [^\r\n]*\r\n/m.exec(buffer);
      if (match) {
        buffer = buffer.slice(match.index + match[0].length);
        return match[0].trim();
      }
      if (socket.destroyed) throw new Error('SMTP connection closed');
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('SMTP timeout')), 10_000);
        waiters.push(() => {
          clearTimeout(timer);
          resolve();
        });
      });
    }
  };
  const expectCode = async (code: string) => {
    const line = await reply();
    if (!line.startsWith(code)) throw new Error(`SMTP: expected ${code}, got ${line}`);
  };
  const command = async (line: string, code: string) => {
    socket.write(`${line}\r\n`);
    await expectCode(code);
  };
  try {
    await expectCode('220');
    await command('EHLO e2e.cockpit.test', '250');
    await command(`MAIL FROM:<${from}>`, '250');
    await command(`RCPT TO:<${to}>`, '250');
    await command('DATA', '354');
    // Dot-stuffing: a line starting with "." gets a second one.
    const body = message.replace(/\r\n\./g, '\r\n..');
    await command(`${body}\r\n.`, '250');
    await command('QUIT', '221');
  } finally {
    socket.destroy();
  }
}
