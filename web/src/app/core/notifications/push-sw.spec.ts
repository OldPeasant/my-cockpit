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

function worker(lang = 'en', clients: FakeClient[] = [], scope = SCOPE) {
  const listeners: Record<string, (event: unknown) => void> = {};
  const self = {
    location: { href: `${scope}push-sw.js?lang=${lang}` },
    registration: { scope, showNotification: vi.fn(() => Promise.resolve()) },
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
    dispatch,
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

describe('push service worker, edge cases', () => {
  const DE_SCOPE = 'https://cockpit.example.test/de/';

  it('activates at once and takes over open tabs', async () => {
    const w = worker();

    await w.dispatch('install', {});
    await w.dispatch('activate', {});

    expect(w.self.skipWaiting).toHaveBeenCalled();
    expect(w.self.clients.claim).toHaveBeenCalled();
  });

  it('uses the language of a regional locale and falls back to English for others', async () => {
    const swiss = worker('de-CH');
    const french = worker('fr');

    await swiss.push({ event: 'mail' });
    await french.push({ event: 'mail' });

    expect(swiss.shown()[0][0]).toBe('Neue E-Mail');
    expect(french.shown()[0][0]).toBe('New mail');
  });

  it('shows the push when the only open tabs are hidden', async () => {
    const w = worker('en', [client(`${SCOPE}inbox`, 'hidden')]);

    await w.push(reminder);

    expect(w.shown()).toHaveLength(1);
  });

  it('opens nothing for a reminder whose item is unavailable, and tags it generically without an id', async () => {
    const w = worker();

    await w.push({
      event: 'reminder',
      data: { id: 'r3', item: { available: false, title: 'Gone', deepLink: '/tasks/t9' } },
    });
    await w.push({ event: 'reminder', data: { item: { title: 'No id' } } });

    expect(w.shown()[0][1].data.route).toBe('');
    expect(w.shown()[0][1].tag).toBe('reminder-r3');
    expect(w.shown()[1][1].tag).toBe('reminder');
  });

  it('shows one new mail without sender or subject with the generic title', async () => {
    const w = worker();

    await w.push({ event: 'mail', data: { count: 1, messages: [{ id: 'm2' }] } });
    await w.push({ event: 'mail', data: { count: 1 } });

    expect(w.shown()[0][0]).toBe('New mail');
    expect(w.shown()[0][1]).toMatchObject({
      body: '',
      tag: 'mail',
      data: { route: 'mail/messages/m2' },
    });
    expect(w.shown()[1][0]).toBe('New mail');
    expect(w.shown()[1][1].data.route).toBe('mail');
  });

  it('counts all new mail when only the newest messages came along', async () => {
    const w = worker();
    const messages = Array.from({ length: 10 }, (_, i) => ({ id: `m${i}`, subject: `S${i}` }));

    await w.push({ event: 'mail', data: { folderId: 'f2', count: 12, messages } });

    expect(w.shown()[0][0]).toBe('12 new messages');
    expect(String(w.shown()[0][1].body).split('\n')).toHaveLength(10);
    expect(w.shown()[0][1].data.route).toBe('mail');
  });

  it('opens a new tab when focusing the open one fails', async () => {
    const open = client(`${SCOPE}inbox`);
    open.focus.mockRejectedValue(new Error('not allowed'));
    const w = worker('en', [open]);

    await w.click({ route: 'mail' });

    expect(open.navigate).not.toHaveBeenCalled();
    expect(w.self.clients.openWindow).toHaveBeenCalledWith(`${SCOPE}mail`);
  });

  it('ignores open tabs outside its scope, e.g. of the other locale build', async () => {
    const english = client(`${SCOPE}inbox`);
    const w = worker('de', [english], DE_SCOPE);

    await w.click({ route: 'tasks/t1' });

    expect(english.navigate).not.toHaveBeenCalled();
    expect(w.self.clients.openWindow).toHaveBeenCalledWith(`${DE_SCOPE}tasks/t1`);
  });

  it('keeps a locale build worker inside its scope, also for routes that climb out of it', async () => {
    const w = worker('de', [], DE_SCOPE);

    await w.click({ route: '../admin' });
    await w.push(reminder);

    expect(w.self.clients.openWindow).toHaveBeenCalledWith(DE_SCOPE);
    expect(w.shown()[0][1].icon).toBe(`${DE_SCOPE}apple-touch-icon.png`);
  });
});
