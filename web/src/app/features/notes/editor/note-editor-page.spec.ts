import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { LinkResponse, NoteResponse } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import { MarkdownEditor } from './markdown-editor';
import NoteEditorPage, { AUTOSAVE_DEBOUNCE_MS, NOTE_EDITOR_MODE_KEY } from './note-editor-page';

const ID = '0192f0c4-0000-7000-8000-000000000001';
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

describe('NoteEditorPage', () => {
  let harness: RouterTestingHarness;
  let root: HTMLElement;
  let http: HttpTestingController;

  async function open(loaded: NoteResponse = note(), links: LinkResponse[] = []): Promise<void> {
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
    const markdown = editor().uploadImage()!(new File(['x'], 'tent.png', { type: 'image/png' }));
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
});
