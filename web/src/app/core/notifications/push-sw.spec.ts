/// <reference types="node" />
// The push service worker (public/push-sw.js) runs outside Angular; these tests load it into a fake
// worker scope and drive its push and notificationclick handlers.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

// `ng test` runs from the web/ folder.
const source = readFileSync(join(process.cwd(), 'public', 'push-sw.js'), 'utf8');

const SCOPE = 'https://cockpit.example.test/';

interface FakeClient {
  url: string;
  visibilityState: 'visible' | 'hidden';
  focus: ReturnType<typeof vi.fn>;
  navigate: ReturnType<typeof vi.fn>;
}

function worker(lang = 'en', clients: FakeClient[] = []) {
  const listeners: Record<string, (event: unknown) => void> = {};
  const self = {
    location: { href: `${SCOPE}push-sw.js?lang=${lang}` },
    registration: { scope: SCOPE, showNotification: vi.fn(() => Promise.resolve()) },
    clients: {
      matchAll: vi.fn(() => Promise.resolve(clients)),
      openWindow: vi.fn(() => Promise.resolve(null)),
      claim: vi.fn(() => Promise.resolve()),
    },
    addEventListener: (type: string, listener: (event: unknown) => void) =>
      (listeners[type] = listener),
    skipWaiting: vi.fn(),
  };
  new Function('self', source)(self);

  async function dispatch(type: string, event: Record<string, unknown>): Promise<void> {
    let done: Promise<unknown> = Promise.resolve();
    listeners[type]({ ...event, waitUntil: (p: Promise<unknown>) => (done = p) });
    await done;
  }

  return {
    self,
    push: (message: unknown) =>
      dispatch('push', {
        data: message === undefined ? null : { json: () => message },
      }),
    pushRaw: (data: unknown) => dispatch('push', { data }),
    click: (data: unknown) => {
      const notification = { data, close: vi.fn() };
      return dispatch('notificationclick', { notification }).then(() => notification);
    },
    shown: () =>
      self.registration.showNotification.mock.calls as unknown as [
        string,
        NotificationOptions & { data: { route: string } },
      ][],
  };
}

function client(url: string, visibilityState: 'visible' | 'hidden' = 'hidden'): FakeClient {
  return {
    url,
    visibilityState,
    focus: vi.fn(() => Promise.resolve()),
    navigate: vi.fn(() => Promise.resolve()),
  };
}

const reminder = {
  event: 'reminder',
  data: {
    id: 'r1',
    item: {
      type: 'TASK',
      id: 't1',
      available: true,
      title: 'Call the plumber',
      deepLink: '/tasks/t1',
    },
  },
};

describe('push service worker', () => {
  it('shows a reminder with its title, tag and route', async () => {
    const w = worker();

    await w.push(reminder);

    const [title, options] = w.shown()[0];
    expect(title).toBe('Call the plumber');
    expect(options.body).toBe('Reminder from My Cockpit');
    expect(options.tag).toBe('reminder-r1');
    expect(options.data.route).toBe('/tasks/t1');
    expect(options.icon).toBe(`${SCOPE}apple-touch-icon.png`);
  });

  it('speaks German when registered with lang=de', async () => {
    const w = worker('de');

    await w.push({ event: 'reminder', data: { id: 'r2', item: { available: false } } });
    await w.push({ event: 'mail', data: { count: 3, messages: [] } });

    expect(w.shown()[0][0]).toBe('Erinnerung');
    expect(w.shown()[0][1].body).toBe('Erinnerung von My Cockpit');
    expect(w.shown()[1][0]).toBe('3 neue Nachrichten');
  });

  it('shows one new mail with sender and subject, several as a count', async () => {
    const w = worker();

    await w.push({
      event: 'mail',
      data: {
        folderId: 'f1',
        count: 1,
        messages: [{ id: 'm1', from: 'Anna <anna@example.test>', subject: 'Contract' }],
      },
    });
    await w.push({
      event: 'mail',
      data: { folderId: 'f1', count: 2, messages: [{ subject: 'A' }, { subject: 'B' }] },
    });

    expect(w.shown()[0][0]).toBe('Anna <anna@example.test>');
    expect(w.shown()[0][1]).toMatchObject({
      body: 'Contract',
      tag: 'mail-f1',
      data: { route: 'mail/messages/m1' },
    });
    expect(w.shown()[1][0]).toBe('2 new messages');
    expect(w.shown()[1][1]).toMatchObject({ body: 'A\nB', data: { route: 'mail' } });
  });

  it('shows a generic text when the message came without data', async () => {
    const w = worker();

    await w.push({ event: 'reminder' });
    await w.push({ event: 'mail' });

    expect(w.shown().map(([title]) => title)).toEqual(['Reminder', 'New mail']);
  });

  it('ignores unknown events and unreadable messages', async () => {
    const w = worker();

    await w.push({ event: 'sync-finished' });
    await w.push(undefined);
    await w.pushRaw({
      json: () => {
        throw new SyntaxError('not json');
      },
    });

    expect(w.shown()).toHaveLength(0);
  });

  it('leaves the notification to a visible tab', async () => {
    const w = worker('en', [client(`${SCOPE}inbox`, 'visible')]);

    await w.push(reminder);

    expect(w.shown()).toHaveLength(0);
  });

  it('opens the item in a new tab when none is open', async () => {
    const w = worker();

    const notification = await w.click({ route: '/tasks/t1' });

    expect(notification.close).toHaveBeenCalled();
    expect(w.self.clients.openWindow).toHaveBeenCalledWith(`${SCOPE}tasks/t1`);
  });

  it('focuses an open tab and navigates it to the item', async () => {
    const open = client(`${SCOPE}inbox`);
    const w = worker('en', [open]);

    await w.click({ route: 'mail/messages/m1' });

    expect(open.focus).toHaveBeenCalled();
    expect(open.navigate).toHaveBeenCalledWith(`${SCOPE}mail/messages/m1`);
    expect(w.self.clients.openWindow).not.toHaveBeenCalled();
  });

  it('opens a new tab when the open one cannot be navigated', async () => {
    const open = client(`${SCOPE}inbox`);
    open.navigate.mockRejectedValue(new TypeError('not controlled'));
    const w = worker('en', [open]);

    await w.click({ route: 'mail' });

    expect(w.self.clients.openWindow).toHaveBeenCalledWith(`${SCOPE}mail`);
  });

  it('never opens a page outside the app', async () => {
    const w = worker();

    await w.click({ route: 'https://evil.example.test/phish' });
    await w.click({ route: '//evil.example.test/phish' });
    await w.click(undefined);

    const opened = (w.self.clients.openWindow.mock.calls as unknown as [string][]).map(
      ([url]) => url,
    );
    expect(opened).toHaveLength(3);
    expect(opened[0]).toBe(SCOPE);
    for (const url of opened) expect(url.startsWith(SCOPE)).toBe(true);
  });
});
