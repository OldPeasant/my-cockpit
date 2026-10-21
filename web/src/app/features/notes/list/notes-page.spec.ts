import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NoteResponse } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import NotesPage, { NOTES_VIEW_KEY } from './notes-page';
import { NotesService } from './notes.service';

const LIST = '/api/v1/notes';
const TAGS = '/api/v1/notes/tags';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

function note(id: string, fields: Partial<NoteResponse> = {}): NoteResponse {
  return {
    id,
    displayTitle: fields.title ?? `Note ${id}`,
    bodyMd: '',
    tags: [],
    pinned: false,
    archived: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

const PACKING = note('0192f0c4-0000-7000-8000-000000000001', {
  title: 'Packing list',
  bodyMd: '- [ ] Passport',
  tags: ['Holiday'],
  updatedAt: '2026-10-03T08:00:00Z',
});
const GARDEN = note('0192f0c4-0000-7000-8000-000000000002', {
  title: 'Garden ideas',
  bodyMd: 'Plant tulips',
  pinned: true,
  updatedAt: '2026-09-01T08:00:00Z',
});
const RECIPES = note('0192f0c4-0000-7000-8000-000000000003', {
  title: 'Recipes',
  bodyMd: 'Lentil soup',
  updatedAt: '2026-10-05T08:00:00Z',
});

describe('NotesPage', () => {
  let fixture: ComponentFixture<NotesPage>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  async function create(notes: NoteResponse[] = [GARDEN, RECIPES, PACKING]): Promise<void> {
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
    fixture = TestBed.createComponent(NotesPage);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne((r) => r.url === TAGS).flush({ items: [{ tag: 'Holiday', count: 1 }] });
    await flushList(notes);
  }

  async function flushList(notes: NoteResponse[], match?: (params: URLSearchParams) => boolean) {
    const req = http.expectOne((r) => r.url === LIST && r.method === 'GET');
    if (match) {
      const params = new URLSearchParams(req.request.params.toString());
      expect(match(params)).toBe(true);
    }
    req.flush({ items: notes });
    await settle();
  }

  async function settle(): Promise<void> {
    await tick();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function titles(): string[] {
    return [...root.querySelectorAll('.card .title')].map((el) => el.textContent?.trim() ?? '');
  }

  function button(selector: string, index = 0): HTMLButtonElement {
    return root.querySelectorAll<HTMLButtonElement>(selector)[index];
  }

  beforeEach(() => localStorage.removeItem(NOTES_VIEW_KEY));
  afterEach(() => http.verify());

  it('shows the pinned notes first under their own heading, the others by last change', async () => {
    await create();

    expect(titles()).toEqual(['Garden ideas', 'Recipes', 'Packing list']);
    const groups = [...root.querySelectorAll('h2.group')].map((h) => h.textContent?.trim());
    expect(groups).toEqual(['Pinned', 'Other notes']);
    expect(root.querySelector('.card.pinned .title')?.textContent).toContain('Garden ideas');
    expect(root.querySelector('a.open')?.getAttribute('href')).toBe(`/notes/${GARDEN.id}`);
  });

  it('has no group headings when nothing is pinned', async () => {
    await create([RECIPES, PACKING]);
    expect(root.querySelectorAll('h2.group')).toHaveLength(0);
    expect(titles()).toEqual(['Recipes', 'Packing list']);
  });

  it('moves a note to the top when it is pinned and back when it is unpinned', async () => {
    await create();

    const pin = button('.card .pin', 2);
    expect(pin.getAttribute('aria-label')).toBe('Pin Packing list');
    pin.click();
    let req = http.expectOne((r) => r.method === 'PATCH' && r.url === `${LIST}/${PACKING.id}`);
    expect(req.request.body).toEqual({ pinned: true });
    expect(req.request.headers.get('If-Match')).toBe('"1"');
    req.flush({ ...PACKING, pinned: true, version: 2 });
    await settle();
    // Both pinned: the more recently changed one first.
    expect(titles()).toEqual(['Packing list', 'Garden ideas', 'Recipes']);

    button('.card .pin', 0).click();
    req = http.expectOne((r) => r.method === 'PATCH' && r.url === `${LIST}/${PACKING.id}`);
    expect(req.request.headers.get('If-Match')).toBe('"2"');
    req.flush({ ...PACKING, pinned: false, version: 3 });
    await settle();
    expect(titles()).toEqual(['Garden ideas', 'Recipes', 'Packing list']);
  });

  it('searches the loaded notes by title, text and tag', async () => {
    await create();
    const search = root.querySelector<HTMLInputElement>('input.search')!;

    search.value = 'soup';
    search.dispatchEvent(new Event('input'));
    await settle();
    expect(titles()).toEqual(['Recipes']);

    search.value = '#holiday';
    search.dispatchEvent(new Event('input'));
    await settle();
    expect(titles()).toEqual(['Packing list']);

    search.value = 'nothing like this';
    search.dispatchEvent(new Event('input'));
    await settle();
    expect(titles()).toEqual([]);
    expect(root.querySelector('.empty')?.textContent).toContain(
      'No notes match “nothing like this”',
    );
  });

  it('filters by tag on the server, and clears the filter when the tag is chosen again', async () => {
    await create();

    const tag = button('.tags .tag');
    expect(tag.textContent).toContain('#Holiday');
    tag.click();
    await flushList([PACKING], (p) => p.get('tag') === 'Holiday' && p.get('archived') === 'false');
    expect(tag.getAttribute('aria-pressed')).toBe('true');
    expect(titles()).toEqual(['Packing list']);

    tag.click();
    await flushList([GARDEN, RECIPES, PACKING], (p) => !p.has('tag'));
    expect(titles()).toHaveLength(3);
  });

  it('shows the archived notes and moves a note back', async () => {
    await create();

    button('.show-archived').click();
    const archivedNote = { ...RECIPES, archived: true };
    await flushList([archivedNote], (p) => p.get('archived') === 'true');
    expect(titles()).toEqual(['Recipes']);
    expect(root.querySelector('.composer')).toBeNull();

    const archive = button('.card .archive');
    expect(archive.getAttribute('aria-label')).toBe('Unarchive Recipes');
    archive.click();
    const req = http.expectOne((r) => r.method === 'PATCH' && r.url === `${LIST}/${RECIPES.id}`);
    expect(req.request.body).toEqual({ archived: false });
    req.flush({ ...RECIPES, version: 2 });
    await settle();
    expect(titles()).toEqual([]);
    expect(notifications.info).toHaveBeenCalledWith('Moved “Recipes” back to Notes');
    expect(root.querySelector('.empty')?.textContent).toContain('No archived notes');
  });

  it('archives a note out of the list', async () => {
    await create();
    button('.card .archive', 1).click();
    http
      .expectOne((r) => r.method === 'PATCH' && r.url === `${LIST}/${RECIPES.id}`)
      .flush({ ...RECIPES, archived: true, version: 2 });
    await settle();
    expect(titles()).toEqual(['Garden ideas', 'Packing list']);
    expect(notifications.info).toHaveBeenCalledWith('Archived “Recipes”');
  });

  it('shows a failed change and reloads when the note changed elsewhere', async () => {
    await create();
    button('.card .pin', 1).click();
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(
        { title: 'Precondition Failed', status: 412, detail: 'The note was changed elsewhere.' },
        { status: 412, statusText: 'Precondition Failed' },
      );
    await settle();
    await flushList([GARDEN, { ...RECIPES, version: 2 }, PACKING]);

    expect(root.querySelector('.error[role=alert]')?.textContent).toContain(
      '“Recipes” could not be changed',
    );
  });

  it('switches between grid and list and remembers the choice', async () => {
    await create();
    expect(root.querySelector('.cards')?.classList.contains('list')).toBe(false);

    button('.view-list').click();
    fixture.detectChanges();
    expect(root.querySelectorAll('.cards.list')).toHaveLength(2);
    expect(localStorage.getItem(NOTES_VIEW_KEY)).toBe('list');
  });

  it('writes a new note with the current tag and puts it into the list', async () => {
    await create();
    button('.tags .tag').click();
    await flushList([PACKING]);

    button('.composer-open').click();
    await settle();
    const save = button('.composer .save');
    expect(save.disabled).toBe(true);
    expect(root.querySelector<HTMLInputElement>('.draft-tags')?.value).toBe('Holiday');

    const body = root.querySelector<HTMLTextAreaElement>('.draft-body')!;
    body.value = 'Sunscreen\nAfter-sun';
    body.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(save.disabled).toBe(false);
    save.click();

    const req = http.expectOne((r) => r.method === 'POST' && r.url === LIST);
    expect(req.request.body).toEqual({
      title: undefined,
      bodyMd: 'Sunscreen\nAfter-sun',
      tags: ['Holiday'],
    });
    const created = note('0192f0c4-0000-7000-8000-000000000004', {
      displayTitle: 'Sunscreen',
      bodyMd: 'Sunscreen\nAfter-sun',
      tags: ['Holiday'],
      updatedAt: '2026-10-21T08:00:00Z',
    });
    req.flush(created, { status: 201, statusText: 'Created' });
    await settle();
    http.expectOne((r) => r.url === TAGS).flush({ items: [{ tag: 'Holiday', count: 2 }] });
    await settle();

    expect(titles()).toEqual(['Sunscreen', 'Packing list']);
    expect(root.querySelector('.draft-body')).toBeNull();
    expect(notifications.info).toHaveBeenCalledWith('Note saved');
  });

  it('keeps the draft and shows the error when saving fails', async () => {
    await create();
    button('.composer-open').click();
    await settle();
    const title = root.querySelector<HTMLInputElement>('.draft-title')!;
    title.value = 'Call list';
    title.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    button('.composer .save').click();

    http
      .expectOne((r) => r.method === 'POST')
      .flush(
        { title: 'Bad Request', status: 400, detail: 'Too many tags.' },
        { status: 400, statusText: 'Bad Request' },
      );
    await settle();

    expect(root.querySelector('.composer .error')?.textContent).toContain('could not be saved');
    expect(root.querySelector<HTMLInputElement>('.draft-title')?.value).toBe('Call list');
  });

  it('offers to try again when the notes cannot be loaded', async () => {
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
    fixture = TestBed.createComponent(NotesPage);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne((r) => r.url === TAGS).flush({ items: [] });
    http
      .expectOne((r) => r.url === LIST)
      .flush({ title: 'Oops', status: 500 }, { status: 500, statusText: 'Server Error' });
    await settle();

    expect(root.querySelector('.load-error')?.textContent).toContain('could not be loaded');
    button('.load-error button').click();
    await flushList([RECIPES]);
    expect(titles()).toEqual(['Recipes']);
  });

  it('shows an empty state without notes', async () => {
    await create([]);
    expect(root.querySelector('.empty')?.textContent).toContain('No notes yet');
  });

  describe('edge cases', () => {
    afterEach(() => vi.restoreAllMocks());

    function typeInto(selector: string, value: string): void {
      const el = root.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
      el.value = value;
      el.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    }

    function keydown(selector: string, init: KeyboardEventInit): void {
      root.querySelector(selector)!.dispatchEvent(new KeyboardEvent('keydown', init));
      fixture.detectChanges();
    }

    function patch(id: string) {
      return http.expectOne((r) => r.method === 'PATCH' && r.url === `${LIST}/${id}`);
    }

    it('shows a loading state until the notes arrive', async () => {
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
      fixture = TestBed.createComponent(NotesPage);
      root = fixture.nativeElement as HTMLElement;
      fixture.detectChanges();
      http.expectOne((r) => r.url === TAGS).flush({ items: [] });
      await tick();
      fixture.detectChanges();
      expect(root.querySelector('.hint')?.textContent).toContain('Loading notes');

      await flushList([RECIPES]);
      expect(root.querySelector('.hint')).toBeNull();
      expect(titles()).toEqual(['Recipes']);
    });

    it('still loads the notes when the tags cannot be loaded, without a tag row', async () => {
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
      fixture = TestBed.createComponent(NotesPage);
      root = fixture.nativeElement as HTMLElement;
      fixture.detectChanges();
      http
        .expectOne((r) => r.url === TAGS)
        .flush({ title: 'Oops', status: 500 }, { status: 500, statusText: 'Server Error' });
      await flushList([GARDEN, RECIPES]);

      expect(root.querySelector('.tags')).toBeNull();
      expect(root.querySelector('.load-error')).toBeNull();
      expect(titles()).toEqual(['Garden ideas', 'Recipes']);
    });

    it('starts in list view when the list view was chosen before', async () => {
      localStorage.setItem(NOTES_VIEW_KEY, 'list');
      await create();
      expect(root.querySelectorAll('.cards.list')).toHaveLength(2);
      expect(button('.view-list').getAttribute('aria-pressed')).toBe('true');

      button('.view-grid').click();
      fixture.detectChanges();
      expect(root.querySelectorAll('.cards.list')).toHaveLength(0);
      expect(localStorage.getItem(NOTES_VIEW_KEY)).toBe('grid');
    });

    it('falls back to the grid for an unknown stored view', async () => {
      localStorage.setItem(NOTES_VIEW_KEY, 'masonry-old');
      await create();
      expect(root.querySelectorAll('.cards.list')).toHaveLength(0);
      expect(button('.view-grid').getAttribute('aria-pressed')).toBe('true');
    });

    it('works with the grid and still switches views when storage is unavailable', async () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new Error('storage disabled');
      });
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('storage disabled');
      });
      await create();
      expect(root.querySelectorAll('.cards.list')).toHaveLength(0);

      button('.view-list').click();
      fixture.detectChanges();
      expect(root.querySelectorAll('.cards.list')).toHaveLength(2);
    });

    it('does not reload when the active view is chosen again', async () => {
      await create();
      button('.show-active').click();
      await settle();
      http.expectNone((r) => r.url === LIST);
      expect(titles()).toHaveLength(3);
    });

    it('shows the newest tag filter when an older response arrives last', async () => {
      await create();
      const tag = button('.tags .tag');
      tag.click(); // filter by Holiday
      tag.click(); // and off again, before the first answer
      const [older, newer] = http.match((r) => r.url === LIST && r.method === 'GET');
      expect(older.request.params.get('tag')).toBe('Holiday');
      expect(newer.request.params.has('tag')).toBe(false);

      newer.flush({ items: [GARDEN, RECIPES, PACKING] });
      await settle();
      older.flush({ items: [PACKING] });
      await settle();

      expect(tag.getAttribute('aria-pressed')).toBe('false');
      expect(titles()).toEqual(['Garden ideas', 'Recipes', 'Packing list']);
    });

    it('ignores the failure of an outdated load', async () => {
      await create();
      const tag = button('.tags .tag');
      tag.click();
      tag.click();
      const [older, newer] = http.match((r) => r.url === LIST && r.method === 'GET');

      newer.flush({ items: [RECIPES] });
      await settle();
      older.flush({ title: 'Oops', status: 500 }, { status: 500, statusText: 'Server Error' });
      await settle();

      expect(root.querySelector('.load-error')).toBeNull();
      expect(titles()).toEqual(['Recipes']);
    });

    it('says when no note has the chosen tag', async () => {
      await create();
      button('.tags .tag').click();
      await flushList([]);
      expect(root.querySelector('.empty')?.textContent).toContain('No notes tagged #Holiday');
    });

    it('drops a note that was deleted elsewhere when pinning it (404)', async () => {
      await create();
      button('.card .pin', 1).click();
      patch(RECIPES.id).flush(
        { title: 'Not Found', status: 404 },
        { status: 404, statusText: 'Not Found' },
      );
      await settle();

      expect(titles()).toEqual(['Garden ideas', 'Packing list']);
      expect(root.querySelector('.error[role=alert]')).toBeNull();
    });

    it('reloads after a conflict (409) as well', async () => {
      await create();
      button('.card .archive', 2).click();
      patch(PACKING.id).flush(
        { title: 'Conflict', status: 409, detail: 'Changed elsewhere.' },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();
      await flushList([GARDEN, RECIPES, { ...PACKING, version: 2 }]);

      expect(root.querySelector('.error[role=alert]')?.textContent).toContain(
        '“Packing list” could not be changed: Changed elsewhere.',
      );
    });

    it('shows a server error without reloading and lets the change be tried again', async () => {
      await create();
      button('.card .pin', 1).click();
      patch(RECIPES.id).flush(
        { title: 'Server Error', status: 500, detail: 'Try later.' },
        { status: 500, statusText: 'Server Error' },
      );
      await settle();
      http.expectNone((r) => r.url === LIST);
      expect(root.querySelector('.error[role=alert]')?.textContent).toContain(
        '“Recipes” could not be changed: Try later.',
      );

      const pin = button('.card .pin', 1);
      expect(pin.disabled).toBe(false);
      pin.click();
      patch(RECIPES.id).flush({ ...RECIPES, pinned: true, version: 2 });
      await settle();
      expect(root.querySelector('.error[role=alert]')).toBeNull();
      // Both pinned now: Recipes changed more recently than Garden ideas.
      expect(titles()).toEqual(['Recipes', 'Garden ideas', 'Packing list']);
      expect(root.querySelectorAll('.card.pinned')).toHaveLength(2);
    });

    it('shows a generic message for an unexpected (non-API) failure', async () => {
      await create();
      vi.spyOn(TestBed.inject(NotesService), 'setPinned').mockRejectedValue(new Error('boom'));
      button('.card .pin', 1).click();
      await settle();
      expect(root.querySelector('.error[role=alert]')?.textContent).toContain(
        '“Recipes” could not be changed: Something went wrong.',
      );
    });

    it('disables the actions of a note while its change runs and sends it only once', async () => {
      await create();
      button('.card .pin', 1).click();
      fixture.detectChanges();
      expect(button('.card .pin', 1).disabled).toBe(true);
      expect(button('.card .archive', 1).disabled).toBe(true);
      expect(button('.card .pin', 0).disabled).toBe(false);
      button('.card .archive', 1).click();

      const req = patch(RECIPES.id);
      req.flush({ ...RECIPES, pinned: true, version: 2 });
      await settle();
      expect(button('.card .pin', 1).disabled).toBe(false);
    });

    it('does not put a note into the archived view when its pin finishes after switching', async () => {
      await create();
      button('.card .pin', 1).click();
      button('.show-archived').click();
      const pending = patch(RECIPES.id);
      const archivedNote = { ...PACKING, archived: true };
      await flushList([archivedNote], (p) => p.get('archived') === 'true');

      pending.flush({ ...RECIPES, pinned: true, version: 2 });
      await settle();
      expect(titles()).toEqual(['Packing list']);
    });

    it('clears the error of an action when switching to the archived notes', async () => {
      await create();
      button('.card .pin', 1).click();
      patch(RECIPES.id).flush(
        { title: 'Server Error', status: 500 },
        { status: 500, statusText: 'Server Error' },
      );
      await settle();
      expect(root.querySelector('.error[role=alert]')).not.toBeNull();

      button('.show-archived').click();
      await flushList([]);
      expect(root.querySelector('.error[role=alert]')).toBeNull();
    });

    it('saves with Ctrl+Enter and Cmd+Enter, but not an empty draft', async () => {
      await create();
      button('.composer-open').click();
      await settle();

      keydown('.draft-body', { key: 'Enter', ctrlKey: true });
      http.expectNone((r) => r.method === 'POST');

      typeInto('.draft-title', '   ');
      typeInto('.draft-body', '  \n ');
      expect(button('.composer .save').disabled).toBe(true);
      keydown('.draft-body', { key: 'Enter', metaKey: true });
      http.expectNone((r) => r.method === 'POST');

      typeInto('.draft-title', '  Book club  ');
      keydown('.draft-body', { key: 'Enter', metaKey: true });
      const req = http.expectOne((r) => r.method === 'POST' && r.url === LIST);
      expect(req.request.body).toEqual({ title: 'Book club', bodyMd: '  \n ', tags: [] });
      req.flush(note('0192f0c4-0000-7000-8000-000000000005', { title: 'Book club' }), {
        status: 201,
        statusText: 'Created',
      });
      await settle();
      http.expectOne((r) => r.url === TAGS).flush({ items: [] });
      await settle();
      expect(titles()).toContain('Book club');
    });

    it('sends a note only once when saved twice quickly', async () => {
      await create();
      button('.composer-open').click();
      await settle();
      typeInto('.draft-body', 'Call the plumber');
      button('.composer .save').click();
      keydown('.draft-body', { key: 'Enter', ctrlKey: true });

      const [req, ...more] = http.match((r) => r.method === 'POST');
      expect(more).toHaveLength(0);
      fixture.detectChanges();
      expect(button('.composer .save').disabled).toBe(true);
      req.flush(note('0192f0c4-0000-7000-8000-000000000006', { bodyMd: 'Call the plumber' }), {
        status: 201,
        statusText: 'Created',
      });
      await settle();
      http.expectOne((r) => r.url === TAGS).flush({ items: [] });
    });

    it('discards the draft with Escape and starts empty next time', async () => {
      await create();
      button('.composer-open').click();
      await settle();
      typeInto('.draft-title', 'Half a thought');
      keydown('.draft-body', { key: 'Escape' });
      expect(root.querySelector('.draft-title')).toBeNull();

      button('.composer-open').click();
      await settle();
      expect(root.querySelector<HTMLInputElement>('.draft-title')?.value).toBe('');
      http.expectNone((r) => r.method === 'POST');
    });

    it('focuses the text when the composer opens', async () => {
      await create();
      button('.composer-open').click();
      await settle();
      expect(document.activeElement).toBe(root.querySelector('.draft-body'));
    });

    it('cancels the composer and clears its error', async () => {
      await create();
      button('.composer-open').click();
      await settle();
      typeInto('.draft-body', 'Draft');
      button('.composer .save').click();
      http
        .expectOne((r) => r.method === 'POST')
        .flush({ title: 'Bad', status: 400 }, { status: 400, statusText: 'Bad Request' });
      await settle();
      expect(root.querySelector('.composer .error')).not.toBeNull();

      [...root.querySelectorAll<HTMLButtonElement>('.composer button')]
        .find((b) => b.textContent?.includes('Cancel'))!
        .click();
      fixture.detectChanges();
      button('.composer-open').click();
      await settle();
      expect(root.querySelector('.composer .error')).toBeNull();
      expect(root.querySelector<HTMLTextAreaElement>('.draft-body')?.value).toBe('');
    });

    it('cannot cancel or escape while a note is saved, so a failed save keeps the draft', async () => {
      await create();
      button('.composer-open').click();
      await settle();
      typeInto('.draft-body', 'Call the plumber');
      button('.composer .save').click();
      fixture.detectChanges();

      const cancel = button('.composer .cancel');
      expect(cancel.disabled).toBe(true);
      cancel.click();
      keydown('.draft-body', { key: 'Escape' });
      expect(root.querySelector<HTMLTextAreaElement>('.draft-body')?.value).toBe(
        'Call the plumber',
      );

      http
        .expectOne((r) => r.method === 'POST')
        .flush({ title: 'Oops', status: 500 }, { status: 500, statusText: 'Server Error' });
      await settle();
      expect(root.querySelector('.composer .error')?.textContent).toContain('could not be saved');
      expect(root.querySelector<HTMLTextAreaElement>('.draft-body')?.value).toBe(
        'Call the plumber',
      );
      expect(button('.composer .cancel').disabled).toBe(false);
    });

    it('shows a note created while the list reloads by loading it again', async () => {
      await create();
      button('.composer-open').click();
      await settle();
      typeInto('.draft-body', 'Sunscreen');
      button('.tags .tag').click();
      fixture.detectChanges();
      // The list of the new tag filter is still loading when the note is saved.
      const [stale] = http.match((r) => r.url === LIST && r.method === 'GET');
      button('.composer .save').click();
      const created = note('0192f0c4-0000-7000-8000-00000000000a', {
        displayTitle: 'Sunscreen',
        bodyMd: 'Sunscreen',
        tags: ['Holiday'],
        updatedAt: '2026-10-21T08:00:00Z',
      });
      http
        .expectOne((r) => r.method === 'POST')
        .flush(created, { status: 201, statusText: 'Created' });
      await settle();
      http.expectOne((r) => r.url === TAGS).flush({ items: [] });

      // The load started before the note existed does not count; the new one does.
      stale.flush({ items: [PACKING] });
      await settle();
      await flushList([created, PACKING], (p) => p.get('tag') === 'Holiday');
      expect(titles()).toEqual(['Sunscreen', 'Packing list']);
      expect(notifications.info).toHaveBeenCalledWith('Note saved');
    });

    it('keeps typed tags when the composer opens while a tag filter is active', async () => {
      await create();
      button('.composer-open').click();
      await settle();
      typeInto('.draft-tags', 'work');
      button('.tags .tag').click();
      await flushList([PACKING]);
      // The composer stays open; its typed tags are not replaced by the filter.
      expect(root.querySelector<HTMLInputElement>('.draft-tags')?.value).toBe('work');
    });

    it('does not add a new note to a tag-filtered list it does not belong to', async () => {
      await create();
      button('.tags .tag').click();
      await flushList([PACKING]);
      button('.composer-open').click();
      await settle();
      typeInto('.draft-tags', '#work, Work');
      typeInto('.draft-body', 'Quarterly plan');
      button('.composer .save').click();

      const req = http.expectOne((r) => r.method === 'POST');
      expect(req.request.body.tags).toEqual(['work']);
      req.flush(
        note('0192f0c4-0000-7000-8000-000000000007', {
          bodyMd: 'Quarterly plan',
          tags: ['work'],
        }),
        { status: 201, statusText: 'Created' },
      );
      await settle();
      http.expectOne((r) => r.url === TAGS).flush({ items: [] });
      await settle();

      expect(titles()).toEqual(['Packing list']);
      expect(notifications.info).toHaveBeenCalledWith('Note saved');
    });

    it('matches the tag filter regardless of case for a new note', async () => {
      await create();
      button('.tags .tag').click();
      await flushList([PACKING]);
      button('.composer-open').click();
      await settle();
      typeInto('.draft-tags', 'HOLIDAY');
      typeInto('.draft-body', 'Travel insurance');
      button('.composer .save').click();
      http
        .expectOne((r) => r.method === 'POST')
        .flush(
          note('0192f0c4-0000-7000-8000-000000000008', {
            displayTitle: 'Travel insurance',
            bodyMd: 'Travel insurance',
            tags: ['HOLIDAY'],
            updatedAt: '2026-10-21T08:00:00Z',
          }),
          { status: 201, statusText: 'Created' },
        );
      await settle();
      http.expectOne((r) => r.url === TAGS).flush({ items: [] });
      await settle();
      expect(titles()).toEqual(['Travel insurance', 'Packing list']);
    });

    it('searches across pinned and other notes and keeps pinned first', async () => {
      const pinnedSoup = note('0192f0c4-0000-7000-8000-000000000009', {
        title: 'Soup ideas',
        pinned: true,
        updatedAt: '2026-08-01T08:00:00Z',
      });
      await create([GARDEN, RECIPES, PACKING, pinnedSoup]);
      typeInto('input.search', '  SOUP ');
      await settle();
      expect(titles()).toEqual(['Soup ideas', 'Recipes']);
      const groups = [...root.querySelectorAll('h2.group')].map((h) => h.textContent?.trim());
      expect(groups).toEqual(['Pinned', 'Other notes']);
    });

    it('keeps the search when the tag filter changes', async () => {
      await create();
      typeInto('input.search', 'passport');
      await settle();
      button('.tags .tag').click();
      await flushList([PACKING, { ...RECIPES, tags: ['Holiday'] }]);
      expect(titles()).toEqual(['Packing list']);
      expect(root.querySelector<HTMLInputElement>('input.search')?.value).toBe('passport');
    });
  });
});
