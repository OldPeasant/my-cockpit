import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { TrashItemResponse } from '../../api/generated';
import { NotificationService, provideHttp } from '../../core/http';
import TrashPage, { daysUntilPurge } from './trash-page';
import { TrashService } from './trash.service';

const LIST = '/api/v1/trash';
const DAY = 24 * 60 * 60 * 1000;

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const TASK_ID = '0192f0c4-0000-7000-8000-000000000001';
const NOTE_ID = '0192f0c4-0000-7000-8000-000000000002';
const MAIL_ID = '0192f0c4-0000-7000-8000-000000000003';

function item(
  type: TrashItemResponse['type'],
  id: string,
  title: string,
  daysLeft: number,
  subtitle?: string,
): TrashItemResponse {
  const purgeAt = Date.now() + daysLeft * DAY - 60_000;
  return {
    type,
    id,
    title,
    subtitle,
    icon: 'description',
    deletedAt: new Date(purgeAt - 30 * DAY).toISOString(),
    purgeAt: new Date(purgeAt).toISOString(),
  };
}

const ITEMS = [
  item('TASK', TASK_ID, 'Book train to Lucerne', 29, 'Project Holiday'),
  item('NOTE', NOTE_ID, 'Packing list', 2),
  item('EMAIL', MAIL_ID, 'Your invoice', 0, 'billing@example.test'),
];

describe('daysUntilPurge', () => {
  const now = Date.parse('2026-10-20T12:00:00Z');

  it('rounds partial days up', () => {
    expect(daysUntilPurge('2026-11-19T12:00:00Z', now)).toBe(30);
    expect(daysUntilPurge('2026-10-21T11:00:00Z', now)).toBe(1);
    expect(daysUntilPurge('2026-10-21T13:00:00Z', now)).toBe(2);
  });

  it('is 0 once the purge is due', () => {
    expect(daysUntilPurge('2026-10-20T12:00:00Z', now)).toBe(0);
    expect(daysUntilPurge('2026-10-19T12:00:00Z', now)).toBe(0);
  });
});

