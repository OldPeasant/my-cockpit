import { TestBed } from '@angular/core/testing';
import { BROWSER_NOTIFICATIONS } from './browser-notifications';

/** Stand-in for the browser's `Notification` constructor (jsdom has none). */
class StubNotification {
  static permission: NotificationPermission = 'default';
  static requestPermission = vi.fn(() => Promise.resolve<NotificationPermission>('granted'));
  static created: StubNotification[] = [];
  onclick: ((event: Event) => void) | null = null;

  constructor(
    readonly title: string,
    readonly options: NotificationOptions,
  ) {
    StubNotification.created.push(this);
  }

  close(): void {
    // not needed here
  }
}

describe('BROWSER_NOTIFICATIONS', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    StubNotification.created = [];
    StubNotification.permission = 'default';
    StubNotification.requestPermission.mockClear();
  });

  describe('without the Notification API', () => {
    // jsdom has no Notification API; these tests rely on that.
    beforeEach(() => expect('Notification' in window).toBe(false));

    it('reports unsupported', () => {
      expect(TestBed.inject(BROWSER_NOTIFICATIONS).permission()).toBe('unsupported');
    });

    it('answers a permission request with denied', async () => {
      await expect(TestBed.inject(BROWSER_NOTIFICATIONS).requestPermission()).resolves.toBe(
        'denied',
      );
    });

    it('refuses to show a notification', () => {
      expect(() =>
        TestBed.inject(BROWSER_NOTIFICATIONS).show('Call the plumber', { tag: 'reminder-x' }),
      ).toThrow();
    });
  });

  describe('with the Notification API', () => {
    beforeEach(() => vi.stubGlobal('Notification', StubNotification));

    it('reads the current permission each time', () => {
      const browser = TestBed.inject(BROWSER_NOTIFICATIONS);
      expect(browser.permission()).toBe('default');

      StubNotification.permission = 'denied';

      expect(browser.permission()).toBe('denied');
    });

    it('asks the browser for the permission', async () => {
      await expect(TestBed.inject(BROWSER_NOTIFICATIONS).requestPermission()).resolves.toBe(
        'granted',
      );
      expect(StubNotification.requestPermission).toHaveBeenCalledTimes(1);
    });

    it('shows a notification with the given title and options', () => {
      const shown = TestBed.inject(BROWSER_NOTIFICATIONS).show('Call the plumber', {
        body: 'Reminder from My Cockpit',
        tag: 'reminder-0190a5c8-0000-7000-8000-0000000000r1',
      });

      expect(StubNotification.created).toHaveLength(1);
      expect(shown).toBe(StubNotification.created[0]);
      expect(StubNotification.created[0].title).toBe('Call the plumber');
      expect(StubNotification.created[0].options).toEqual({
        body: 'Reminder from My Cockpit',
        tag: 'reminder-0190a5c8-0000-7000-8000-0000000000r1',
      });
    });

    it('passes on the error when the browser refuses to construct one', () => {
      vi.stubGlobal(
        'Notification',
        class {
          static permission = 'granted';
          constructor() {
            throw new TypeError('Illegal constructor');
          }
        },
      );
      expect(() => TestBed.inject(BROWSER_NOTIFICATIONS).show('Call the plumber', {})).toThrow(
        TypeError,
      );
    });
  });
});
