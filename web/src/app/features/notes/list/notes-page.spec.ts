import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NoteResponse } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import NotesPage, { NOTES_VIEW_KEY } from './notes-page';

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
});