describe('TrashPage', () => {
  let fixture: ComponentFixture<TrashPage>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  async function create(): Promise<void> {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TrashPage);
    root = fixture.nativeElement as HTMLElement;
    await settle();
  }

  async function setup(items: TrashItemResponse[] = ITEMS, nextCursor?: string): Promise<void> {
    await create();
    http.expectOne((r) => r.url === LIST && !r.params.has('cursor')).flush({ items, nextCursor });
    await settle();
  }

  async function settle(): Promise<void> {
    await tick();
    await fixture.whenStable();
  }

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
    http.verify();
  });

  function titles(): string[] {
    return [...root.querySelectorAll('.items .title')].map((el) => el.textContent?.trim() ?? '');
  }

  function row(title: string): HTMLElement {
    return [...root.querySelectorAll<HTMLElement>('.items .item')].find(
      (li) => li.querySelector('.title')?.textContent?.trim() === title,
    ) as HTMLElement;
  }

  function byLabel(label: string): HTMLButtonElement {
    return root.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement;
  }

  function dialogButton(text: string): HTMLButtonElement {
    return [...document.querySelectorAll<HTMLButtonElement>('app-purge-dialog button')].find(
      (button) => button.textContent?.trim() === text,
    ) as HTMLButtonElement;
  }

  /** The dialog closes (and the delete starts) only after its exit animation. */
  async function requestTo(url: string): Promise<TestRequest> {
    let request: TestRequest | undefined;
    await vi.waitFor(() => {
      [request] = http.match(url);
      if (!request) throw new Error(`no request to ${url}`);
    });
    return request as TestRequest;
  }

  async function openPurge(title: string): Promise<void> {
    byLabel(`Delete ${title} permanently`).click();
    await settle();
    await vi.waitFor(() => expect(dialogButton('Delete permanently')).toBeTruthy());
  }

  const noContent = { status: 204, statusText: 'No Content' };

  it('shows a loading state, then lists items of all types in server order', async () => {
    await create();
    expect(root.querySelector('[role="status"]')?.textContent).toContain('Loading');
    http.expectOne((r) => r.url === LIST).flush({ items: ITEMS });
    await settle();

    expect(titles()).toEqual(['Book train to Lucerne', 'Packing list', 'Your invoice']);
    expect(row('Book train to Lucerne').textContent).toContain('Task');
    expect(row('Book train to Lucerne').textContent).toContain('Project Holiday');
    expect(row('Packing list').textContent).toContain('Note');
    expect(row('Your invoice').textContent).toContain('Email');
    expect(row('Packing list').querySelector('app-item-type-icon')?.getAttribute('data-type')).toBe(
      'NOTE',
    );
  });

  it('shows the deletion date and the days until the purge', async () => {
    await setup();

    const deleted = row('Book train to Lucerne').querySelector('.deleted')?.textContent ?? '';
    const year = new Date(ITEMS[0].deletedAt).getFullYear();
    expect(deleted).toContain('Deleted');
    expect(deleted).toContain(String(year));
    expect(row('Book train to Lucerne').querySelector('.purge')?.textContent?.trim()).toBe(
      'purged in 29 days',
    );
    expect(row('Packing list').querySelector('.purge')?.textContent?.trim()).toBe(
      'purged in 2 days',
    );
    expect(row('Your invoice').querySelector('.purge')?.textContent?.trim()).toBe('purged today');
  });

  it('marks items purged soon', async () => {
    await setup([...ITEMS, item('HABIT', '0192f0c4-0000-7000-8000-000000000004', 'Run', 1)]);

    expect(row('Book train to Lucerne').querySelector('.purge')?.classList).not.toContain('soon');
    expect(row('Packing list').querySelector('.purge')?.classList).toContain('soon');
    expect(row('Run').querySelector('.purge')?.textContent?.trim()).toBe('purged in 1 day');
  });

  it('says so when the Trash is empty', async () => {
    await setup([]);

    expect(root.querySelector('.items')).toBeNull();
    expect(root.querySelector('.empty')?.textContent).toContain('The Trash is empty');
  });

  it('shows a load error with a retry', async () => {
    await create();
    http
      .expectOne((r) => r.url === LIST)
      .flush({ title: 'Unavailable', status: 503 }, { status: 503, statusText: 'Unavailable' });
    await settle();

    expect(root.querySelector('.load-error')?.textContent).toContain('could not be loaded');
    (root.querySelector('.load-error button') as HTMLButtonElement).click();
    await settle();
    http.expectOne((r) => r.url === LIST).flush({ items: ITEMS });
    await settle();

    expect(root.querySelector('.load-error')).toBeNull();
    expect(titles()).toHaveLength(3);
  });

  it('restores an item, drops it from the list and says so', async () => {
    await setup();

    byLabel('Restore Packing list').click();
    await settle();
    expect(byLabel('Restore Packing list').disabled).toBe(true);
    expect(byLabel('Delete Packing list permanently').disabled).toBe(true);
    http
      .expectOne({ method: 'POST', url: `${LIST}/NOTE/${NOTE_ID}/restore` })
      .flush(null, noContent);
    await settle();

    expect(titles()).toEqual(['Book train to Lucerne', 'Your invoice']);
    expect(notifications.info).toHaveBeenCalledWith('Restored “Packing list”');
  });

  it('moves the focus to the next row after a restore', async () => {
    await setup();

    const button = byLabel('Restore Packing list');
    button.focus();
    button.click();
    await settle();
    http.expectOne(`${LIST}/NOTE/${NOTE_ID}/restore`).flush(null, noContent);
    await settle();

    expect(document.activeElement).toBe(byLabel('Restore Your invoice'));
  });

  it('keeps the item and shows the error when the restore fails', async () => {
    await setup();

    byLabel('Restore Packing list').click();
    await settle();
    http
      .expectOne(`${LIST}/NOTE/${NOTE_ID}/restore`)
      .flush(
        { title: 'Conflict', status: 409, detail: 'The project of this task is deleted' },
        { status: 409, statusText: 'Conflict' },
      );
    await settle();

    expect(titles()).toContain('Packing list');
    expect(byLabel('Restore Packing list').disabled).toBe(false);
    expect(root.querySelector('.error[role="alert"]')?.textContent).toContain(
      '“Packing list” could not be changed: The project of this task is deleted',
    );
    expect(notifications.info).not.toHaveBeenCalled();
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('drops an item that is no longer in the Trash (404) without an error', async () => {
    await setup();

    byLabel('Restore Packing list').click();
    await settle();
    http
      .expectOne(`${LIST}/NOTE/${NOTE_ID}/restore`)
      .flush({ title: 'Not Found', status: 404 }, { status: 404, statusText: 'Not Found' });
    await settle();

    expect(titles()).not.toContain('Packing list');
    expect(root.querySelector('.error[role="alert"]')).toBeNull();
    expect(notifications.info).not.toHaveBeenCalled();
  });

  it('asks before deleting permanently and does nothing on cancel', async () => {
    await setup();

    await openPurge('Packing list');
    expect(document.querySelector('app-purge-dialog')?.textContent).toContain(
      'Delete “Packing list” permanently?',
    );
    dialogButton('Cancel').click();
    await vi.waitFor(() => expect(document.querySelector('app-purge-dialog')).toBeNull());
    await settle();

    http.expectNone(`${LIST}/NOTE/${NOTE_ID}`);
    expect(titles()).toContain('Packing list');
  });

  it('deletes permanently after the confirmation', async () => {
    await setup();

    await openPurge('Your invoice');
    dialogButton('Delete permanently').click();
    const req = await requestTo(`${LIST}/EMAIL/${MAIL_ID}`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, noContent);
    await settle();

    expect(titles()).toEqual(['Book train to Lucerne', 'Packing list']);
    expect(notifications.info).not.toHaveBeenCalled();
  });

  it('keeps the item and shows the error when the permanent delete fails', async () => {
    await setup();

    await openPurge('Your invoice');
    dialogButton('Delete permanently').click();
    (await requestTo(`${LIST}/EMAIL/${MAIL_ID}`)).flush(
      { title: 'Server error', status: 500 },
      { status: 500, statusText: 'Server Error' },
    );
    await settle();

    expect(titles()).toContain('Your invoice');
    expect(root.querySelector('.error[role="alert"]')?.textContent).toContain(
      '“Your invoice” could not be changed: Server error',
    );
  });

  it('loads more pages with the cursor and skips items already shown', async () => {
    await setup(ITEMS.slice(0, 2), 'c2');

    const more = [...root.querySelectorAll<HTMLButtonElement>('.more button')][0];
    expect(more.textContent?.trim()).toBe('Show more');
    more.click();
    await settle();
    http
      .expectOne((r) => r.url === LIST && r.params.get('cursor') === 'c2')
      .flush({ items: [ITEMS[1], ITEMS[2]] });
    await settle();

    expect(titles()).toEqual(['Book train to Lucerne', 'Packing list', 'Your invoice']);
    expect(root.querySelector('.more')).toBeNull();
  });

  it('shows an error when the next page fails and keeps the button', async () => {
    await setup(ITEMS.slice(0, 1), 'c2');

    (root.querySelector('.more button') as HTMLButtonElement).click();
    await settle();
    http
      .expectOne((r) => r.params.get('cursor') === 'c2')
      .flush({ title: 'Unavailable', status: 503 }, { status: 503, statusText: 'Unavailable' });
    await settle();

    expect(root.querySelector('.more .error')?.textContent).toContain('Unavailable');
    expect((root.querySelector('.more button') as HTMLButtonElement).disabled).toBe(false);
    expect(titles()).toHaveLength(1);
  });

  it('marks the countdown urgent from 3 days left on, not at 4', async () => {
    await setup([
      item('TASK', TASK_ID, 'Four days', 4),
      item('NOTE', NOTE_ID, 'Three days', 3),
      item('EMAIL', MAIL_ID, 'Due today', 0),
    ]);

    expect(row('Four days').querySelector('.purge')?.classList).not.toContain('soon');
    expect(row('Three days').querySelector('.purge')?.classList).toContain('soon');
    expect(row('Due today').querySelector('.purge')?.classList).toContain('soon');
  });

  it('labels the actions of each row with its title', async () => {
    await setup();

    const actions = [...row('Your invoice').querySelectorAll('.actions button')].map((b) =>
      b.getAttribute('aria-label'),
    );
    expect(actions).toEqual(['Restore Your invoice', 'Delete Your invoice permanently']);
  });

  it('does nothing when the confirmation is dismissed with Escape', async () => {
    await setup();

    await openPurge('Packing list');
    document
      .querySelector('app-purge-dialog')
      ?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true }));
    await vi.waitFor(() => expect(document.querySelector('app-purge-dialog')).toBeNull());
    await settle();

    http.expectNone(`${LIST}/NOTE/${NOTE_ID}`);
    expect(titles()).toContain('Packing list');
    expect(byLabel('Delete Packing list permanently').disabled).toBe(false);
  });

  it('disables the row while the permanent delete runs', async () => {
    await setup();

    await openPurge('Your invoice');
    dialogButton('Delete permanently').click();
    const req = await requestTo(`${LIST}/EMAIL/${MAIL_ID}`);
    await settle();
    expect(byLabel('Restore Your invoice').disabled).toBe(true);
    expect(byLabel('Delete Your invoice permanently').disabled).toBe(true);
    expect(byLabel('Restore Packing list').disabled).toBe(false);
    req.flush(null, noContent);
    await settle();

    expect(titles()).not.toContain('Your invoice');
  });

  it('drops an item whose permanent delete answers 404 without an error', async () => {
    await setup();

    await openPurge('Packing list');
    dialogButton('Delete permanently').click();
    (await requestTo(`${LIST}/NOTE/${NOTE_ID}`)).flush(
      { title: 'Not Found', status: 404 },
      { status: 404, statusText: 'Not Found' },
    );
    await settle();

    expect(titles()).toEqual(['Book train to Lucerne', 'Your invoice']);
    expect(root.querySelector('.error[role="alert"]')).toBeNull();
  });

  it('runs actions on different rows independently, whatever order they answer in', async () => {
    await setup();

    byLabel('Restore Book train to Lucerne').click();
    byLabel('Restore Packing list').click();
    await settle();
    const first = http.expectOne(`${LIST}/TASK/${TASK_ID}/restore`);
    const second = http.expectOne(`${LIST}/NOTE/${NOTE_ID}/restore`);

    second.flush(null, noContent);
    await settle();
    expect(titles()).toEqual(['Book train to Lucerne', 'Your invoice']);
    expect(byLabel('Restore Book train to Lucerne').disabled).toBe(true);

    first.flush({ title: 'Conflict', status: 409 }, { status: 409, statusText: 'Conflict' });
    await settle();
    expect(titles()).toEqual(['Book train to Lucerne', 'Your invoice']);
    expect(byLabel('Restore Book train to Lucerne').disabled).toBe(false);
    expect(root.querySelector('.error[role="alert"]')?.textContent).toContain(
      '“Book train to Lucerne” could not be changed: Conflict',
    );
    expect(notifications.info).toHaveBeenCalledTimes(1);
    expect(notifications.info).toHaveBeenCalledWith('Restored “Packing list”');
  });

  it('clears the previous action error when the next action starts', async () => {
    await setup();

    byLabel('Restore Packing list').click();
    await settle();
    http
      .expectOne(`${LIST}/NOTE/${NOTE_ID}/restore`)
      .flush({ title: 'Conflict', status: 409 }, { status: 409, statusText: 'Conflict' });
    await settle();
    expect(root.querySelector('.error[role="alert"]')).not.toBeNull();

    byLabel('Restore Your invoice').click();
    await settle();
    expect(root.querySelector('.error[role="alert"]')).toBeNull();
    http.expectOne(`${LIST}/EMAIL/${MAIL_ID}/restore`).flush(null, noContent);
    await settle();

    expect(root.querySelector('.error[role="alert"]')).toBeNull();
    expect(titles()).toEqual(['Book train to Lucerne', 'Packing list']);
  });

  it('shows the unreachable server message when the restore cannot reach it', async () => {
    await setup();

    byLabel('Restore Packing list').click();
    await settle();
    http
      .expectOne(`${LIST}/NOTE/${NOTE_ID}/restore`)
      .error(new ProgressEvent('error'), { status: 0, statusText: '' });
    await settle();

    expect(titles()).toContain('Packing list');
    expect(root.querySelector('.error[role="alert"]')?.textContent).toContain(
      'The server cannot be reached.',
    );
  });

  it('shows a generic message for an unexpected failure', async () => {
    await setup();
    vi.spyOn(TestBed.inject(TrashService), 'restore').mockRejectedValue(new TypeError('boom'));

    byLabel('Restore Packing list').click();
    await settle();

    expect(titles()).toContain('Packing list');
    const alert = root.querySelector('.error[role="alert"]')?.textContent ?? '';
    expect(alert).toContain('“Packing list” could not be changed: Something went wrong.');
    expect(alert).not.toContain('boom');
  });

  it('moves the focus to the previous row when the last row is restored', async () => {
    await setup();

    const button = byLabel('Restore Your invoice');
    button.focus();
    button.click();
    await settle();
    http.expectOne(`${LIST}/EMAIL/${MAIL_ID}/restore`).flush(null, noContent);
    await settle();

    expect(document.activeElement).toBe(byLabel('Restore Packing list'));
  });

  it('leaves the focus alone when the user moved it elsewhere during the restore', async () => {
    await setup();

    byLabel('Restore Packing list').click();
    await settle();
    const elsewhere = byLabel('Delete Book train to Lucerne permanently');
    elsewhere.focus();
    http.expectOne(`${LIST}/NOTE/${NOTE_ID}/restore`).flush(null, noContent);
    await settle();

    expect(titles()).not.toContain('Packing list');
    expect(document.activeElement).toBe(elsewhere);
  });

  it('moves the focus to the heading when the only row is restored', async () => {
    await setup([ITEMS[1]]);

    const button = byLabel('Restore Packing list');
    button.focus();
    button.click();
    await settle();
    http.expectOne(`${LIST}/NOTE/${NOTE_ID}/restore`).flush(null, noContent);
    await settle();

    expect(root.querySelector('.empty')).not.toBeNull();
    expect(document.activeElement).toBe(root.querySelector('h1'));
  });

  it('disables "Show more" while the next page loads and drops it after the last page', async () => {
    await setup(ITEMS.slice(0, 1), 'c2');

    const more = root.querySelector('.more button') as HTMLButtonElement;
    more.click();
    await settle();
    expect(more.disabled).toBe(true);
    http
      .expectOne((r) => r.params.get('cursor') === 'c2')
      .flush({ items: ITEMS.slice(1), nextCursor: 'c3' });
    await settle();
    expect(titles()).toHaveLength(3);
    expect((root.querySelector('.more button') as HTMLButtonElement).disabled).toBe(false);

    (root.querySelector('.more button') as HTMLButtonElement).click();
    await settle();
    http.expectOne((r) => r.params.get('cursor') === 'c3').flush({ items: [] });
    await settle();

    expect(root.querySelector('.more')).toBeNull();
    expect(titles()).toHaveLength(3);
  });

  it('retries the next page after a failure', async () => {
    await setup(ITEMS.slice(0, 1), 'c2');

    (root.querySelector('.more button') as HTMLButtonElement).click();
    await settle();
    http
      .expectOne((r) => r.params.get('cursor') === 'c2')
      .flush({ title: 'Unavailable', status: 503 }, { status: 503, statusText: 'Unavailable' });
    await settle();
    (root.querySelector('.more button') as HTMLButtonElement).click();
    await settle();
    expect(root.querySelector('.more .error')).toBeNull();
    http.expectOne((r) => r.params.get('cursor') === 'c2').flush({ items: ITEMS.slice(1) });
    await settle();

    expect(titles()).toHaveLength(3);
  });
});
