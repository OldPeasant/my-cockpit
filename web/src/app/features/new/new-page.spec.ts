import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import {
  Api,
  listMailMessages,
  ListMailMessages$Params,
  listTasks,
  mailToTask,
  TaskResponse,
} from '../../api/generated';
import { ChangeEventsService } from '../../core/events';
import { ApiError, NotificationService } from '../../core/http';
import { MAIL_FOLDER_EVENT, REFRESH_DEBOUNCE_MS } from '../mail/list/mail-list-store';
import { FakeEvents, summary } from '../mail/list/mail-testing';
import { MailSummary } from '../mail/list/mail.service';
import NewPage from './new-page';

function task(id: string, createdAt: string): TaskResponse {
  return {
    id,
    title: `Task ${id}`,
    list: 'INBOX',
    position: 1,
    contextIds: [],
    createdAt,
    updatedAt: createdAt,
    version: 1,
  };
}

const mail = (id: string, receivedAt: string): MailSummary =>
  summary(id, { receivedAt, subject: `Mail ${id}` });

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('NewPage', () => {
  let tasks: TaskResponse[];
  /** The mail pages by cursor (`''` is the first page). */
  let pages: Map<string, { items: MailSummary[]; nextCursor?: string }>;
  let mailCalls: ListMailMessages$Params[];
  let toTaskCalls: string[];
  let toTaskAnswer: (id: string) => Promise<unknown>;
  /** When set, the task list / mail list requests fail with it. */
  let taskFailure: unknown;
  let mailFailure: unknown;
  let events: FakeEvents;
  let infos: string[];
  let errors: string[];
  let harness: RouterTestingHarness;
  let element: HTMLElement;

  beforeEach(() => {
    tasks = [task('t1', '2026-10-20T12:00:00Z'), task('t2', '2026-10-19T08:00:00Z')];
    pages = new Map([
      [
        '',
        {
          items: [mail('m1', '2026-10-20T11:00:00Z'), mail('m2', '2026-10-20T10:00:00Z')],
          nextCursor: 'c2',
        },
      ],
      ['c2', { items: [mail('m3', '2026-10-19T09:00:00Z'), mail('m4', '2026-10-18T09:00:00Z')] }],
    ]);
    mailCalls = [];
    toTaskCalls = [];
    toTaskAnswer = async (id) => {
      tasks = [...tasks, task(`from-${id}`, '2026-10-21T08:00:00Z')];
      return { id: `from-${id}`, linkId: 'l1', messageId: id, title: `Mail ${id}`, type: 'TASK' };
    };
    taskFailure = null;
    mailFailure = null;
    events = new FakeEvents();
    infos = [];
    errors = [];
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}) => {
        if (fn === listTasks) {
          if (taskFailure) throw taskFailure;
          return { items: tasks };
        }
        if (fn === listMailMessages) {
          const query = params as ListMailMessages$Params;
          mailCalls.push(query);
          if (mailFailure) throw mailFailure;
          return pages.get(query.cursor ?? '')!;
        }
        if (fn === mailToTask) {
          const id = params['id'] as string;
          toTaskCalls.push(id);
          return toTaskAnswer(id);
        }
        throw new Error('unexpected call');
      },
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'new', component: NewPage },
          { path: 'tasks/:id', children: [] },
          { path: 'mail/messages/:id', children: [] },
        ]),
        { provide: Api, useValue: api },
        { provide: ChangeEventsService, useValue: events },
        {
          provide: NotificationService,
          useValue: { info: (m: string) => infos.push(m), error: (m: string) => errors.push(m) },
        },
      ],
    });
  });

  afterEach(() => element?.remove());

  async function open(url = '/new'): Promise<void> {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    element = harness.routeNativeElement!;
    document.body.appendChild(harness.fixture.nativeElement);
    await refresh();
  }

  async function refresh(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await settle();
      harness.detectChanges();
    }
  }

  async function press(
    key: string,
    target: EventTarget = document.body,
    init: KeyboardEventInit = {},
  ): Promise<KeyboardEvent> {
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
    target.dispatchEvent(event);
    await refresh();
    return event;
  }

  const rowKeys = () =>
    [...element.querySelectorAll<HTMLElement>('li.row')].map((row) => row.dataset['key']);
  const row = (key: string) => element.querySelector<HTMLElement>(`li.row[data-key="${key}"]`);
  const button = (label: string, scope: ParentNode = element) =>
    [...scope.querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.includes(label),
    );

  it('merges both inboxes newest first and shows each row source', async () => {
    await open();
    expect(rowKeys()).toEqual(['task:t1', 'mail:m1', 'mail:m2']);
    expect(row('task:t1')!.querySelector('.source')!.textContent!.trim()).toBe('GTD Inbox');
    expect(row('mail:m1')!.querySelector('.source')!.textContent!.trim()).toBe('Mail');
    expect(row('task:t1')!.querySelector('.source svg')).not.toBeNull();
    expect(mailCalls[0]).toMatchObject({ folderId: undefined, cursor: undefined });
  });

  it('keeps the order when older mail loads', async () => {
    await open();
    button('Load older messages')!.click();
    await refresh();
    expect(mailCalls.at(-1)).toMatchObject({ cursor: 'c2' });
    expect(rowKeys()).toEqual(['task:t1', 'mail:m1', 'mail:m2', 'mail:m3', 'task:t2', 'mail:m4']);
    expect(button('Load older messages')).toBeUndefined();
  });

  it.each([
    ['/new?source=inbox', ['task:t1', 'task:t2']],
    ['/new?source=mail', ['mail:m1', 'mail:m2']],
    ['/new', ['task:t1', 'mail:m1', 'mail:m2']],
  ])('filters by the URL: %s', async (url, expected) => {
    await open(url);
    expect(rowKeys()).toEqual(expected);
  });

  it('switches the filter with the buttons and keys and keeps it in the URL', async () => {
    await open();
    const router = TestBed.inject(Router);
    button('Mail', element.querySelector('.filters')!)!.click();
    await refresh();
    expect(router.url).toBe('/new?source=mail');
    expect(rowKeys()).toEqual(['mail:m1', 'mail:m2']);
    expect(button('Mail', element.querySelector('.filters')!)!.getAttribute('aria-pressed')).toBe(
      'true',
    );

    await press('2');
    expect(router.url).toBe('/new?source=inbox');
    expect(rowKeys()).toEqual(['task:t1', 'task:t2']);

    await press('1');
    expect(router.url).toBe('/new');
    expect(rowKeys()).toEqual(['task:t1', 'mail:m1', 'mail:m2']);
  });

  it('opens a task in its detail view', async () => {
    await open();
    row('task:t1')!.querySelector<HTMLButtonElement>('button.open')!.click();
    await refresh();
    expect(TestBed.inject(Router).url).toBe('/tasks/t1');
  });

  it('opens a mail in the read view with the keyboard', async () => {
    await open();
    await press('j');
    await press('j');
    await press('o');
    expect(TestBed.inject(Router).url).toBe('/mail/messages/m1');
  });

  it('adds new captures and new mail live and drops processed items', async () => {
    await open();
    tasks = [task('t0', '2026-10-20T13:00:00Z'), tasks[1]];
    events.changes.next({ type: 'TASK', id: 't0', op: 'CREATED' } as never);
    await refresh();
    expect(rowKeys()).toEqual(['task:t0', 'mail:m1', 'mail:m2']);

    pages.set('', {
      items: [mail('m0', '2026-10-20T14:00:00Z'), mail('m1', '2026-10-20T11:00:00Z')],
      nextCursor: 'c2',
    });
    events.changes.next({ type: MAIL_FOLDER_EVENT, id: 'f-inbox', op: 'UPDATED' } as never);
    await new Promise((resolve) => setTimeout(resolve, REFRESH_DEBOUNCE_MS + 50));
    await refresh();
    expect(rowKeys()).toEqual(['mail:m0', 'task:t0', 'mail:m1']);

    events.changes.next({ type: 'TASK', id: 't0', op: 'DELETED' } as never);
    await refresh();
    expect(rowKeys()).toEqual(['mail:m0', 'mail:m1']);
  });

  it('makes an Inbox task of a mail and keeps the mail', async () => {
    await open();
    button('Inbox task', row('mail:m2')!)!.click();
    await refresh();
    expect(toTaskCalls).toEqual(['m2']);
    expect(rowKeys()).toEqual(['task:from-m2', 'task:t1', 'mail:m1', 'mail:m2']);
    expect(infos).toEqual(['Added “Mail m2” to the Inbox']);

    // `t` on a task row does nothing; on a mail row it makes a task.
    await press('j');
    await press('t');
    await press('j');
    await press('j');
    expect(row('mail:m1')!.getAttribute('aria-current')).toBe('true');
    await press('t');
    expect(toTaskCalls).toEqual(['m2', 'm1']);
  });

  it('reports a failed "→ Inbox task"', async () => {
    toTaskAnswer = () => Promise.reject(new ApiError(404, 'Not found', 'The message is gone.'));
    await open();
    button('Inbox task', row('mail:m1')!)!.click();
    await refresh();
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain('The task could not be created');
    expect(button('Inbox task', row('mail:m1')!)!.disabled).toBe(false);
  });

  it('reports an unexpected "→ Inbox task" failure with a generic message', async () => {
    toTaskAnswer = () => Promise.reject(new Error('socket closed'));
    await open();
    button('Inbox task', row('mail:m1')!)!.click();
    await refresh();
    expect(errors).toEqual(['The task could not be created: Something went wrong.']);
    expect(rowKeys()).toEqual(['task:t1', 'mail:m1', 'mail:m2']);
  });

  it('sends "→ Inbox task" once while it runs (double click, then `t`)', async () => {
    let finish!: () => void;
    toTaskAnswer = (id) =>
      new Promise((resolve) => {
        finish = () => {
          tasks = [...tasks, task(`from-${id}`, '2026-10-21T08:00:00Z')];
          resolve({
            id: `from-${id}`,
            linkId: 'l1',
            messageId: id,
            title: `Mail ${id}`,
            type: 'TASK',
          });
        };
      });
    await open();
    await press('j');
    await press('j');
    expect(row('mail:m1')!.getAttribute('aria-current')).toBe('true');

    const toTask = button('Inbox task', row('mail:m1')!)!;
    toTask.click();
    toTask.click();
    await refresh();
    expect(button('Inbox task', row('mail:m1')!)!.disabled).toBe(true);
    await press('t');
    expect(toTaskCalls).toEqual(['m1']);

    finish();
    await refresh();
    expect(toTaskCalls).toEqual(['m1']);
    expect(infos).toEqual(['Added “Mail m1” to the Inbox']);
    expect(button('Inbox task', row('mail:m1')!)!.disabled).toBe(false);
    // The mail stays in the list next to its new task.
    expect(rowKeys()).toEqual(['task:from-m1', 'task:t1', 'mail:m1', 'mail:m2']);
  });

  it('shows sender, snippet, a subject fallback and the read state of mail rows', async () => {
    pages.set('', {
      items: [
        summary('m1', {
          receivedAt: '2026-10-20T11:00:00Z',
          subject: '',
          snippet: 'Fictitious agenda',
          seen: true,
        }),
        mail('m2', '2026-10-20T10:00:00Z'),
      ],
    });
    await open();
    const first = row('mail:m1')!;
    expect(first.querySelector('.title')!.textContent!.trim()).toBe('(no subject)');
    expect(first.querySelector('.snippet')!.textContent).toContain('Max Muster');
    expect(first.querySelector('.snippet')!.textContent).toContain('– Fictitious agenda');
    expect(first.querySelector('.text.unread')).toBeNull();
    expect(row('mail:m2')!.querySelector('.text.unread')).not.toBeNull();
    expect(row('mail:m2')!.querySelector('.snippet')!.textContent).not.toContain('–');
  });

  describe('dates', () => {
    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-10-20T13:00:00Z'));
    });
    afterEach(() => vi.useRealTimers());

    it('shows the time today, the day this year and the full date before', async () => {
      tasks = [task('t1', '2026-10-20T12:00:00Z'), task('t2', '2025-10-19T12:00:00Z')];
      pages.set('', { items: [mail('m1', '2026-10-19T12:00:00Z')] });
      await open();
      const shown = (key: string) => row(key)!.querySelector('time')!;
      expect(shown('task:t1').getAttribute('datetime')).toBe('2026-10-20T12:00:00Z');
      expect(shown('task:t1').textContent!.trim()).toMatch(/^\d{1,2}:\d{2}\s?[AP]M$/);
      expect(shown('mail:m1').textContent!.trim()).toBe('Oct 19');
      expect(shown('task:t2').textContent!.trim()).toBe('Oct 19, 2025');
    });
  });

  it('does not navigate when the shown filter is chosen again', async () => {
    await open('/new?source=mail');
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate');
    button('Mail', element.querySelector('.filters')!)!.click();
    await press('3');
    expect(navigate).not.toHaveBeenCalled();
    expect(router.url).toBe('/new?source=mail');
  });

  it('opens a mail in the read view on click', async () => {
    await open();
    row('mail:m2')!.querySelector<HTMLButtonElement>('button.open')!.click();
    await refresh();
    expect(TestBed.inject(Router).url).toBe('/mail/messages/m2');
  });

  it('loads older mail when the keyboard cursor reaches the last row', async () => {
    await open();
    await press('j');
    await press('j');
    expect(mailCalls).toHaveLength(1);
    await press('j');
    expect(mailCalls.at(-1)).toMatchObject({ cursor: 'c2' });
    expect(rowKeys()).toEqual(['task:t1', 'mail:m1', 'mail:m2', 'mail:m3', 'task:t2', 'mail:m4']);
    expect(row('mail:m2')!.getAttribute('aria-current')).toBe('true');
  });

  it('keeps the cursor at the first row on `k`', async () => {
    await open();
    await press('j');
    await press('k');
    expect(row('task:t1')!.getAttribute('aria-current')).toBe('true');
  });

  it('shows the empty state, where the row keys do nothing', async () => {
    tasks = [];
    pages.set('', { items: [] });
    await open();
    await press('j');
    await press('o');
    expect(rowKeys()).toEqual([]);
    expect(element.querySelector('p.empty')!.textContent).toContain('Nothing new');
    expect(TestBed.inject(Router).url).toBe('/new');
  });

  describe('keys', () => {
    it('ignores keys typed into an input', async () => {
      await open();
      const input = document.createElement('input');
      element.appendChild(input);
      await press('3', input);
      await press('j', input);
      expect(TestBed.inject(Router).url).toBe('/new');
      expect(element.querySelector('[aria-current="true"]')).toBeNull();
    });

    it('ignores keys with modifiers and repeated keys', async () => {
      await open();
      const ctrl = await press('3', document.body, { ctrlKey: true });
      await press('2', document.body, { metaKey: true });
      await press('3', document.body, { repeat: true });
      expect(ctrl.defaultPrevented).toBe(false);
      expect(TestBed.inject(Router).url).toBe('/new');
    });

    it('moves the cursor while `j`/`k` are held, but repeats no other key', async () => {
      await open();
      await press('j');
      await press('j', document.body, { repeat: true });
      expect(row('mail:m1')!.getAttribute('aria-current')).toBe('true');
      await press('k', document.body, { repeat: true });
      expect(row('task:t1')!.getAttribute('aria-current')).toBe('true');
      const o = await press('o', document.body, { repeat: true });
      const t = await press('t', document.body, { repeat: true });
      expect(o.defaultPrevented).toBe(false);
      expect(t.defaultPrevented).toBe(false);
      expect(TestBed.inject(Router).url).toBe('/new');
    });

    it('moves the focus to the cursor row', async () => {
      await open();
      await press('j');
      await press('j');
      expect(document.activeElement).toBe(row('mail:m1')!.querySelector('button.open'));
    });

    it('opens the cursor row on `o` while a filter button has the focus', async () => {
      await open();
      await press('j');
      const filterButton = button('Mail', element.querySelector('.filters')!)!;
      filterButton.focus();
      await press('o', filterButton);
      expect(TestBed.inject(Router).url).toBe('/tasks/t1');
    });

    it('leaves Enter on a focused button to the button', async () => {
      await open();
      await press('j');
      const event = await press('Enter', button('Mail', element.querySelector('.filters')!)!);
      expect(event.defaultPrevented).toBe(false);
      expect(TestBed.inject(Router).url).toBe('/new');
    });

    it('ignores keys while a dialog is open', async () => {
      TestBed.configureTestingModule({
        providers: [{ provide: MatDialog, useValue: { openDialogs: [{}] } }],
      });
      await open();
      await press('3');
      await press('j');
      expect(TestBed.inject(Router).url).toBe('/new');
      expect(element.querySelector('[aria-current="true"]')).toBeNull();
    });
  });

  describe('live updates', () => {
    const waitForMailRefetch = async () => {
      await new Promise((resolve) => setTimeout(resolve, REFRESH_DEBOUNCE_MS + 50));
      await refresh();
    };

    it('drops a mail archived elsewhere after the folder refetch', async () => {
      await open();
      pages.set('', { items: [mail('m2', '2026-10-20T10:00:00Z')], nextCursor: 'c2' });
      events.changes.next({ type: MAIL_FOLDER_EVENT, id: 'f-archive', op: 'UPDATED' } as never);
      await waitForMailRefetch();
      expect(mailCalls.at(-1)).toMatchObject({ cursor: undefined });
      expect(rowKeys()).toEqual(['task:t1', 'mail:m2']);
    });

    it('drops a task processed in another view', async () => {
      await open();
      tasks = tasks.filter((t) => t.id !== 't1');
      events.changes.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as never);
      await refresh();
      expect(rowKeys()).toEqual(['mail:m1', 'mail:m2']);
    });

    it('refetches both inboxes after the event stream reconnects', async () => {
      await open();
      tasks = [task('t9', '2026-10-20T15:00:00Z'), ...tasks];
      pages.set('', {
        items: [mail('m9', '2026-10-20T16:00:00Z'), ...pages.get('')!.items],
        nextCursor: 'c2',
      });
      events.resync$.next();
      await waitForMailRefetch();
      expect(rowKeys()).toEqual(['mail:m9', 'task:t9', 'task:t1', 'mail:m1', 'mail:m2']);
    });
  });

  describe('load errors', () => {
    it('shows a failed Inbox load and retries it', async () => {
      taskFailure = new ApiError(500, 'Server error', 'The Inbox is down.');
      await open();
      const alert = element.querySelector('.load-error[role="alert"]')!;
      expect(alert.textContent).toContain('The Inbox could not be loaded: The Inbox is down.');
      expect(alert.textContent).not.toContain('messages could not be loaded');

      taskFailure = null;
      button('Try again', alert)!.click();
      await refresh();
      expect(element.querySelector('.load-error')).toBeNull();
      expect(rowKeys()).toEqual(['task:t1', 'mail:m1', 'mail:m2']);
      // Only the failed list is fetched again.
      expect(mailCalls).toHaveLength(1);
    });

    it('shows a failed mail load and reloads it', async () => {
      mailFailure = new ApiError(503, 'Unavailable', 'The mail server is down.');
      await open();
      const alert = element.querySelector('.load-error[role="alert"]')!;
      expect(alert.textContent).toContain(
        'The messages could not be loaded: The mail server is down.',
      );

      mailFailure = null;
      button('Try again', alert)!.click();
      await refresh();
      expect(element.querySelector('.load-error')).toBeNull();
      expect(mailCalls.at(-1)).toMatchObject({ cursor: undefined });
      expect(rowKeys()).toEqual(['task:t1', 'mail:m1', 'mail:m2']);
    });

    it.each([
      ['/new?source=mail', () => (taskFailure = new Error('down')), ['mail:m1', 'mail:m2']],
      ['/new?source=inbox', () => (mailFailure = new Error('down')), ['task:t1', 'task:t2']],
    ])('ignores the failure of the hidden source: %s', async (url, fail, expected) => {
      fail();
      await open(url);
      expect(element.querySelector('.load-error')).toBeNull();
      expect(rowKeys()).toEqual(expected);
    });

    it.each([
      ['Inbox', () => (taskFailure = new ApiError(500, 'Server error', 'Down.'))],
      ['mail', () => (mailFailure = new ApiError(500, 'Server error', 'Down.'))],
    ])('stops showing "Loading…" when the %s load failed', async (_, fail) => {
      fail();
      await open();
      expect(element.querySelector('.load-error[role="alert"]')).not.toBeNull();
      expect(element.querySelector('p.note[role="status"]')).toBeNull();
    });
  });

  describe('load errors under All', () => {
    it('shows every task next to a failed mail load', async () => {
      mailFailure = new ApiError(503, 'Unavailable', 'Down.');
      await open();
      expect(element.querySelector('.load-error[role="alert"]')).not.toBeNull();
      expect(rowKeys()).toEqual(['task:t1', 'task:t2']);
    });

    it('shows the mails next to a failed Inbox load', async () => {
      taskFailure = new ApiError(500, 'Server error', 'Down.');
      await open();
      expect(element.querySelector('.load-error[role="alert"]')).not.toBeNull();
      expect(rowKeys()).toEqual(['mail:m1', 'mail:m2']);
    });

    it('shows no empty state next to the error', async () => {
      taskFailure = new ApiError(500, 'Server error', 'Down.');
      pages.set('', { items: [] });
      await open();
      expect(element.querySelector('.load-error[role="alert"]')).not.toBeNull();
      expect(element.querySelector('p.empty')).toBeNull();
    });
  });

  describe('infinite scroll', () => {
    let observers: {
      callback: (entries: { isIntersecting: boolean }[]) => void;
      observed: Element[];
      disconnected: boolean;
    }[];

    beforeEach(() => {
      observers = [];
      vi.stubGlobal(
        'IntersectionObserver',
        class {
          readonly record: (typeof observers)[number];
          constructor(callback: (entries: { isIntersecting: boolean }[]) => void) {
            this.record = { callback, observed: [], disconnected: false };
            observers.push(this.record);
          }
          observe(target: Element) {
            this.record.observed.push(target);
          }
          disconnect() {
            this.record.disconnected = true;
          }
        },
      );
    });
    afterEach(() => vi.unstubAllGlobals());

    it('loads the next mail page when the end of the list comes into view', async () => {
      await open();
      expect(observers).toHaveLength(1);
      expect(observers[0].observed[0]).toBe(element.querySelector('.more'));

      observers[0].callback([{ isIntersecting: false }]);
      await refresh();
      expect(mailCalls).toHaveLength(1);

      observers[0].callback([{ isIntersecting: true }]);
      await refresh();
      expect(mailCalls.at(-1)).toMatchObject({ cursor: 'c2' });
      expect(rowKeys()).toEqual(['task:t1', 'mail:m1', 'mail:m2', 'mail:m3', 'task:t2', 'mail:m4']);
      // The last page: nothing more to observe.
      expect(element.querySelector('.more')).toBeNull();
      expect(observers[0].disconnected).toBe(true);
    });

    it('shows a failed next page and loads it again on the button', async () => {
      await open();
      mailFailure = new ApiError(500, 'Server error', 'Try later.');
      observers[0].callback([{ isIntersecting: true }]);
      await refresh();
      expect(element.querySelector('.more [role="alert"]')!.textContent).toContain('Try later.');
      expect(rowKeys()).toEqual(['task:t1', 'mail:m1', 'mail:m2']);

      mailFailure = null;
      button('Load older messages')!.click();
      await refresh();
      expect(element.querySelector('.more')).toBeNull();
      expect(rowKeys()).toEqual(['task:t1', 'mail:m1', 'mail:m2', 'mail:m3', 'task:t2', 'mail:m4']);
    });

    it('loads no mail pages under the GTD Inbox filter', async () => {
      await open('/new?source=inbox');
      expect(observers).toEqual([]);
      expect(element.querySelector('.more')).toBeNull();
    });
  });
});
