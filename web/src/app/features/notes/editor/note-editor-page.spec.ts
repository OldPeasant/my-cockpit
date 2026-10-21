import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EditorView } from '@codemirror/view';
import { Component } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { LinkResponse, NoteResponse } from '../../../api/generated';
import { ApiError, NotificationService, provideHttp } from '../../../core/http';
import { MarkdownEditor } from './markdown-editor';
import NoteEditorPage, { AUTOSAVE_DEBOUNCE_MS, NOTE_EDITOR_MODE_KEY } from './note-editor-page';

const ID = '0192f0c4-0000-7000-8000-000000000001';
const OTHER_ID = '0192f0c4-0000-7000-8000-000000000002';
const TASK_ID = '0192f0c4-0000-7000-8000-0000000000a1';
const URL = `/api/v1/notes/${ID}`;

function note(fields: Partial<NoteResponse> = {}): NoteResponse {
  return {
    id: ID,
    title: 'Packing list',
    displayTitle: 'Packing list',
    bodyMd: '- [ ] Passport\n- [ ] Charger',
    tags: [],
    pinned: false,
    archived: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

function taskLink(title: string): LinkResponse {
  return {
    id: '0192f0c4-0000-7000-8000-0000000000c1',
    direction: 'OUTGOING',
    origin: 'SYNC',
    createdAt: '2026-10-01T08:00:00Z',
    source: { type: 'NOTE', id: ID, available: true, title: 'Packing list' },
    target: { type: 'TASK', id: TASK_ID, available: true, title, deepLink: `/tasks/${TASK_ID}` },
  };
}

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

/** Another page, to leave the editor. */
@Component({ template: 'Elsewhere' })
class Elsewhere {}

const ROUTES = [
  { path: 'notes/:id', component: NoteEditorPage },
  { path: 'elsewhere', component: Elsewhere },
];

const CONFLICT = [
  { type: 'about:blank', title: 'Conflict', status: 409 },
  { status: 409, statusText: 'Conflict' },
] as const;

describe('NoteEditorPage', () => {
  let harness: RouterTestingHarness;
  let root: HTMLElement;
  let http: HttpTestingController;

  async function open(loaded: NoteResponse = note(), links: LinkResponse[] = []): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter(ROUTES),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    void harness.navigateByUrl(`/notes/${ID}`);
    await tick();
    http.expectOne((r) => r.url === URL && r.method === 'GET').flush(loaded);
    await settle();
    flushLinks(links);
    await settle();
    root = harness.routeNativeElement as HTMLElement;
  }

  /** Answers the link list requests of the page (chips) and its Linked items panel. */
  function flushLinks(links: LinkResponse[] = []): void {
    for (const req of http.match((r) => r.url === '/api/v1/links')) req.flush({ items: links });
  }

  async function settle(): Promise<void> {
    await tick();
    harness.detectChanges();
    await harness.fixture.whenStable();
  }

  function editor(): MarkdownEditor {
    return harness.fixture.debugElement.query(By.directive(MarkdownEditor))
      .componentInstance as MarkdownEditor;
  }

  function type(body: string): void {
    editor().valueChange.emit(body);
  }

  function state(): string {
    return root.querySelector('.save-state')?.textContent?.trim() ?? '';
  }

  function button(text: string): HTMLButtonElement {
    const found = [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === text);
    if (!found) throw new Error(`no button “${text}”`);
    return found as HTMLButtonElement;
  }

  afterEach(() => {
    vi.useRealTimers();
    localStorage.removeItem(NOTE_EDITOR_MODE_KEY);
    TestBed.inject(MatDialog).closeAll();
  });

  it('shows the note in the editor and the preview', async () => {
    await open();
    expect((root.querySelector('.title') as HTMLInputElement).value).toBe('Packing list');
    expect(editor().value()).toBe('- [ ] Passport\n- [ ] Charger');
    expect(root.querySelectorAll('app-note-markdown-view input[type=checkbox]')).toHaveLength(2);
    expect(state()).toBe('Saved');
  });

  it('autosaves once after the debounce, with the version as If-Match', async () => {
    await open();
    vi.useFakeTimers();
    type('- [ ] Passport\n- [ ] Charger\n- [ ] Sun');
    harness.detectChanges();
    expect(state()).toBe('Unsaved changes');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS - 100);
    type('- [ ] Passport\n- [ ] Charger\n- [ ] Sunscreen');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS - 100);
    http.expectNone((r) => r.method === 'PATCH');
    await vi.advanceTimersByTimeAsync(100);

    const save = http.expectOne((r) => r.url === URL && r.method === 'PATCH');
    expect(save.request.headers.get('If-Match')).toBe('"1"');
    expect(save.request.body).toEqual({
      title: 'Packing list',
      bodyMd: '- [ ] Passport\n- [ ] Charger\n- [ ] Sunscreen',
    });
    harness.detectChanges();
    expect(state()).toBe('Saving…');
    save.flush(note({ bodyMd: save.request.body.bodyMd, version: 2 }));
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();
    expect(state()).toBe('Saved');

    // The next save sends the new version.
    type('Done');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    const next = http.expectOne((r) => r.method === 'PATCH');
    expect(next.request.headers.get('If-Match')).toBe('"2"');
  });

  it('saves edits made while a save runs after that save', async () => {
    await open();
    vi.useFakeTimers();
    type('one');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    const first = http.expectOne((r) => r.method === 'PATCH');
    type('one two');
    first.flush(note({ bodyMd: 'one', version: 2 }));
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    const second = http.expectOne((r) => r.method === 'PATCH');
    expect(second.request.body.bodyMd).toBe('one two');
    expect(second.request.headers.get('If-Match')).toBe('"2"');
  });

  it('removes the title with null when it is cleared', async () => {
    await open();
    vi.useFakeTimers();
    const title = root.querySelector('.title') as HTMLInputElement;
    title.value = '  ';
    title.dispatchEvent(new Event('input'));
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    const save = http.expectOne((r) => r.method === 'PATCH');
    expect(save.request.body.title).toBeNull();
  });

  it('on a conflict stops autosaving and keeps my version on request', async () => {
    await open();
    vi.useFakeTimers();
    type('mine');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(
        { type: 'about:blank', title: 'Precondition Failed', status: 412 },
        { status: 412, statusText: 'Precondition Failed' },
      );
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();
    expect(root.querySelector('.banner.conflict')?.textContent).toContain(
      'This note was changed somewhere else',
    );
    expect(state()).toBe('Not saved: changed elsewhere');

    type('mine, more');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
    http.expectNone((r) => r.method === 'PATCH');

    button('Save my version').click();
    await vi.advanceTimersByTimeAsync(0);
    http
      .expectOne((r) => r.url === URL && r.method === 'GET')
      .flush(note({ bodyMd: 'theirs', version: 5 }));
    await vi.advanceTimersByTimeAsync(0);
    const save = http.expectOne((r) => r.method === 'PATCH');
    expect(save.request.headers.get('If-Match')).toBe('"5"');
    expect(save.request.body.bodyMd).toBe('mine, more');
    save.flush(note({ bodyMd: 'mine, more', version: 6 }));
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();
    expect(root.querySelector('.banner.conflict')).toBeNull();
    expect(state()).toBe('Saved');
  });

  it('on a conflict discards my edits on request', async () => {
    await open();
    vi.useFakeTimers();
    type('mine');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(
        { type: 'about:blank', title: 'Precondition Failed', status: 412 },
        { status: 412, statusText: 'Precondition Failed' },
      );
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();

    button('Discard my edits').click();
    await vi.advanceTimersByTimeAsync(0);
    http
      .expectOne((r) => r.url === URL && r.method === 'GET')
      .flush(note({ bodyMd: 'theirs', version: 5 }));
    await vi.advanceTimersByTimeAsync(0);
    flushLinks();
    harness.detectChanges();
    expect(editor().value()).toBe('theirs');
    expect(state()).toBe('Saved');
    http.expectNone((r) => r.method === 'PATCH');
  });

  it('shows other save errors with a retry', async () => {
    await open();
    vi.useFakeTimers();
    type('mine');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();
    expect(state()).toBe('Not saved');

    button('Try again').click();
    await vi.advanceTimersByTimeAsync(0);
    const retry = http.expectOne((r) => r.method === 'PATCH');
    expect(retry.request.body.bodyMd).toBe('mine');
  });

  it('toggles a checklist item in the preview and saves it right away', async () => {
    await open();
    const boxes = root.querySelectorAll<HTMLInputElement>('app-note-markdown-view input');
    boxes[1].click();
    await tick();
    const save = http.expectOne((r) => r.method === 'PATCH');
    expect(save.request.body.bodyMd).toBe('- [ ] Passport\n- [x] Charger');
    save.flush(note({ bodyMd: '- [ ] Passport\n- [x] Charger', version: 2 }));
    await settle();
    expect(editor().value()).toBe('- [ ] Passport\n- [x] Charger');
    expect(state()).toBe('Saved');
    const checked = root.querySelectorAll<HTMLInputElement>('app-note-markdown-view input');
    expect([...checked].map((box) => box.checked)).toEqual([false, true]);
  });

  it('renders inline links as chips with the titles of the linked items', async () => {
    await open(note({ bodyMd: `Call [[task:${TASK_ID}]]` }), [taskLink('Book flights')]);
    const chip = root.querySelector('app-note-markdown-view a.chip');
    expect(chip?.textContent).toBe('Task Book flights');
    expect(chip?.getAttribute('data-route')).toBe(`/tasks/${TASK_ID}`);
  });

  it('shows the note’s linked items in the Linked items panel', async () => {
    await open(note({ bodyMd: `Call [[task:${TASK_ID}]]` }), [taskLink('Book flights')]);
    const panel = root.querySelector('aside.side app-linked-items');
    expect(panel).not.toBeNull();
    expect(panel?.textContent).toContain('Book flights');
  });

  it('shows chips with the type only when the links cannot be loaded', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter(ROUTES),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    void harness.navigateByUrl(`/notes/${ID}`);
    await tick();
    http.expectOne((r) => r.url === URL).flush(note({ bodyMd: `Call [[task:${TASK_ID}]]` }));
    await settle();
    for (const req of http.match((r) => r.url === '/api/v1/links')) {
      req.flush(null, { status: 503, statusText: 'Service Unavailable' });
    }
    await settle();
    root = harness.routeNativeElement as HTMLElement;
    const chip = root.querySelector('app-note-markdown-view .chip');
    expect(chip?.tagName).toBe('SPAN');
    expect(chip?.textContent).toBe('Task');
    expect(state()).toBe('Saved');
  });

  it('offers link targets of all types but the note itself, and remembers their titles', async () => {
    await open();
    const found = editor().searchLinks()!('book');
    http
      .expectOne((r) => r.url === '/api/v1/search' && r.params.get('q') === 'book')
      .flush({
        groups: [
          {
            type: 'TASK',
            items: [
              {
                type: 'TASK',
                id: TASK_ID,
                title: 'Book flights',
                deepLink: `/tasks/${TASK_ID}`,
                updatedAt: '2026-10-01T08:00:00Z',
              },
            ],
          },
          {
            type: 'NOTE',
            items: [
              { type: 'NOTE', id: ID, title: 'Packing list', updatedAt: '2026-10-01T08:00:00Z' },
            ],
          },
        ],
      });
    expect(await found).toEqual([
      { type: 'TASK', id: TASK_ID, title: 'Book flights', detail: 'Task' },
    ]);

    type(`See [[task:${TASK_ID}]]`);
    await settle();
    expect(root.querySelector('app-note-markdown-view a.chip')?.textContent).toBe(
      'Task Book flights',
    );
  });

  it('reloads the links when a save changes the inline links', async () => {
    await open();
    vi.useFakeTimers();
    type(`See [[task:${TASK_ID}]]`);
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(note({ bodyMd: `See [[task:${TASK_ID}]]`, version: 2 }));
    await vi.advanceTimersByTimeAsync(0);
    // The page's chips and the Linked items panel.
    expect(http.match((r) => r.url === '/api/v1/links')).toHaveLength(2);
  });

  it('switches between write, side by side and preview, and remembers the choice', async () => {
    await open();
    expect(root.querySelector('.panes')?.getAttribute('data-mode')).toBe('split');
    button('Preview').click();
    await settle();
    expect((root.querySelector('app-markdown-editor') as HTMLElement).hidden).toBe(true);
    expect(root.querySelector('app-note-markdown-view')).not.toBeNull();
    expect(localStorage.getItem(NOTE_EDITOR_MODE_KEY)).toBe('preview');
    button('Write').click();
    await settle();
    expect((root.querySelector('app-markdown-editor') as HTMLElement).hidden).toBe(false);
    expect(root.querySelector('app-note-markdown-view')).toBeNull();
  });

  it('uploads pasted images to the note', async () => {
    await open();
    const markdown = editor().uploadImage()!(
      new File(['x'], 'tent.png', { type: 'image/png' }),
      '![Uploading tent.png…](uploading:0)',
    );
    const upload = http.expectOne((r) => r.url === `${URL}/images` && r.method === 'POST');
    expect(upload.request.body).toBeInstanceOf(FormData);
    upload.flush({
      fileId: TASK_ID,
      markdown: `![tent.png](cockpit-file:${TASK_ID})`,
      reference: `cockpit-file:${TASK_ID}`,
      mime: 'image/png',
      name: 'tent.png',
      size: 1,
    });
    expect(await markdown).toBe(`![tent.png](cockpit-file:${TASK_ID})`);
  });

  it('restores a revision from the dialog', async () => {
    await open();
    button('Earlier versions').click();
    await settle();
    http
      .expectOne((r) => r.url === `${URL}/revisions`)
      .flush({
        items: [
          {
            number: 1,
            bodyMd: 'first',
            savedAt: '2026-09-01T08:00:00Z',
            replacedAt: '2026-09-02T08:00:00Z',
          },
          {
            number: 2,
            bodyMd: 'second',
            savedAt: '2026-09-02T08:00:00Z',
            replacedAt: '2026-09-03T08:00:00Z',
          },
        ],
      });
    await settle();
    const dialog = document.querySelector('mat-dialog-container') as HTMLElement;
    // Newest first, and selected.
    expect(dialog.querySelector('app-note-markdown-view')?.textContent).toBe('second');
    const older = [...dialog.querySelectorAll<HTMLButtonElement>('button.revision')][1];
    older.click();
    await settle();
    expect(dialog.querySelector('app-note-markdown-view')?.textContent).toBe('first');
    dialog.querySelector<HTMLButtonElement>('button.restore')!.click();

    // The dialog reports its result after its close animation.
    const restore = await vi.waitFor(() =>
      http.expectOne((r) => r.url === `${URL}/revisions/1/restore`),
    );
    expect(restore.request.headers.get('If-Match')).toBe('"1"');
    restore.flush(note({ bodyMd: 'first', version: 2 }));
    await settle();
    flushLinks();
    expect(editor().value()).toBe('first');
    expect(state()).toBe('Saved');
  });

  it('shows a load error with a retry', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'notes/:id', component: NoteEditorPage }]),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    void harness.navigateByUrl(`/notes/${ID}`);
    await tick();
    http
      .expectOne((r) => r.url === URL)
      .flush(
        { type: 'about:blank', title: 'Not Found', status: 404, detail: 'No such note' },
        { status: 404, statusText: 'Not Found' },
      );
    await settle();
    root = harness.routeNativeElement as HTMLElement;
    expect(root.textContent).toContain('The note could not be loaded');
    button('Try again').click();
    await tick();
    http.expectOne((r) => r.url === URL).flush(note());
    await settle();
    expect((root.querySelector('.title') as HTMLInputElement).value).toBe('Packing list');
  });

  /** Fails the pending save with a conflict and waits for the page to show it. */
  async function conflictOnSave(): Promise<void> {
    http.expectOne((r) => r.method === 'PATCH').flush(...CONFLICT);
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();
  }

  it('treats a 409 like a 412: conflict banner, no further autosave', async () => {
    await open();
    vi.useFakeTimers();
    type('mine');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    await conflictOnSave();
    expect(state()).toBe('Not saved: changed elsewhere');
    expect(root.querySelector('.banner.conflict')).not.toBeNull();
    expect(root.querySelector('.retry')).toBeNull();
    type('mine again');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
    http.expectNone((r) => r.method === 'PATCH');
  });

  it('does not save a checklist toggle while in conflict', async () => {
    await open();
    vi.useFakeTimers();
    type('- [ ] Passport\n- [ ] Charger\n- [ ] Sun');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    await conflictOnSave();
    root.querySelectorAll<HTMLInputElement>('app-note-markdown-view input')[0].click();
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    http.expectNone((r) => r.method === 'PATCH');
    harness.detectChanges();
    expect(editor().value()).toBe('- [x] Passport\n- [ ] Charger\n- [ ] Sun');
    expect(state()).toBe('Not saved: changed elsewhere');
  });

  it('keeps the conflict when reloading the newer version fails', async () => {
    await open();
    vi.useFakeTimers();
    type('mine');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    await conflictOnSave();

    button('Discard my edits').click();
    await vi.advanceTimersByTimeAsync(0);
    http
      .expectOne((r) => r.url === URL && r.method === 'GET')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();
    expect(editor().value()).toBe('mine');
    expect(state()).toBe('Not saved: changed elsewhere');
    expect(root.querySelectorAll('.banner[role=alert]')).toHaveLength(2);

    button('Save my version').click();
    await vi.advanceTimersByTimeAsync(0);
    http
      .expectOne((r) => r.url === URL && r.method === 'GET')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();
    http.expectNone((r) => r.method === 'PATCH');
    expect(state()).toBe('Not saved: changed elsewhere');
  });

  it('saves pending edits before opening another note', async () => {
    await open();
    vi.useFakeTimers();
    type('edited before leaving');
    void harness.navigateByUrl(`/notes/${OTHER_ID}`);
    await vi.advanceTimersByTimeAsync(0);
    // The edits of the first note are saved before the other note is loaded.
    http.expectNone((r) => r.url === `/api/v1/notes/${OTHER_ID}`);
    const save = http.expectOne((r) => r.url === URL && r.method === 'PATCH');
    expect(save.request.body.bodyMd).toBe('edited before leaving');
    expect(save.request.headers.get('If-Match')).toBe('"1"');
    save.flush(note({ bodyMd: 'edited before leaving', version: 2 }));
    await vi.advanceTimersByTimeAsync(0);
    http
      .expectOne((r) => r.url === `/api/v1/notes/${OTHER_ID}` && r.method === 'GET')
      .flush(note({ id: OTHER_ID, title: 'Recipes', bodyMd: 'Soup', version: 9 }));
    await vi.advanceTimersByTimeAsync(0);
    flushLinks();
    harness.detectChanges();
    expect(editor().value()).toBe('Soup');
    expect((root.querySelector('.title') as HTMLInputElement).value).toBe('Recipes');
    expect(state()).toBe('Saved');

    // Nothing more is saved for the first note, and edits now go to the other one.
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
    http.expectNone((r) => r.url === URL && r.method === 'PATCH');
    type('Soup and bread');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    const next = http.expectOne((r) => r.method === 'PATCH');
    expect(next.request.url).toBe(`/api/v1/notes/${OTHER_ID}`);
    expect(next.request.headers.get('If-Match')).toBe('"9"');
  });

  it('ignores a note that arrives after another one was opened', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter(ROUTES),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    void harness.navigateByUrl(`/notes/${ID}`);
    await tick();
    const slow = http.expectOne((r) => r.url === URL);
    void harness.navigateByUrl(`/notes/${OTHER_ID}`);
    await tick();
    http
      .expectOne((r) => r.url === `/api/v1/notes/${OTHER_ID}`)
      .flush(note({ id: OTHER_ID, title: 'Recipes', bodyMd: 'Soup' }));
    await settle();
    slow.flush(note({ bodyMd: 'stale' }));
    await settle();
    flushLinks();
    root = harness.routeNativeElement as HTMLElement;
    expect(editor().value()).toBe('Soup');
    expect((root.querySelector('.title') as HTMLInputElement).value).toBe('Recipes');
  });

  it('saves pending edits when leaving the page', async () => {
    await open();
    vi.useFakeTimers();
    type('edited before leaving');
    void harness.navigateByUrl('/elsewhere');
    await vi.advanceTimersByTimeAsync(0);
    expect(harness.routeNativeElement?.textContent).toBe('Elsewhere');
    const save = http.expectOne((r) => r.url === URL && r.method === 'PATCH');
    expect(save.request.body.bodyMd).toBe('edited before leaving');
    save.flush(note({ bodyMd: 'edited before leaving', version: 2 }));
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
    http.expectNone((r) => r.method === 'PATCH');
  });

  it('when leaving during a save, saves the edits made meanwhile after it', async () => {
    await open();
    vi.useFakeTimers();
    type('one');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    const first = http.expectOne((r) => r.method === 'PATCH');
    type('one two');
    void harness.navigateByUrl('/elsewhere');
    await vi.advanceTimersByTimeAsync(0);
    http.expectNone((r) => r.method === 'PATCH');
    first.flush(note({ bodyMd: 'one', version: 2 }));
    await vi.advanceTimersByTimeAsync(0);
    const second = http.expectOne((r) => r.method === 'PATCH');
    expect(second.request.body.bodyMd).toBe('one two');
    expect(second.request.headers.get('If-Match')).toBe('"2"');
    second.flush(note({ bodyMd: 'one two', version: 3 }));
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
    http.expectNone((r) => r.method === 'PATCH');
  });

  it('does not leave an upload placeholder in the saved note when leaving during an upload', async () => {
    await open(note({ bodyMd: 'Trip' }));
    const dom = root.querySelector('.cm-editor') as HTMLElement;
    const view = EditorView.findFromDOM(dom)!;
    view.dispatch({ selection: { anchor: 4 } });
    const paste = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent;
    const image = new File(['x'], 'tent.png', { type: 'image/png' });
    Object.defineProperty(paste, 'clipboardData', { value: { files: [image], getData: () => '' } });
    view.contentDOM.dispatchEvent(paste);
    const upload = http.expectOne((r) => r.url === `${URL}/images`);

    // Leave while the image is still uploading.
    void harness.navigateByUrl('/elsewhere');
    await settle();
    const saved: string[] = [];
    const answerSaves = () => {
      for (const save of http.match((r) => r.url === URL && r.method === 'PATCH')) {
        saved.push(save.request.body.bodyMd);
        save.flush(note({ bodyMd: save.request.body.bodyMd, version: saved.length + 1 }));
      }
    };
    answerSaves();
    await settle();
    upload.flush({
      fileId: TASK_ID,
      markdown: `![tent.png](cockpit-file:${TASK_ID})`,
      reference: `cockpit-file:${TASK_ID}`,
      mime: 'image/png',
      name: 'tent.png',
      size: 1,
    });
    await settle();
    answerSaves();
    await settle();
    answerSaves();

    // What the server keeps last must not be the "Uploading…" placeholder.
    expect(saved.at(-1) ?? 'Trip').not.toContain('(uploading:');
  });

  /** Pastes an image at the end of the text; returns its pending upload request. */
  function pasteImage(): TestRequest {
    const view = EditorView.findFromDOM(root.querySelector('.cm-editor') as HTMLElement)!;
    view.dispatch({ selection: { anchor: view.state.doc.length } });
    const paste = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent;
    const image = new File(['x'], 'tent.png', { type: 'image/png' });
    Object.defineProperty(paste, 'clipboardData', { value: { files: [image], getData: () => '' } });
    view.contentDOM.dispatchEvent(paste);
    return http.expectOne((r) => r.url === `${URL}/images`);
  }

  const TENT = {
    fileId: TASK_ID,
    markdown: `![tent.png](cockpit-file:${TASK_ID})`,
    reference: `cockpit-file:${TASK_ID}`,
    mime: 'image/png',
    name: 'tent.png',
    size: 1,
  };

  it('does not autosave the placeholder of an image that is still uploading', async () => {
    await open(note({ bodyMd: 'Trip' }));
    vi.useFakeTimers();
    const upload = pasteImage();
    harness.detectChanges();
    expect(editor().value()).toContain('(uploading:');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    const first = http.expectOne((r) => r.method === 'PATCH');
    expect(first.request.body.bodyMd).toBe('Trip\n\n');
    first.flush(note({ bodyMd: 'Trip\n\n', version: 2 }));
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();
    expect(state()).toBe('Saved');

    upload.flush(TENT);
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    const second = http.expectOne((r) => r.method === 'PATCH');
    expect(second.request.body.bodyMd).toBe(`Trip\n${TENT.markdown}\n`);
  });

  it('puts an image still uploading into the note before opening another note', async () => {
    await open(note({ bodyMd: 'Trip' }));
    const upload = pasteImage();
    void harness.navigateByUrl(`/notes/${OTHER_ID}`);
    await settle();
    const first = http.expectOne((r) => r.url === URL && r.method === 'PATCH');
    expect(first.request.body.bodyMd).toBe('Trip\n\n');
    first.flush(note({ bodyMd: 'Trip\n\n', version: 2 }));
    await settle();
    // The other note waits for the upload.
    http.expectNone((r) => r.url === `/api/v1/notes/${OTHER_ID}`);
    upload.flush(TENT);
    await settle();
    const second = http.expectOne((r) => r.url === URL && r.method === 'PATCH');
    expect(second.request.body.bodyMd).toBe(`Trip\n${TENT.markdown}\n`);
    expect(second.request.headers.get('If-Match')).toBe('"2"');
    second.flush(note({ bodyMd: second.request.body.bodyMd, version: 3 }));
    await settle();
    http
      .expectOne((r) => r.url === `/api/v1/notes/${OTHER_ID}` && r.method === 'GET')
      .flush(note({ id: OTHER_ID, title: 'Recipes', bodyMd: 'Soup', version: 9 }));
    await settle();
    flushLinks();
    expect(editor().value()).toBe('Soup');
  });

  it('when leaving during an upload that then fails, saves the note without its placeholder', async () => {
    await open(note({ bodyMd: 'Trip' }));
    const upload = pasteImage();
    void harness.navigateByUrl('/elsewhere');
    await settle();
    const save = http.expectOne((r) => r.url === URL && r.method === 'PATCH');
    expect(save.request.body.bodyMd).toBe('Trip\n\n');
    save.flush(note({ bodyMd: 'Trip\n\n', version: 2 }));
    await settle();
    upload.flush(null, { status: 413, statusText: 'Payload Too Large' });
    await settle();
    http.expectNone((r) => r.method === 'PATCH');
  });

  it('retries a failed save when leaving the page', async () => {
    await open();
    vi.useFakeTimers();
    type('mine');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();
    expect(state()).toBe('Not saved');

    void harness.navigateByUrl('/elsewhere');
    await vi.advanceTimersByTimeAsync(0);
    const retry = http.expectOne((r) => r.url === URL && r.method === 'PATCH');
    expect(retry.request.body.bodyMd).toBe('mine');
    expect(retry.request.headers.get('If-Match')).toBe('"1"');
  });

  it('retries a failed save before opening another note', async () => {
    await open();
    vi.useFakeTimers();
    type('mine');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await vi.advanceTimersByTimeAsync(0);

    void harness.navigateByUrl(`/notes/${OTHER_ID}`);
    await vi.advanceTimersByTimeAsync(0);
    http.expectNone((r) => r.url === `/api/v1/notes/${OTHER_ID}`);
    const retry = http.expectOne((r) => r.url === URL && r.method === 'PATCH');
    expect(retry.request.body.bodyMd).toBe('mine');
    retry.flush(note({ bodyMd: 'mine', version: 2 }));
    await vi.advanceTimersByTimeAsync(0);
    http
      .expectOne((r) => r.url === `/api/v1/notes/${OTHER_ID}` && r.method === 'GET')
      .flush(note({ id: OTHER_ID, title: 'Recipes', bodyMd: 'Soup', version: 9 }));
    await vi.advanceTimersByTimeAsync(0);
    flushLinks();
    harness.detectChanges();
    expect(editor().value()).toBe('Soup');
    expect(state()).toBe('Saved');
    expect(root.querySelector('.banner[role=alert]')).toBeNull();
  });

  it('stays on the note with its edits when the retry before opening another note fails too', async () => {
    await open();
    vi.useFakeTimers();
    type('mine');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await vi.advanceTimersByTimeAsync(0);

    void harness.navigateByUrl(`/notes/${OTHER_ID}`);
    await vi.advanceTimersByTimeAsync(0);
    http
      .expectOne((r) => r.url === URL && r.method === 'PATCH')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();

    // The other note is not opened, and the address goes back to the first one.
    http.expectNone((r) => r.url === `/api/v1/notes/${OTHER_ID}`);
    expect(TestBed.inject(Router).url).toBe(`/notes/${ID}`);
    root = harness.routeNativeElement as HTMLElement;
    expect(editor().value()).toBe('mine');
    expect(state()).toBe('Not saved');
    expect(root.querySelector('.banner[role=alert] .retry')).not.toBeNull();

    // A successful retry then saves the first note.
    button('Try again').click();
    await vi.advanceTimersByTimeAsync(0);
    const retry = http.expectOne((r) => r.method === 'PATCH');
    expect(retry.request.url).toBe(URL);
    expect(retry.request.body.bodyMd).toBe('mine');
    expect(retry.request.headers.get('If-Match')).toBe('"1"');
    retry.flush(note({ bodyMd: 'mine', version: 2 }));
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();
    expect(state()).toBe('Saved');
    expect(root.querySelector('.banner[role=alert]')).toBeNull();
    http.expectNone((r) => r.method === 'GET' && r.url.startsWith('/api/v1/notes/'));
  });

  it('when leaving during several uploads, saves every image in place of its placeholder', async () => {
    await open(note({ bodyMd: 'Trip' }));
    const tent = pasteImage();
    const map = pasteImage();
    void harness.navigateByUrl('/elsewhere');
    await settle();
    const saved: string[] = [];
    const answerSaves = () => {
      for (const save of http.match((r) => r.url === URL && r.method === 'PATCH')) {
        saved.push(save.request.body.bodyMd);
        save.flush(note({ bodyMd: save.request.body.bodyMd, version: saved.length + 1 }));
      }
    };
    answerSaves();
    await settle();
    // The second upload ends first.
    map.flush({ ...TENT, fileId: OTHER_ID, markdown: `![map.png](cockpit-file:${OTHER_ID})` });
    await settle();
    answerSaves();
    tent.flush(TENT);
    await settle();
    answerSaves();
    await settle();
    answerSaves();

    expect(saved.every((body) => !body.includes('(uploading:'))).toBe(true);
    expect(saved.at(-1)).toBe(`Trip\n${TENT.markdown}\n![map.png](cockpit-file:${OTHER_ID})\n`);
  });

  it('when the save on leaving fails, saves again with the image once the upload ends', async () => {
    await open(note({ bodyMd: 'Trip' }));
    const upload = pasteImage();
    void harness.navigateByUrl('/elsewhere');
    await settle();
    http
      .expectOne((r) => r.url === URL && r.method === 'PATCH')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await settle();
    upload.flush(TENT);
    await settle();
    const save = http.expectOne((r) => r.url === URL && r.method === 'PATCH');
    expect(save.request.body.bodyMd).toBe(`Trip\n${TENT.markdown}\n`);
    expect(save.request.headers.get('If-Match')).toBe('"1"');
  });

  it('asks before closing the browser tab while an image is still uploading', async () => {
    await open(note({ bodyMd: 'Trip' }));
    vi.useFakeTimers();
    const unload = () => {
      const event = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    };
    const upload = pasteImage();
    // The text around the placeholder is saved; the image is not there yet.
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    http.expectOne((r) => r.method === 'PATCH').flush(note({ bodyMd: 'Trip\n\n', version: 2 }));
    await vi.advanceTimersByTimeAsync(0);
    expect(unload()).toBe(true);
    upload.flush(TENT);
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(note({ bodyMd: `Trip\n${TENT.markdown}\n`, version: 3 }));
    await vi.advanceTimersByTimeAsync(0);
    expect(unload()).toBe(false);
  });

  it('stays on the first note when the route returns to it while leaving waits for an upload', async () => {
    await open(note({ bodyMd: 'Trip' }));
    const upload = pasteImage();
    // A chip to the other note: leaving the first one waits for the upload.
    void harness.navigateByUrl(`/notes/${OTHER_ID}`);
    await settle();
    http
      .expectOne((r) => r.url === URL && r.method === 'PATCH')
      .flush(note({ bodyMd: 'Trip\n\n', version: 2 }));
    await settle();
    // Back to the first note before the upload ends.
    void harness.navigateByUrl(`/notes/${ID}`);
    await settle();
    upload.flush(TENT);
    await settle();
    for (const save of http.match((r) => r.url === URL && r.method === 'PATCH')) {
      save.flush(note({ bodyMd: save.request.body.bodyMd, version: 3 }));
    }
    await settle();
    // If the editor goes on to the other note anyway, it gets an answer.
    for (const get of http.match((r) => r.url === `/api/v1/notes/${OTHER_ID}`)) {
      get.flush(note({ id: OTHER_ID, title: 'Recipes', bodyMd: 'Soup', version: 9 }));
    }
    await settle();
    for (const get of http.match((r) => r.url === URL && r.method === 'GET')) {
      get.flush(note({ bodyMd: `Trip\n${TENT.markdown}\n`, version: 3 }));
    }
    await settle();
    flushLinks();
    await settle();

    // The address shows the first note, so the page must show it too.
    expect(TestBed.inject(Router).url).toBe(`/notes/${ID}`);
    root = harness.routeNativeElement as HTMLElement;
    expect((root.querySelector('.title') as HTMLInputElement).value).toBe('Packing list');
    expect(editor().value()).toBe(`Trip\n${TENT.markdown}\n`);
  });

  it('opens only the last note when the route changes twice while leaving waits for an upload', async () => {
    const THIRD_ID = '0192f0c4-0000-7000-8000-000000000003';
    await open(note({ bodyMd: 'Trip' }));
    const upload = pasteImage();
    // A chip to note B: leaving the first note waits for the upload.
    void harness.navigateByUrl(`/notes/${OTHER_ID}`);
    await settle();
    http
      .expectOne((r) => r.url === URL && r.method === 'PATCH')
      .flush(note({ bodyMd: 'Trip\n\n', version: 2 }));
    await settle();
    // On to note C before the upload ends.
    void harness.navigateByUrl(`/notes/${THIRD_ID}`);
    await settle();
    http.expectNone((r) => r.url.startsWith('/api/v1/notes/0192f0c4') && r.method === 'GET');
    upload.flush(TENT);
    await settle();
    // The image is still saved into the first note, once.
    const saves = http.match((r) => r.method === 'PATCH');
    expect(saves.map((s) => [s.request.url, s.request.body.bodyMd])).toEqual([
      [URL, `Trip\n${TENT.markdown}\n`],
    ]);
    expect(saves[0].request.headers.get('If-Match')).toBe('"2"');
    saves[0].flush(note({ bodyMd: saves[0].request.body.bodyMd, version: 3 }));
    await settle();

    // Only note C is loaded; note B is never requested.
    http.expectNone((r) => r.url === `/api/v1/notes/${OTHER_ID}`);
    http
      .expectOne((r) => r.url === `/api/v1/notes/${THIRD_ID}` && r.method === 'GET')
      .flush(note({ id: THIRD_ID, title: 'Garden', bodyMd: 'Tomatoes', version: 4 }));
    await settle();
    flushLinks();
    await settle();
    expect(TestBed.inject(Router).url).toBe(`/notes/${THIRD_ID}`);
    root = harness.routeNativeElement as HTMLElement;
    expect((root.querySelector('.title') as HTMLInputElement).value).toBe('Garden');
    expect(editor().value()).toBe('Tomatoes');

    // Edits now go to note C with its version.
    vi.useFakeTimers();
    type('Tomatoes and basil');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    const save = http.expectOne((r) => r.method === 'PATCH');
    expect(save.request.url).toBe(`/api/v1/notes/${THIRD_ID}`);
    expect(save.request.headers.get('If-Match')).toBe('"4"');
  });

  it('ends on the other note when the route goes there, back and there again while leaving waits', async () => {
    await open(note({ bodyMd: 'Trip' }));
    const upload = pasteImage();
    void harness.navigateByUrl(`/notes/${OTHER_ID}`);
    await settle();
    http
      .expectOne((r) => r.url === URL && r.method === 'PATCH')
      .flush(note({ bodyMd: 'Trip\n\n', version: 2 }));
    await settle();
    void harness.navigateByUrl(`/notes/${ID}`);
    await settle();
    void harness.navigateByUrl(`/notes/${OTHER_ID}`);
    await settle();
    http.expectNone((r) => r.url === `/api/v1/notes/${OTHER_ID}`);
    upload.flush(TENT);
    await settle();
    for (const save of http.match((r) => r.url === URL && r.method === 'PATCH')) {
      expect(save.request.body.bodyMd).toBe(`Trip\n${TENT.markdown}\n`);
      save.flush(note({ bodyMd: save.request.body.bodyMd, version: 3 }));
    }
    await settle();
    for (const get of http.match((r) => r.url === `/api/v1/notes/${OTHER_ID}`)) {
      get.flush(note({ id: OTHER_ID, title: 'Recipes', bodyMd: 'Soup', version: 9 }));
    }
    await settle();
    http.expectNone((r) => r.url === URL && r.method === 'GET');
    flushLinks();
    await settle();

    expect(TestBed.inject(Router).url).toBe(`/notes/${OTHER_ID}`);
    root = harness.routeNativeElement as HTMLElement;
    expect((root.querySelector('.title') as HTMLInputElement).value).toBe('Recipes');
    expect(editor().value()).toBe('Soup');
    vi.useFakeTimers();
    type('Soup and bread');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    const save = http.expectOne((r) => r.method === 'PATCH');
    expect(save.request.url).toBe(`/api/v1/notes/${OTHER_ID}`);
    expect(save.request.headers.get('If-Match')).toBe('"9"');
  });

  it('does not open the other note when the page is left while leaving the first one waits', async () => {
    await open(note({ bodyMd: 'Trip' }));
    const upload = pasteImage();
    void harness.navigateByUrl(`/notes/${OTHER_ID}`);
    await settle();
    http
      .expectOne((r) => r.url === URL && r.method === 'PATCH')
      .flush(note({ bodyMd: 'Trip\n\n', version: 2 }));
    await settle();
    // Another page before the upload ends.
    void harness.navigateByUrl('/elsewhere');
    await settle();
    expect(harness.routeNativeElement?.textContent).toBe('Elsewhere');
    upload.flush(TENT);
    await settle();
    // The image is still saved into the first note.
    const saves = http.match((r) => r.method === 'PATCH');
    expect(saves.map((s) => [s.request.url, s.request.body.bodyMd])).toEqual([
      [URL, `Trip\n${TENT.markdown}\n`],
    ]);
    saves[0].flush(note({ bodyMd: saves[0].request.body.bodyMd, version: 3 }));
    await settle();
    await settle();
    http.expectNone((r) => r.url === `/api/v1/notes/${OTHER_ID}`);
    http.expectNone((r) => r.url === '/api/v1/links');
  });

  /** In conflict on the first note, clicks `action` and opens the other note before it ends. */
  async function conflictThenOpenOther(action: string): Promise<TestRequest> {
    await open();
    vi.useFakeTimers();
    type('mine');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    await conflictOnSave();
    button(action).click();
    await vi.advanceTimersByTimeAsync(0);
    const slow = http.expectOne((r) => r.url === URL && r.method === 'GET');
    void harness.navigateByUrl(`/notes/${OTHER_ID}`);
    await vi.advanceTimersByTimeAsync(0);
    http
      .expectOne((r) => r.url === `/api/v1/notes/${OTHER_ID}` && r.method === 'GET')
      .flush(note({ id: OTHER_ID, title: 'Recipes', bodyMd: 'Soup', version: 9 }));
    await vi.advanceTimersByTimeAsync(0);
    flushLinks();
    return slow;
  }

  it('“Save my version” has no effect when another note was opened meanwhile', async () => {
    const slow = await conflictThenOpenOther('Save my version');
    slow.flush(note({ bodyMd: 'theirs', version: 5 }));
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
    http.expectNone((r) => r.method === 'PATCH');
    harness.detectChanges();
    expect(editor().value()).toBe('Soup');
    expect(state()).toBe('Saved');
  });

  it('“Discard my edits” has no effect when another note was opened meanwhile', async () => {
    const slow = await conflictThenOpenOther('Discard my edits');
    slow.flush(note({ bodyMd: 'theirs', version: 5 }));
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();
    expect(editor().value()).toBe('Soup');
    expect((root.querySelector('.title') as HTMLInputElement).value).toBe('Recipes');
    type('Soup and bread');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    const save = http.expectOne((r) => r.method === 'PATCH');
    expect(save.request.url).toBe(`/api/v1/notes/${OTHER_ID}`);
    expect(save.request.headers.get('If-Match')).toBe('"9"');
  });

  it('shows no error of a conflict action when another note was opened meanwhile', async () => {
    const slow = await conflictThenOpenOther('Save my version');
    slow.flush(null, { status: 503, statusText: 'Service Unavailable' });
    await vi.advanceTimersByTimeAsync(0);
    harness.detectChanges();
    expect(root.querySelector('.banner[role=alert]')).toBeNull();
  });

  it('saves nothing when leaving without edits', async () => {
    await open();
    vi.useFakeTimers();
    void harness.navigateByUrl('/elsewhere');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
    http.expectNone((r) => r.method === 'PATCH');
  });

  it('asks before closing the browser tab only while edits are unsaved', async () => {
    await open();
    vi.useFakeTimers();
    const unload = () => {
      const event = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    };
    expect(unload()).toBe(false);
    type('mine');
    expect(unload()).toBe(true);
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    const save = http.expectOne((r) => r.method === 'PATCH');
    expect(unload()).toBe(true);
    save.flush(note({ bodyMd: 'mine', version: 2 }));
    await vi.advanceTimersByTimeAsync(0);
    expect(unload()).toBe(false);
  });

  it('shows a failed image upload', async () => {
    await open();
    editor().uploadFailed.emit(new ApiError(413, 'Payload Too Large', 'The image is too large.'));
    harness.detectChanges();
    const banner = root.querySelector('.banner[role=alert]');
    expect(banner?.textContent).toContain(
      'The image could not be uploaded: The image is too large.',
    );
    // An upload error is no save error: there is nothing to retry.
    expect(banner?.querySelector('.retry')).toBeNull();
    expect(state()).toBe('Saved');
  });

  it('shows a generic message for an unexpected upload failure', async () => {
    await open();
    editor().uploadFailed.emit(new Error('boom'));
    harness.detectChanges();
    expect(root.querySelector('.banner[role=alert]')?.textContent).toContain(
      'The image could not be uploaded: Something went wrong.',
    );
  });

  async function openRevisionsAndPick(number: number): Promise<void> {
    button('Earlier versions').click();
    await settle();
    http
      .expectOne((r) => r.url === `${URL}/revisions`)
      .flush({
        items: [
          {
            number,
            bodyMd: 'old',
            savedAt: '2026-09-01T08:00:00Z',
            replacedAt: '2026-09-02T08:00:00Z',
          },
        ],
      });
    await settle();
    document.querySelector<HTMLButtonElement>('mat-dialog-container button.restore')!.click();
  }

  it('saves pending edits before restoring a revision, then restores on the saved version', async () => {
    await open();
    type('unsaved edit');
    await openRevisionsAndPick(1);
    const save = await vi.waitFor(() => http.expectOne((r) => r.method === 'PATCH'));
    expect(save.request.body.bodyMd).toBe('unsaved edit');
    save.flush(note({ bodyMd: 'unsaved edit', version: 2 }));
    const restore = await vi.waitFor(() =>
      http.expectOne((r) => r.url === `${URL}/revisions/1/restore`),
    );
    expect(restore.request.headers.get('If-Match')).toBe('"2"');
    restore.flush(note({ bodyMd: 'old', version: 3 }));
    await settle();
    flushLinks();
    expect(editor().value()).toBe('old');
  });

  it('does not restore while the note is in conflict', async () => {
    await open();
    type('unsaved edit');
    await openRevisionsAndPick(1);
    const save = await vi.waitFor(() => http.expectOne((r) => r.method === 'PATCH'));
    save.flush(...CONFLICT);
    await settle();
    await settle();
    http.expectNone((r) => r.url.endsWith('/restore'));
    expect(state()).toBe('Not saved: changed elsewhere');
    expect(editor().value()).toBe('unsaved edit');
  });

  it('shows a failed restore and keeps the note', async () => {
    await open();
    await openRevisionsAndPick(1);
    const restore = await vi.waitFor(() =>
      http.expectOne((r) => r.url === `${URL}/revisions/1/restore`),
    );
    restore.flush(null, { status: 503, statusText: 'Service Unavailable' });
    await settle();
    expect(root.querySelector('.banner[role=alert]')?.textContent).toContain(
      'The version could not be restored',
    );
    expect(editor().value()).toBe('- [ ] Passport\n- [ ] Charger');
  });

  it('restores nothing when the dialog is closed without a choice', async () => {
    await open();
    button('Earlier versions').click();
    await settle();
    http.expectOne((r) => r.url === `${URL}/revisions`).flush({ items: [] });
    await settle();
    const dialog = document.querySelector('mat-dialog-container') as HTMLElement;
    expect(dialog.textContent).toContain('No earlier versions yet');
    expect(dialog.querySelector<HTMLButtonElement>('button.restore')!.disabled).toBe(true);
    TestBed.inject(MatDialog).closeAll();
    await settle();
    await settle();
    http.expectNone((r) => r.url.endsWith('/restore'));
  });

  it('shows when the earlier versions cannot be loaded', async () => {
    await open();
    button('Earlier versions').click();
    await settle();
    http
      .expectOne((r) => r.url === `${URL}/revisions`)
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await settle();
    const dialog = document.querySelector('mat-dialog-container') as HTMLElement;
    expect(dialog.querySelector('[role=alert]')?.textContent).toContain(
      'The earlier versions could not be loaded',
    );
  });

  it('lists revisions by title or first line and shows the chosen one read-only', async () => {
    await open();
    button('Earlier versions').click();
    await settle();
    http
      .expectOne((r) => r.url === `${URL}/revisions`)
      .flush({
        items: [
          {
            number: 1,
            bodyMd: '',
            savedAt: '2026-09-01T08:00:00Z',
            replacedAt: '2026-09-02T08:00:00Z',
          },
          {
            number: 2,
            title: 'Trip',
            bodyMd: '- [x] Passport',
            savedAt: '2026-09-02T08:00:00Z',
            replacedAt: '2026-09-03T08:00:00Z',
          },
          {
            number: 3,
            bodyMd: 'First line\nsecond',
            savedAt: '2026-09-03T08:00:00Z',
            replacedAt: '2026-09-04T08:00:00Z',
          },
        ],
      });
    await settle();
    const dialog = document.querySelector('mat-dialog-container') as HTMLElement;
    const summaries = [...dialog.querySelectorAll('button.revision .what')].map((e) =>
      e.textContent?.trim(),
    );
    expect(summaries).toEqual(['First line', 'Trip', 'Empty note']);
    dialog.querySelectorAll<HTMLButtonElement>('button.revision')[1].click();
    await settle();
    expect(dialog.querySelector('.preview-title')?.textContent).toBe('Trip');
    const box = dialog.querySelector<HTMLInputElement>('app-note-markdown-view input')!;
    expect(box.disabled).toBe(true);
    expect(box.checked).toBe(true);
    http.expectNone((r) => r.method === 'PATCH');
  });
});
