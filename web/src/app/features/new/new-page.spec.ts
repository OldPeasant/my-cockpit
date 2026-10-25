import { TestBed } from '@angular/core/testing';
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
    events = new FakeEvents();
    infos = [];
    errors = [];
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}) => {
        if (fn === listTasks) return { items: tasks };
        if (fn === listMailMessages) {
          const query = params as ListMailMessages$Params;
          mailCalls.push(query);
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

  async function press(key: string): Promise<void> {
    document.body.dispatchEvent(
      new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }),
    );
    await refresh();
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
});
