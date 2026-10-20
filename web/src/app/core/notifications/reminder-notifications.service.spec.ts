import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { ReminderResponse } from '../../api/generated';
import { ChangeEventsService } from '../events';
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

  function create(
    permission: NotificationPermissionState = 'default',
  ): ReminderNotificationsService {
    browser = new FakeBrowserNotifications();
    browser.current = permission;
    fired = new Subject();
    messages$ = vi.fn(() => fired.asObservable());
    action = new Subject();
    snackBar = { open: vi.fn(() => ({ onAction: () => action.asObservable() })) };
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
});
