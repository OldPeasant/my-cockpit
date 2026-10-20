import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { ReminderResponse } from '../../api/generated';
import { ChangeEventsService } from '../events';
import { NotificationService } from '../http/notification.service';
import { BROWSER_NOTIFICATIONS, NotificationPermissionState } from './browser-notifications';
import {
  REMINDER_EVENT_NAME,
  REMINDER_NOTIFICATIONS_STORAGE_KEY,
  ReminderNotificationsService,
} from './reminder-notifications.service';
import { FakeBrowserNotifications } from './testing';

function reminder(item: Partial<ReminderResponse['item']> = {}): ReminderResponse {
  return {
    id: '0190a5c8-0000-7000-8000-0000000000r1',
    channel: 'NOTIFICATION',
    createdAt: '2026-10-20T08:00:00Z',
    remindAt: '2026-10-20T09:00:00Z',
    firedAt: '2026-10-20T09:00:01Z',
    item: {
      type: 'TASK',
      id: '0190a5c8-0000-7000-8000-000000000001',
      available: true,
      title: 'Call the plumber',
      deepLink: '/tasks/0190a5c8-0000-7000-8000-000000000001',
      ...item,
    },
  };
}

describe('ReminderNotificationsService', () => {
  let browser: FakeBrowserNotifications;
  let fired: Subject<ReminderResponse>;
  let messages$: ReturnType<typeof vi.fn>;
  let action: Subject<void>;
  let snackBar: { open: ReturnType<typeof vi.fn> };
  /** The toast on screen; like MatSnackBar, opening another one dismisses it. */
  let shownToast: { dismiss: () => void } | undefined;

  /** A MatSnackBar double that shows one toast at a time, like the real one. */
  function openToast() {
    shownToast?.dismiss();
    action = new Subject();
    const toastAction = action;
    const dismissed = new Subject<void>();
    const ref = {
      onAction: () => toastAction.asObservable(),
      afterDismissed: () => dismissed.asObservable(),
      dismiss: () => {
        if (shownToast === ref) shownToast = undefined;
        dismissed.next();
        dismissed.complete();
      },
    };
    shownToast = ref;
    return ref;
  }

  function create(
    permission: NotificationPermissionState = 'default',
  ): ReminderNotificationsService {
    browser = new FakeBrowserNotifications();
    browser.current = permission;
    fired = new Subject();
    messages$ = vi.fn(() => fired.asObservable());
    action = new Subject();
    shownToast = undefined;
    snackBar = { open: vi.fn(openToast) };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: BROWSER_NOTIFICATIONS, useValue: browser },
        { provide: ChangeEventsService, useValue: { messages$ } },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    return TestBed.inject(ReminderNotificationsService);
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('listens to the reminder SSE event', () => {
    create();
    expect(messages$).toHaveBeenCalledWith(REMINDER_EVENT_NAME);
  });

  it('asks for the permission on enable and remembers the switch when granted', async () => {
    const service = create('default');

    await expect(service.enable()).resolves.toBe('granted');

    expect(browser.requests).toBe(1);
    expect(service.permission()).toBe('granted');
    expect(service.enabled()).toBe(true);
    expect(localStorage.getItem(REMINDER_NOTIFICATIONS_STORAGE_KEY)).toBe('on');
  });

  it('stays off when the permission is refused', async () => {
    const service = create('default');
    browser.answer = 'denied';

    await expect(service.enable()).resolves.toBe('denied');

    expect(service.enabled()).toBe(false);
    expect(service.switchedOn()).toBe(false);
    expect(localStorage.getItem(REMINDER_NOTIFICATIONS_STORAGE_KEY)).toBeNull();
  });

  it('does not ask again once the permission was refused or granted', async () => {
    const service = create('denied');
    await service.enable();
    browser.current = 'granted';
    await service.enable();

    expect(browser.requests).toBe(0);
    expect(service.enabled()).toBe(true);
  });

  it('cannot be switched on without Notification support', async () => {
    const service = create('unsupported');

    await expect(service.enable()).resolves.toBe('unsupported');
    expect(service.enabled()).toBe(false);
  });

  it('restores the stored switch, and disable clears it', () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    const service = create('granted');
    expect(service.enabled()).toBe(true);

    service.disable();

    expect(service.enabled()).toBe(false);
    expect(localStorage.getItem(REMINDER_NOTIFICATIONS_STORAGE_KEY)).toBeNull();
  });

  it('shows a fired reminder as a browser notification when enabled, and a click opens the item', async () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    create('granted');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const focus = vi.spyOn(window, 'focus').mockImplementation(() => undefined);

    fired.next(reminder());

    expect(snackBar.open).not.toHaveBeenCalled();
    expect(browser.shown).toHaveLength(1);
    const [{ title, options, notification }] = browser.shown;
    expect(title).toBe('Call the plumber');
    expect(options.tag).toBe('reminder-0190a5c8-0000-7000-8000-0000000000r1');

    notification.onclick?.(new Event('click'));

    expect(focus).toHaveBeenCalled();
    expect(notification.closed).toBe(true);
    expect(navigate).toHaveBeenCalledWith('/tasks/0190a5c8-0000-7000-8000-000000000001');
  });

  it('falls back to a toast with an Open action while notifications are off', () => {
    create('granted');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    fired.next(reminder());

    expect(browser.shown).toHaveLength(0);
    expect(snackBar.open).toHaveBeenCalledWith(
      'Reminder: Call the plumber',
      'Open',
      expect.objectContaining({ duration: 15_000 }),
    );
    action.next();
    expect(navigate).toHaveBeenCalledWith('/tasks/0190a5c8-0000-7000-8000-000000000001');
  });

  it('falls back to a toast when the permission was revoked in the browser meanwhile', () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    const service = create('granted');
    browser.current = 'denied';

    fired.next(reminder());

    expect(browser.shown).toHaveLength(0);
    expect(snackBar.open).toHaveBeenCalledTimes(1);
    expect(service.permission()).toBe('denied');
  });

  it('falls back to a toast when the browser refuses to show the notification', () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    create('granted');
    browser.refuseToShow = true;

    fired.next(reminder());

    expect(snackBar.open).toHaveBeenCalledTimes(1);
  });

  it('toasts an unavailable item without a title or link', () => {
    create('unsupported');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl');

    fired.next(reminder({ available: false, title: undefined, deepLink: undefined }));

    expect(snackBar.open).toHaveBeenCalledWith('Reminder', 'Dismiss', expect.anything());
    action.next();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('ignores a reminder event without an item', () => {
    create();
    fired.next({} as ReminderResponse);
    expect(snackBar.open).not.toHaveBeenCalled();
  });

  it('ignores a null reminder event', () => {
    create();
    fired.next(null as unknown as ReminderResponse);
    expect(snackBar.open).not.toHaveBeenCalled();
    expect(browser.shown).toHaveLength(0);
  });

  it('keeps the permission undecided and the switch off when the permission prompt fails', async () => {
    const service = create('default');
    browser.requestPermission = vi.fn(() => Promise.reject(new Error('prompt dismissed')));

    await expect(service.enable()).resolves.toBe('default');

    expect(browser.requestPermission).toHaveBeenCalledTimes(1);
    expect(service.permission()).toBe('default');
    expect(service.switchedOn()).toBe(false);
    expect(service.enabled()).toBe(false);
    expect(localStorage.getItem(REMINDER_NOTIFICATIONS_STORAGE_KEY)).toBeNull();
  });

  it('asks again on the next enable after the prompt was dismissed', async () => {
    const service = create('default');
    browser.answer = 'default';
    await service.enable();
    browser.answer = 'granted';

    await expect(service.enable()).resolves.toBe('granted');

    expect(browser.requests).toBe(2);
    expect(service.enabled()).toBe(true);
  });

  it('does not use a stored switch while the permission is not granted', () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    const service = create('default');

    expect(service.switchedOn()).toBe(true);
    expect(service.enabled()).toBe(false);

    fired.next(reminder());

    expect(browser.shown).toHaveLength(0);
    expect(snackBar.open).toHaveBeenCalledWith(
      'Reminder: Call the plumber',
      'Open',
      expect.anything(),
    );
  });

  it('ignores a stored value other than on', () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'true');
    const service = create('granted');
    expect(service.switchedOn()).toBe(false);
    expect(service.enabled()).toBe(false);
  });

  it('picks up a permission granted in the browser meanwhile when a reminder fires', () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    const service = create('default');
    browser.current = 'granted';

    fired.next(reminder());

    expect(service.permission()).toBe('granted');
    expect(browser.shown).toHaveLength(1);
    expect(snackBar.open).not.toHaveBeenCalled();
  });

  it('refresh re-reads the permission from the browser', () => {
    const service = create('default');
    browser.current = 'denied';
    expect(service.permission()).toBe('default');

    service.refresh();

    expect(service.permission()).toBe('denied');
  });

  it('toasts again after notifications were switched off', async () => {
    const service = create('default');
    await service.enable();
    service.disable();

    fired.next(reminder());

    expect(browser.shown).toHaveLength(0);
    expect(snackBar.open).toHaveBeenCalledTimes(1);
  });

  it('gives the notification a body and one tag per reminder', () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    create('granted');

    fired.next(reminder());
    fired.next({ ...reminder(), id: '0190a5c8-0000-7000-8000-0000000000r2' });

    expect(browser.shown.map((shown) => shown.options.tag)).toEqual([
      'reminder-0190a5c8-0000-7000-8000-0000000000r1',
      'reminder-0190a5c8-0000-7000-8000-0000000000r2',
    ]);
    expect(browser.shown[0].options.body).toBe('Reminder from My Cockpit');
  });

  it('titles the notification "Reminder" when the item has no title', () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    create('granted');

    fired.next(reminder({ title: '' }));

    expect(browser.shown[0].title).toBe('Reminder');
  });

  it('only focuses and closes on a notification click when the item is unavailable', () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    create('granted');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const focus = vi.spyOn(window, 'focus').mockImplementation(() => undefined);

    fired.next(reminder({ available: false }));
    const [{ notification }] = browser.shown;
    notification.onclick?.(new Event('click'));

    expect(focus).toHaveBeenCalled();
    expect(notification.closed).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
    focus.mockRestore();
  });

  it('toasts the title of an unavailable item without the Open action', () => {
    create('granted');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl');

    fired.next(reminder({ available: false }));

    expect(snackBar.open).toHaveBeenCalledWith(
      'Reminder: Call the plumber',
      'Dismiss',
      expect.objectContaining({ duration: 15_000 }),
    );
    action.next();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('does not open the item before the toast action is used', () => {
    create('denied');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    fired.next(reminder());

    expect(snackBar.open).toHaveBeenCalledTimes(1);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('toasts every fired reminder while notifications are unsupported', () => {
    create('unsupported');

    fired.next(reminder());
    fired.next(reminder({ title: 'Water the plants' }));
    shownToast?.dismiss();

    expect(snackBar.open.mock.calls.map((call) => call[0])).toEqual([
      'Reminder: Call the plumber',
      'Reminder: Water the plants',
    ]);
  });

  it('shows reminders that fire together one after the other, in order', () => {
    create('denied');

    fired.next(reminder());
    fired.next(reminder({ title: 'Water the plants' }));
    fired.next(reminder({ title: undefined, available: false }));

    expect(snackBar.open).toHaveBeenCalledTimes(1);
    shownToast?.dismiss();
    expect(snackBar.open).toHaveBeenCalledTimes(2);
    shownToast?.dismiss();
    expect(snackBar.open.mock.calls.map((call) => [call[0], call[1]])).toEqual([
      ['Reminder: Call the plumber', 'Open'],
      ['Reminder: Water the plants', 'Open'],
      ['Reminder', 'Dismiss'],
    ]);
    shownToast?.dismiss();
    expect(snackBar.open).toHaveBeenCalledTimes(3);
  });

  it('opens the item of a queued reminder from its own toast', () => {
    create('unsupported');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    fired.next(reminder());
    fired.next(reminder({ title: 'Water the plants', deepLink: '/tasks/plants' }));
    shownToast?.dismiss();
    action.next();

    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith('/tasks/plants');
  });

  it('toasts a reminder that fires after the queue ran empty right away', () => {
    create('denied');

    fired.next(reminder());
    shownToast?.dismiss();
    fired.next(reminder({ title: 'Water the plants' }));

    expect(snackBar.open).toHaveBeenCalledTimes(2);
    expect(snackBar.open).toHaveBeenLastCalledWith(
      'Reminder: Water the plants',
      'Open',
      expect.anything(),
    );
  });

  it('drops queued reminder toasts when the app is destroyed', () => {
    create('denied');

    fired.next(reminder());
    fired.next(reminder({ title: 'Water the plants' }));
    TestBed.resetTestingModule();
    shownToast?.dismiss();

    expect(snackBar.open).toHaveBeenCalledTimes(1);
  });

  it('shows a burst of reminders as one browser notification each, without toasts', () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    create('granted');

    fired.next(reminder());
    fired.next({ ...reminder({ title: 'Water the plants' }), id: 'r2' });
    fired.next({ ...reminder({ title: 'Pay the rent' }), id: 'r3' });

    expect(browser.shown.map((shown) => shown.title)).toEqual([
      'Call the plumber',
      'Water the plants',
      'Pay the rent',
    ]);
    expect(snackBar.open).not.toHaveBeenCalled();
  });

  it('notifies at once while a reminder toast is still queued, once notifications work', async () => {
    const service = create('denied');
    fired.next(reminder());
    fired.next(reminder({ title: 'Water the plants' }));
    browser.current = 'granted';
    await service.enable();

    fired.next(reminder({ title: 'Pay the rent' }));

    expect(browser.shown.map((shown) => shown.title)).toEqual(['Pay the rent']);
    expect(snackBar.open).toHaveBeenCalledTimes(1);
    shownToast?.dismiss();
    expect(snackBar.open).toHaveBeenLastCalledWith(
      'Reminder: Water the plants',
      'Open',
      expect.anything(),
    );
  });

  it('opens the item of the toast on screen, not of the reminder queued behind it', () => {
    create('denied');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    fired.next(reminder());
    fired.next(reminder({ title: 'Water the plants', deepLink: '/tasks/plants' }));
    action.next();

    expect(navigate).toHaveBeenCalledExactlyOnceWith('/tasks/0190a5c8-0000-7000-8000-000000000001');
  });

  it('moves on to the queued reminder when an app message replaces the reminder toast', () => {
    create('denied');

    fired.next(reminder());
    fired.next(reminder({ title: 'Water the plants' }));
    // Another part of the app (e.g. NotificationService) opens a snackbar of its own.
    TestBed.inject(MatSnackBar).open('Task saved.', 'Dismiss', { duration: 4000 });

    expect(snackBar.open.mock.calls.map((call) => call[0])).toContain('Reminder: Water the plants');
  });

  describe('with the real MatSnackBar', () => {
    const shown = () =>
      [...document.querySelectorAll('mat-snack-bar-container:not([mat-exit])')].map((container) =>
        container.textContent?.replace(/\s+/g, ' ').trim(),
      );

    function createWithRealSnackBar(): void {
      browser = new FakeBrowserNotifications();
      browser.current = 'denied';
      fired = new Subject();
      TestBed.configureTestingModule({
        providers: [
          provideRouter([]),
          { provide: BROWSER_NOTIFICATIONS, useValue: browser },
          { provide: ChangeEventsService, useValue: { messages$: () => fired.asObservable() } },
        ],
      });
      TestBed.inject(ReminderNotificationsService);
    }

    beforeEach(() => vi.useFakeTimers());
    afterEach(async () => {
      TestBed.inject(MatSnackBar).dismiss();
      await vi.runAllTimersAsync();
      vi.useRealTimers();
    });

    it('shows a burst of reminders one after the other, each for its full duration', async () => {
      createWithRealSnackBar();

      fired.next(reminder());
      fired.next(reminder({ title: 'Water the plants' }));
      await vi.advanceTimersByTimeAsync(1_000);
      expect(shown()).toEqual(['Reminder: Call the plumber Open']);

      await vi.advanceTimersByTimeAsync(13_000);
      expect(shown()).toEqual(['Reminder: Call the plumber Open']);

      await vi.advanceTimersByTimeAsync(2_000);
      expect(shown()).toEqual(['Reminder: Water the plants Open']);
    });

    it('keeps an app message on screen that replaced a reminder toast, then shows the next reminder', async () => {
      createWithRealSnackBar();
      fired.next(reminder());
      fired.next(reminder({ title: 'Water the plants' }));
      await vi.advanceTimersByTimeAsync(1_000);

      TestBed.inject(NotificationService).error('The server cannot be reached.');
      await vi.advanceTimersByTimeAsync(1_000);

      // The error stays for its own 8 s; the queued reminder must not push it away.
      expect(shown()).toEqual(['The server cannot be reached. Dismiss']);

      await vi.advanceTimersByTimeAsync(8_000);
      expect(shown()).toEqual(['Reminder: Water the plants Open']);
    });
  });

  describe('with a failing localStorage', () => {
    afterEach(() => vi.restoreAllMocks());

    it('starts switched off when reading the switch throws', () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new Error('denied');
      });
      const service = create('granted');
      expect(service.switchedOn()).toBe(false);
    });

    it('keeps the switch for this session when storing it throws', async () => {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('quota');
      });
      vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
        throw new Error('denied');
      });
      const service = create('default');

      await expect(service.enable()).resolves.toBe('granted');
      expect(service.enabled()).toBe(true);
      fired.next(reminder());
      expect(browser.shown).toHaveLength(1);

      expect(() => service.disable()).not.toThrow();
      expect(service.enabled()).toBe(false);
    });
  });
});
