import { DOCUMENT, LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { BROWSER_PUSH } from './browser-push';

const BASE = 'https://cockpit.example.test/de/';

/** A stand-in for the browser's `PushSubscription`. */
function stubSubscription(key: ArrayBuffer | null = new Uint8Array([4, 1, 2]).buffer) {
  return {
    endpoint: 'https://push.example.test/send/fictitious',
    options: { applicationServerKey: key },
    toJSON: vi.fn(() => ({
      endpoint: 'https://push.example.test/send/fictitious',
      keys: { p256dh: 'BFakeP256dh', auth: 'fakeAuth' },
    })),
    unsubscribe: vi.fn(() => Promise.resolve(true)),
  };
}

/** A window with a service worker container and Push API, as far as `BROWSER_PUSH` uses them. */
function stubWindow(options: { registered?: boolean; pushManager?: boolean } = {}) {
  const existing = stubSubscription();
  const created = stubSubscription();
  const pushManager = {
    getSubscription: vi.fn(() => Promise.resolve(existing)),
    subscribe: vi.fn(() => Promise.resolve(created)),
  };
  const serviceWorker = {
    getRegistration: vi.fn(() =>
      Promise.resolve(options.registered === false ? undefined : { pushManager }),
    ),
    register: vi.fn(() => Promise.resolve({ pushManager })),
    ready: Promise.resolve({ pushManager }),
  };
  const window: Record<string, unknown> = { navigator: { serviceWorker } };
  if (options.pushManager !== false) window['PushManager'] = class {};
  return { window, serviceWorker, pushManager, existing, created };
}

function inject(window: unknown, locale = 'de') {
  TestBed.configureTestingModule({
    providers: [
      { provide: DOCUMENT, useValue: { defaultView: window, baseURI: BASE } },
      { provide: LOCALE_ID, useValue: locale },
    ],
  });
  return TestBed.inject(BROWSER_PUSH);
}

describe('BROWSER_PUSH', () => {
  it('is unsupported without a service worker container or Push API', async () => {
    const withoutWorker = inject({ navigator: {}, PushManager: class {} });

    expect(withoutWorker.supported()).toBe(false);
    expect(await withoutWorker.subscription()).toBeNull();
    await expect(withoutWorker.subscribe(new Uint8Array(65))).rejects.toThrow();
  });

  it('is unsupported when the browser has service workers but no PushManager', () => {
    const { window } = stubWindow({ pushManager: false });

    expect(inject(window).supported()).toBe(false);
  });

  it('is unsupported without a window', () => {
    expect(inject(null).supported()).toBe(false);
  });

  it('asks the registration of the app base for the current subscription', async () => {
    const { window, serviceWorker, existing } = stubWindow();
    const push = inject(window);

    const subscription = await push.subscription();

    expect(push.supported()).toBe(true);
    expect(serviceWorker.getRegistration).toHaveBeenCalledWith(BASE);
    expect(serviceWorker.register).not.toHaveBeenCalled();
    expect(subscription?.endpoint).toBe(existing.endpoint);
    expect(subscription?.applicationServerKey).toBe(existing.options.applicationServerKey);
    expect(subscription?.toJSON().keys).toEqual({ p256dh: 'BFakeP256dh', auth: 'fakeAuth' });
    await subscription?.unsubscribe();
    expect(existing.unsubscribe).toHaveBeenCalled();
  });

  it('reports no subscription when the worker is not registered, without registering it', async () => {
    const { window, serviceWorker } = stubWindow({ registered: false });

    expect(await inject(window).subscription()).toBeNull();
    expect(serviceWorker.register).not.toHaveBeenCalled();
  });

  it('registers the locale build worker next to index.html and subscribes for visible pushes', async () => {
    const { window, serviceWorker, pushManager, created } = stubWindow();
    const key = new Uint8Array([4, 9, 9]);

    const subscription = await inject(window, 'de').subscribe(key);

    expect(serviceWorker.register).toHaveBeenCalledWith(`${BASE}push-sw.js?lang=de`);
    expect(pushManager.subscribe).toHaveBeenCalledWith({
      userVisibleOnly: true,
      applicationServerKey: key,
    });
    expect(subscription.endpoint).toBe(created.endpoint);
  });

  it('passes a failing subscribe on, e.g. a refused permission', async () => {
    const { window, pushManager } = stubWindow();
    pushManager.subscribe.mockRejectedValue(new DOMException('denied', 'NotAllowedError'));

    await expect(inject(window).subscribe(new Uint8Array(65))).rejects.toThrow('denied');
  });
});
