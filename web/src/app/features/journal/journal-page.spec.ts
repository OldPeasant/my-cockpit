import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { EditorView } from '@codemirror/view';
import {
  JournalEntryResponse,
  JournalHeatmapResponse,
  JournalImageResponse,
} from '../../api/generated';
import { NotificationService, provideHttp } from '../../core/http';
import { MarkdownEditor } from '../notes/editor/markdown-editor';
import JournalPage, { AUTOSAVE_DEBOUNCE_MS, DAY_CHECK_MS } from './journal-page';
import journalRoutes from './journal.routes';
import { JournalService } from './journal.service';
import { LeaveChoice, LeavePrompt } from './leave-dialog';
import { journalEntry, journalHeatmap } from './testing';

const TODAY = '2026-10-25';
const ENTRIES = '/api/v1/journal/entries';
const HEATMAP_URL = '/api/v1/journal/heatmap';
const ON_THIS_DAY_URL = '/api/v1/journal/on-this-day';
const IMAGES_URL = '/api/v1/journal/images';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

/** Another page, to leave the journal. */
@Component({ template: 'Elsewhere' })
class Elsewhere {}

// The real journal routes: today and a date are one route, so the page stays between them.
const ROUTES = [
  { path: 'journal', children: journalRoutes },
  { path: 'elsewhere', component: Elsewhere },
];

const IMAGE: JournalImageResponse = {
  fileId: '0192f0c4-0000-7000-8000-0000000000f1',
  markdown: '![lake.png](cockpit-file:0192f0c4-0000-7000-8000-0000000000f1)',
  reference: 'cockpit-file:0192f0c4-0000-7000-8000-0000000000f1',
  mime: 'image/png',
  name: 'lake.png',
  size: 1,
};

function problem(status: number, detail: string) {
  return [
    { title: 'Error', status, detail },
    { status, statusText: 'Error' },
  ] as const;
}

const NOT_FOUND = problem(404, 'No entry for this date.');

interface Day {
  entry?: JournalEntryResponse | null;
  onThisDay?: JournalEntryResponse[];
}

describe('JournalPage', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let router: Router;
  /** The prompt before edits in conflict are left; stays unless a test answers otherwise. */
  let ask: ReturnType<typeof vi.fn<() => Promise<LeaveChoice>>>;

  function setup(): void {
    ask = vi.fn<() => Promise<LeaveChoice>>().mockResolvedValue('stay');
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter(ROUTES),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
        { provide: LeavePrompt, useValue: { ask } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  }

  /** Opens `url` in a fresh page; answers the heatmap and the day's requests. */
  async function open(
    url = '/journal',
    day: Day = {},
    heatmap: JournalHeatmapResponse = journalHeatmap(TODAY),
  ): Promise<void> {
    setup();
    harness = await RouterTestingHarness.create();
    void harness.navigateByUrl(url);
    await settle();
    flushHeatmap(heatmap);
    await settle();
    flushDay(url === '/journal' ? heatmap.to : url.slice('/journal/'.length), day);
    await settle();
  }

  function flushHeatmap(heatmap: JournalHeatmapResponse = journalHeatmap(TODAY)): void {
    const req = http.expectOne((r) => r.method === 'GET' && r.url === HEATMAP_URL);
    expect(req.request.params.keys()).toEqual([]);
    req.flush(heatmap);
  }

  /** Answers "on this day" and the entry of `date`. */
  function flushDay(date: string, day: Day = {}): void {
    http
      .expectOne((r) => r.url === ON_THIS_DAY_URL && r.params.get('date') === date)
      .flush({ date, items: day.onThisDay ?? [] });
    const req = http.expectOne((r) => r.method === 'GET' && r.url === `${ENTRIES}/${date}`);
    if (day.entry) req.flush(day.entry);
    else req.flush(...NOT_FOUND);
  }

  async function settle(): Promise<void> {
    await tick();
    harness.detectChanges();
    await harness.fixture.whenStable();
  }

  function root(): HTMLElement {
    return harness.routeNativeElement as HTMLElement;
  }

  function editor(): MarkdownEditor {
    return harness.fixture.debugElement.query(By.directive(MarkdownEditor))
      .componentInstance as MarkdownEditor;
  }

  function type(body: string): void {
    editor().valueChange.emit(body);
    harness.detectChanges();
  }

  function text(el: Element | null): string {
    return el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  function state(): string {
    return text(root().querySelector('.save-state'));
  }

  function el<T extends HTMLElement = HTMLButtonElement>(selector: string): T {
    const found = root().querySelector(selector);
    if (!found) throw new Error(`no element ${selector}`);
    return found as T;
  }

  function heatmapDay(date: string): HTMLButtonElement {
    return el(`app-journal-heatmap .day[data-date="${date}"]`);
  }

  function moodButton(mood: number): HTMLButtonElement {
    return el(`.mood-option[data-mood="${mood}"]`);
  }

  function puts(): TestRequest[] {
    return http.match((r) => r.method === 'PUT');
  }

  /** Pastes an image at the end of the text; returns its pending upload request. */
  function pasteImage(): TestRequest {
    const view = EditorView.findFromDOM(el<HTMLElement>('.cm-editor'))!;
    view.dispatch({ selection: { anchor: view.state.doc.length } });
    const paste = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent;
    const image = new File(['x'], 'lake.png', { type: 'image/png' });
    Object.defineProperty(paste, 'clipboardData', { value: { files: [image], getData: () => '' } });
    view.contentDOM.dispatchEvent(paste);
    return http.expectOne((r) => r.method === 'POST' && r.url === IMAGES_URL);
  }

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  describe('today', () => {
    it('opens today without creating an entry', async () => {
      await open('/journal');

      expect(text(root().querySelector('h1'))).toBe('Sunday, October 25, 2026');
      expect(text(root().querySelector('.relative'))).toBe('Today');
      expect(editor().value()).toBe('');
      expect(state()).toBe('Not written yet');
      expect(el('.next').disabled).toBe(true);
      expect(el('.previous').disabled).toBe(false);
      expect(root().querySelector('.go-today')).toBeNull();
      expect(heatmapDay(TODAY).classList).toContain('selected');
      expect(heatmapDay(TODAY).classList).not.toContain('entry');

      // Nothing is saved while nothing is written.
      vi.useFakeTimers();
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 3);
      expect(puts()).toEqual([]);
    });

    it("creates today's entry with the first edit, by a PUT without If-Match", async () => {
      await open('/journal');
      vi.useFakeTimers();

      type('Swam in the lake.');
      expect(state()).toBe('Unsaved changes');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS - 1);
      expect(puts()).toEqual([]);
      await vi.advanceTimersByTimeAsync(1);

      const create = http.expectOne((r) => r.method === 'PUT' && r.url === `${ENTRIES}/${TODAY}`);
      expect(create.request.headers.has('If-Match')).toBe(false);
      expect(create.request.body).toEqual({ bodyMd: 'Swam in the lake.', mood: null, tags: [] });
      harness.detectChanges();
      expect(state()).toBe('Saving…');
      create.flush(journalEntry(TODAY, { bodyMd: 'Swam in the lake.', version: 1 }), {
        status: 201,
        statusText: 'Created',
      });
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      expect(state()).toBe('Saved');
      // The new entry shows in the heatmap at once.
      expect(heatmapDay(TODAY).classList).toContain('entry');
      expect(heatmapDay(TODAY).getAttribute('data-mood')).toBe('none');
    });

    it('sends the version as If-Match for the edits after the entry exists', async () => {
      await open('/journal');
      vi.useFakeTimers();

      type('Swam');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      http
        .expectOne((r) => r.method === 'PUT')
        .flush(journalEntry(TODAY, { bodyMd: 'Swam', version: 1 }));
      await vi.advanceTimersByTimeAsync(0);

      type('Swam twice');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      const second = http.expectOne((r) => r.method === 'PUT');
      expect(second.request.headers.get('If-Match')).toBe('"1"');
      expect(second.request.body.bodyMd).toBe('Swam twice');
      second.flush(journalEntry(TODAY, { bodyMd: 'Swam twice', version: 2 }));
      await vi.advanceTimersByTimeAsync(0);

      type('Swam three times');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      const third = http.expectOne((r) => r.method === 'PUT');
      expect(third.request.headers.get('If-Match')).toBe('"2"');
      third.flush(journalEntry(TODAY, { bodyMd: 'Swam three times', version: 3 }));
      await vi.advanceTimersByTimeAsync(0);
    });

    it('saves a burst of typing once, after the debounce', async () => {
      await open('/journal');
      vi.useFakeTimers();

      type('S');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS / 2);
      type('Sw');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS / 2);
      type('Swim');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);

      const [only, ...more] = puts();
      expect(more).toEqual([]);
      expect(only.request.body.bodyMd).toBe('Swim');
      only.flush(journalEntry(TODAY, { bodyMd: 'Swim' }));
      await vi.advanceTimersByTimeAsync(0);
    });

    it('does not create an entry for whitespace only', async () => {
      await open('/journal');
      vi.useFakeTimers();

      type('  \n  ');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
      harness.detectChanges();

      expect(puts()).toEqual([]);
      expect(state()).toBe('Not written yet');
    });

    it('creates the entry from a mood and tags alone', async () => {
      await open('/journal');
      vi.useFakeTimers();

      moodButton(4).click();
      harness.detectChanges();
      expect(moodButton(4).getAttribute('aria-pressed')).toBe('true');
      expect(moodButton(3).getAttribute('aria-pressed')).toBe('false');
      const tags = el<HTMLInputElement>('.tags input');
      tags.value = 'Lake, #family, lake';
      tags.dispatchEvent(new Event('input'));
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);

      const create = http.expectOne((r) => r.method === 'PUT');
      expect(create.request.headers.has('If-Match')).toBe(false);
      expect(create.request.body).toEqual({ bodyMd: '', mood: 4, tags: ['Lake', 'family'] });
      create.flush(journalEntry(TODAY, { bodyMd: '', mood: 4, tags: ['family', 'Lake'] }));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      // The server's order of the tags is no change.
      expect(state()).toBe('Saved');
      expect(heatmapDay(TODAY).getAttribute('data-mood')).toBe('4');

      tags.dispatchEvent(new Event('blur'));
      harness.detectChanges();
      expect(tags.value).toBe('Lake, family');
    });

    it('clears the mood with a second tap on it', async () => {
      await open('/journal', { entry: journalEntry(TODAY, { mood: 2, version: 6 }) });
      vi.useFakeTimers();
      expect(moodButton(2).getAttribute('aria-pressed')).toBe('true');
      expect(moodButton(2).getAttribute('title')).toBe('Low');

      moodButton(2).click();
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      const save = http.expectOne((r) => r.method === 'PUT');
      expect(save.request.headers.get('If-Match')).toBe('"6"');
      expect(save.request.body.mood).toBeNull();
      save.flush(journalEntry(TODAY, { version: 7 }));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();
      expect(moodButton(2).getAttribute('aria-pressed')).toBe('false');
    });

    it("shows today's existing entry with its mood and tags", async () => {
      await open('/journal', {
        entry: journalEntry(TODAY, { bodyMd: 'Rainy.', mood: 3, tags: ['home', 'rain'] }),
      });

      expect(editor().value()).toBe('Rainy.');
      expect(moodButton(3).getAttribute('aria-pressed')).toBe('true');
      expect(el<HTMLInputElement>('.tags input').value).toBe('home, rain');
      expect(state()).toBe('Saved');
    });

    it('shows the entry as Markdown in the preview', async () => {
      await open('/journal', { entry: journalEntry(TODAY, { bodyMd: '# Lake\n\nCold water.' }) });

      el('.mode-preview').click();
      await settle();
      expect(el('.mode-preview').getAttribute('aria-pressed')).toBe('true');
      expect(root().querySelector('app-markdown-editor')).toBeNull();
      const view = root().querySelector('app-note-markdown-view');
      expect(text(view).startsWith('Lake')).toBe(true);
      expect(view?.innerHTML).not.toContain('# Lake');
      expect(text(view?.querySelector('p') ?? null)).toBe('Cold water.');

      el('.mode-edit').click();
      await settle();
      expect(editor().value()).toBe('# Lake\n\nCold water.');
    });

    it('says so in the preview when nothing is written', async () => {
      await open('/journal');
      el('.mode-preview').click();
      await settle();
      expect(text(root().querySelector('.empty-preview'))).toBe('Nothing written for this day.');
    });
  });

  describe('images', () => {
    it('uploads a pasted image and saves its Markdown in the entry', async () => {
      await open('/journal');
      vi.useFakeTimers();
      const upload = pasteImage();
      expect(upload.request.body).toBeInstanceOf(FormData);
      harness.detectChanges();
      expect(editor().value()).toContain('(uploading:');

      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
      // The placeholder alone does not create the entry.
      expect(puts()).toEqual([]);

      upload.flush(IMAGE, { status: 201, statusText: 'Created' });
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();
      expect(editor().value()).toBe(`${IMAGE.markdown}\n`);
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);

      const create = http.expectOne((r) => r.method === 'PUT');
      expect(create.request.headers.has('If-Match')).toBe(false);
      expect(create.request.body.bodyMd).toBe(`${IMAGE.markdown}\n`);
      create.flush(journalEntry(TODAY, { bodyMd: `${IMAGE.markdown}\n` }));
      await vi.advanceTimersByTimeAsync(0);
    });

    it('never saves the placeholder of an image still uploading', async () => {
      await open('/journal', { entry: journalEntry(TODAY, { bodyMd: 'Lake', version: 2 }) });
      vi.useFakeTimers();
      const upload = pasteImage();

      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      const first = http.expectOne((r) => r.method === 'PUT');
      expect(first.request.body.bodyMd).not.toContain('uploading:');
      expect(first.request.headers.get('If-Match')).toBe('"2"');
      first.flush(journalEntry(TODAY, { bodyMd: first.request.body.bodyMd, version: 3 }));
      await vi.advanceTimersByTimeAsync(0);

      upload.flush(IMAGE);
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      const second = http.expectOne((r) => r.method === 'PUT');
      expect(second.request.body.bodyMd).toBe(`Lake\n${IMAGE.markdown}\n`);
      expect(second.request.headers.get('If-Match')).toBe('"3"');
      second.flush(journalEntry(TODAY, { bodyMd: second.request.body.bodyMd, version: 4 }));
      await vi.advanceTimersByTimeAsync(0);
    });

    it('shows a failed upload and removes its placeholder', async () => {
      await open('/journal', { entry: journalEntry(TODAY, { bodyMd: 'Lake' }) });
      const upload = pasteImage();

      upload.flush(...problem(413, 'The image is too large.'));
      await settle();

      expect(text(root().querySelector('.banner[role="alert"]'))).toBe(
        'The image could not be uploaded: The image is too large.',
      );
      expect(editor().value()).not.toContain('uploading:');
    });

    it('puts an image still uploading in place before opening another day', async () => {
      await open('/journal/2026-10-24', {
        entry: journalEntry('2026-10-24', { bodyMd: 'Lake', version: 2 }),
      });
      const upload = pasteImage();

      void harness.navigateByUrl('/journal/2026-10-23');
      await settle();
      const first = http.expectOne((r) => r.method === 'PUT' && r.url === `${ENTRIES}/2026-10-24`);
      expect(first.request.body.bodyMd).not.toContain('uploading:');
      first.flush(journalEntry('2026-10-24', { bodyMd: first.request.body.bodyMd, version: 3 }));
      await settle();
      // The other day waits for the upload.
      http.expectNone((r) => r.url === `${ENTRIES}/2026-10-23`);

      upload.flush(IMAGE);
      await settle();
      const second = http.expectOne((r) => r.method === 'PUT');
      expect(second.request.body.bodyMd).toBe(`Lake\n${IMAGE.markdown}\n`);
      expect(second.request.headers.get('If-Match')).toBe('"3"');
      second.flush(journalEntry('2026-10-24', { bodyMd: second.request.body.bodyMd, version: 4 }));
      await settle();

      flushDay('2026-10-23');
      await settle();
      expect(editor().value()).toBe('');
    });

    it('puts an image whose upload ends during Preview in the text and saves it', async () => {
      await open('/journal', { entry: journalEntry(TODAY, { bodyMd: 'Lake', version: 2 }) });
      const upload = pasteImage();
      el('.mode-preview').click();
      await settle();
      expect(root().querySelector('app-markdown-editor')).toBeNull();
      vi.useFakeTimers();

      upload.flush(IMAGE);
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();
      expect(root().querySelector('app-note-markdown-view img')).not.toBeNull();
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);

      const save = http.expectOne((r) => r.method === 'PUT');
      expect(save.request.body.bodyMd).toBe(`Lake\n${IMAGE.markdown}\n`);
      expect(save.request.headers.get('If-Match')).toBe('"2"');
      save.flush(journalEntry(TODAY, { bodyMd: save.request.body.bodyMd, version: 3 }));
      await vi.advanceTimersByTimeAsync(0);
      vi.useRealTimers();

      el('.mode-edit').click();
      await settle();
      expect(editor().value()).toBe(`Lake\n${IMAGE.markdown}\n`);
      expect(state()).toBe('Saved');
    });

    it('reports an upload that fails during Preview and drops its placeholder', async () => {
      await open('/journal', { entry: journalEntry(TODAY, { bodyMd: 'Lake' }) });
      const upload = pasteImage();
      el('.mode-preview').click();
      await settle();

      upload.flush(...problem(413, 'The image is too large.'));
      await settle();
      expect(text(root().querySelector('.banner[role="alert"]'))).toBe(
        'The image could not be uploaded: The image is too large.',
      );

      el('.mode-edit').click();
      await settle();
      // Like the editor does: the placeholder goes, the line breaks around it stay.
      expect(editor().value()).toBe('Lake\n\n');
    });

    it('reports a failed upload once while the editor is shown', async () => {
      await open('/journal', { entry: journalEntry(TODAY, { bodyMd: 'Lake' }) });
      const spy = vi.spyOn(
        JournalPage.prototype as unknown as { onUploadFailed(e: unknown): void },
        'onUploadFailed',
      );
      const upload = pasteImage();

      upload.flush(...problem(413, 'The image is too large.'));
      await settle();
      expect(spy).toHaveBeenCalledTimes(1);
    });

    it('puts an image in the new editor when its upload ends after going back from Preview', async () => {
      await open('/journal', { entry: journalEntry(TODAY, { bodyMd: 'Lake', version: 2 }) });
      const upload = pasteImage();
      el('.mode-preview').click();
      await settle();
      el('.mode-edit').click();
      await settle();
      vi.useFakeTimers();

      upload.flush(IMAGE);
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();
      // Once, without the placeholder.
      expect(editor().value()).toBe(`Lake\n${IMAGE.markdown}\n`);
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);

      const [save, ...more] = puts();
      expect(more).toEqual([]);
      expect(save.request.body.bodyMd).toBe(`Lake\n${IMAGE.markdown}\n`);
      expect(save.request.headers.get('If-Match')).toBe('"2"');
      save.flush(journalEntry(TODAY, { bodyMd: save.request.body.bodyMd, version: 3 }));
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
      harness.detectChanges();
      expect(puts()).toEqual([]);
      expect(state()).toBe('Saved');
    });

    it('saves an image still uploading when the journal is left', async () => {
      await open('/journal', { entry: journalEntry(TODAY, { bodyMd: 'Lake', version: 2 }) });
      const upload = pasteImage();

      void harness.navigateByUrl('/elsewhere');
      await settle();
      const first = http.expectOne((r) => r.method === 'PUT');
      expect(first.request.body.bodyMd).not.toContain('uploading:');
      first.flush(journalEntry(TODAY, { bodyMd: first.request.body.bodyMd, version: 3 }));
      await settle();
      expect(harness.routeNativeElement?.textContent).toContain('Elsewhere');

      vi.useFakeTimers();
      upload.flush(IMAGE);
      await vi.advanceTimersByTimeAsync(0);
      const second = http.expectOne((r) => r.method === 'PUT');
      expect(second.request.body.bodyMd).toBe(`Lake\n${IMAGE.markdown}\n`);
      expect(second.request.headers.get('If-Match')).toBe('"3"');
      second.flush(journalEntry(TODAY, { bodyMd: second.request.body.bodyMd, version: 4 }));
      // The page is gone: no autosave after that.
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
      expect(puts()).toEqual([]);
    });
  });

  describe('heatmap', () => {
    it('shows the entries of the last year and opens a day on click', async () => {
      const heatmap = journalHeatmap(TODAY, [
        { date: '2026-10-20', mood: 5 },
        { date: '2026-10-21' },
      ]);
      await open('/journal', {}, heatmap);

      expect(heatmapDay('2026-10-20').getAttribute('data-mood')).toBe('5');
      expect(heatmapDay('2026-10-21').getAttribute('data-mood')).toBe('none');
      expect(heatmapDay('2025-10-25')).not.toBeNull();
      expect(text(root().querySelector('app-journal-heatmap .count'))).toBe(
        '2 entries in the last 12 months',
      );

      heatmapDay('2026-10-20').click();
      await settle();
      expect(router.url).toBe('/journal/2026-10-20');
      // The page stays: no new heatmap.
      flushDay('2026-10-20', { entry: journalEntry('2026-10-20', { bodyMd: 'Hike', mood: 5 }) });
      await settle();

      expect(text(root().querySelector('h1'))).toBe('Tuesday, October 20, 2026');
      expect(root().querySelector('.relative')).toBeNull();
      expect(editor().value()).toBe('Hike');
      expect(heatmapDay('2026-10-20').classList).toContain('selected');

      // Today from the heatmap is the today route.
      heatmapDay(TODAY).click();
      await settle();
      expect(router.url).toBe('/journal');
      flushDay(TODAY);
      await settle();
    });

    it('starts the weeks on the configured week start', async () => {
      // 2026-10-25 is a Sunday; with Monday as week start it ends the last column.
      await open('/journal');
      const lastWeek = [...root().querySelectorAll('app-journal-heatmap .week')].at(-1)!;
      expect(lastWeek.querySelectorAll('button.day')).toHaveLength(7);
      expect([...lastWeek.querySelectorAll('button.day')].at(-1)?.getAttribute('data-date')).toBe(
        TODAY,
      );
    });

    it('updates a day of the heatmap when its mood is saved', async () => {
      const heatmap = journalHeatmap(TODAY, [{ date: TODAY, mood: 1 }]);
      await open('/journal', { entry: journalEntry(TODAY, { mood: 1, version: 2 }) }, heatmap);
      vi.useFakeTimers();

      moodButton(5).click();
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      http.expectOne((r) => r.method === 'PUT').flush(journalEntry(TODAY, { mood: 5, version: 3 }));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      expect(heatmapDay(TODAY).getAttribute('data-mood')).toBe('5');
      expect(text(root().querySelector('app-journal-heatmap .count'))).toBe(
        '1 entry in the last 12 months',
      );
    });
  });

  describe('on this day', () => {
    it('lists the entries of the same day in earlier years, with links', async () => {
      await open('/journal', {
        onThisDay: [
          journalEntry('2025-10-25', { bodyMd: '# Lake\n\nSwam **far**.', mood: 4 }),
          journalEntry('2024-10-25', { bodyMd: '', mood: 2 }),
          journalEntry('2023-10-25', { bodyMd: '' }),
        ],
      });

      const items = [...root().querySelectorAll('.earlier-entry')];
      expect(items.map((item) => text(item.querySelector('.earlier-year')))).toEqual([
        '2025, a year ago',
        '2024, 2 years ago',
        '2023, 3 years ago',
      ]);
      expect(items.map((item) => text(item.querySelector('.earlier-text')))).toEqual([
        'Lake Swam far.',
        'Low',
        'Journal 2023-10-25',
      ]);
      expect(items.map((item) => item.getAttribute('href'))).toEqual([
        '/journal/2025-10-25',
        '/journal/2024-10-25',
        '/journal/2023-10-25',
      ]);
      expect(items[0].querySelector('.dot')?.getAttribute('data-mood')).toBe('4');
      expect(items[2].querySelector('.dot')).toBeNull();
    });

    it('says when there are no earlier entries', async () => {
      await open('/journal');
      expect(text(root().querySelector('.earlier .muted'))).toBe(
        'No entries from this day in earlier years. What you write today shows up here next year.',
      );
    });

    it('shows that earlier years failed and still shows the entry', async () => {
      setup();
      harness = await RouterTestingHarness.create();
      void harness.navigateByUrl('/journal');
      await settle();
      flushHeatmap();
      await settle();
      http.expectOne((r) => r.url === ON_THIS_DAY_URL).flush(...problem(503, 'Unavailable.'));
      http.expectOne(`${ENTRIES}/${TODAY}`).flush(journalEntry(TODAY, { bodyMd: 'Rain' }));
      await settle();

      expect(text(root().querySelector('.earlier .muted'))).toBe(
        'Earlier years could not be loaded.',
      );
      expect(editor().value()).toBe('Rain');
      expect(root().querySelector('.status[role="alert"]')).toBeNull();
    });
  });

  describe('navigation', () => {
    it('steps to the previous and next day', async () => {
      await open('/journal');

      el('.previous').click();
      await settle();
      expect(router.url).toBe('/journal/2026-10-24');
      flushDay('2026-10-24');
      await settle();
      expect(text(root().querySelector('.relative'))).toBe('Yesterday');
      expect(el('.next').disabled).toBe(false);
      expect(text(root().querySelector('.go-today'))).toBe('Today');
      expect(root().querySelector('.go-today')?.getAttribute('href')).toBe('/journal');

      // The page stays: no new heatmap.
      el('.previous').click();
      await settle();
      expect(router.url).toBe('/journal/2026-10-23');
      flushDay('2026-10-23', { entry: journalEntry('2026-10-23', { bodyMd: 'Market' }) });
      await settle();
      expect(root().querySelector('.relative')).toBeNull();
      expect(text(root().querySelector('h1'))).toBe('Friday, October 23, 2026');
      expect(editor().value()).toBe('Market');

      el('.next').click();
      await settle();
      expect(router.url).toBe('/journal/2026-10-24');
      flushDay('2026-10-24');
      await settle();

      // The day after yesterday is today, on the today route.
      el('.next').click();
      await settle();
      expect(router.url).toBe('/journal');
      flushDay(TODAY);
      await settle();
      expect(el('.next').disabled).toBe(true);
    });

    it('leads a date after today to today', async () => {
      setup();
      harness = await RouterTestingHarness.create();
      void harness.navigateByUrl('/journal/2026-10-30');
      await settle();
      flushHeatmap();
      await settle();
      await settle();

      expect(router.url).toBe('/journal');
      http.expectNone((r) => r.url.endsWith('2026-10-30'));
      // The heatmap is known already: only today loads.
      flushDay(TODAY);
      await settle();
      expect(text(root().querySelector('.relative'))).toBe('Today');
    });

    it('leads a date that is no date to today without loading it', async () => {
      setup();
      harness = await RouterTestingHarness.create();
      void harness.navigateByUrl('/journal/2026-02-30');
      await settle();
      await settle();

      expect(router.url).toBe('/journal');
      http.expectNone((r) => r.url.endsWith('2026-02-30'));
      flushHeatmap();
      await settle();
      flushDay(TODAY);
      await settle();
      expect(text(root().querySelector('h1'))).toBe('Sunday, October 25, 2026');
    });

    it('opens a past day directly', async () => {
      await open('/journal/2025-10-25', {
        entry: journalEntry('2025-10-25', { bodyMd: 'First swim' }),
      });
      expect(text(root().querySelector('h1'))).toBe('Saturday, October 25, 2025');
      expect(editor().value()).toBe('First swim');
      expect(heatmapDay('2025-10-25').classList).toContain('selected');
    });

    it('saves the edits of a day before opening the next one', async () => {
      await open('/journal/2026-10-24');

      type('Market day');
      el('.previous').click();
      await settle();

      const save = http.expectOne((r) => r.method === 'PUT' && r.url === `${ENTRIES}/2026-10-24`);
      expect(save.request.body.bodyMd).toBe('Market day');
      expect(save.request.headers.has('If-Match')).toBe(false);
      // The next day waits for the save.
      http.expectNone((r) => r.url === `${ENTRIES}/2026-10-23`);
      save.flush(journalEntry('2026-10-24', { bodyMd: 'Market day' }));
      await settle();

      flushDay('2026-10-23');
      await settle();
      expect(router.url).toBe('/journal/2026-10-23');
      expect(editor().value()).toBe('');
      expect(heatmapDay('2026-10-24').classList).toContain('entry');
    });

    it('keeps the page between today and another day, saving before the next day loads', async () => {
      await open('/journal');
      const page = harness.routeDebugElement?.componentInstance as JournalPage;

      type('Swim');
      el('.previous').click();
      await settle();
      const save = http.expectOne((r) => r.method === 'PUT' && r.url === `${ENTRIES}/${TODAY}`);
      // Yesterday waits for today's save, and nothing loads twice.
      http.expectNone((r) => r.method === 'GET');
      save.flush(journalEntry(TODAY, { bodyMd: 'Swim', version: 1 }));
      await settle();
      flushDay('2026-10-24');
      await settle();
      expect(router.url).toBe('/journal/2026-10-24');
      expect(harness.routeDebugElement?.componentInstance).toBe(page);

      el('.next').click();
      await settle();
      expect(router.url).toBe('/journal');
      flushDay(TODAY, { entry: journalEntry(TODAY, { bodyMd: 'Swim', version: 1 }) });
      await settle();
      expect(harness.routeDebugElement?.componentInstance).toBe(page);
      expect(editor().value()).toBe('Swim');
      expect(state()).toBe('Saved');
    });

    it('stays on a day whose save failed', async () => {
      await open('/journal/2026-10-24');

      type('Market day');
      void harness.navigateByUrl('/journal/2026-10-23');
      await settle();
      http.expectOne((r) => r.method === 'PUT').flush(...problem(503, 'Try again later.'));
      await settle();
      await settle();

      expect(router.url).toBe('/journal/2026-10-24');
      http.expectNone((r) => r.url === `${ENTRIES}/2026-10-23`);
      expect(editor().value()).toBe('Market day');
      expect(state()).toBe('Not saved');
    });

    it('stays on today when its save failed', async () => {
      await open('/journal');

      type('Market day');
      el('.previous').click();
      await settle();
      http.expectOne((r) => r.method === 'PUT').flush(...problem(503, 'Try again later.'));
      await settle();
      await settle();

      expect(router.url).toBe('/journal');
      http.expectNone((r) => r.url === `${ENTRIES}/2026-10-24`);
      expect(editor().value()).toBe('Market day');
      expect(state()).toBe('Not saved');
    });

    it('opens only the last day chosen while the save of the day left is running', async () => {
      await open('/journal');
      const page = harness.routeDebugElement?.componentInstance as JournalPage;

      type('Swim');
      el('.previous').click();
      await settle();
      const save = http.expectOne((r) => r.method === 'PUT' && r.url === `${ENTRIES}/${TODAY}`);
      void harness.navigateByUrl('/journal/2026-10-20');
      await settle();
      save.flush(journalEntry(TODAY, { bodyMd: 'Swim', version: 1 }));
      await settle();

      http.expectNone((r) => r.url === `${ENTRIES}/2026-10-24`);
      http.expectNone((r) => r.params.get('date') === '2026-10-24');
      flushDay('2026-10-20', { entry: journalEntry('2026-10-20', { bodyMd: 'Hike' }) });
      await settle();
      expect(router.url).toBe('/journal/2026-10-20');
      expect(harness.routeDebugElement?.componentInstance).toBe(page);
      expect(editor().value()).toBe('Hike');
      expect(puts()).toEqual([]);
    });

    it('saves the edits when leaving the journal', async () => {
      await open('/journal');

      type('Late note');
      void harness.navigateByUrl('/elsewhere');
      await settle();

      const save = http.expectOne((r) => r.method === 'PUT' && r.url === `${ENTRIES}/${TODAY}`);
      expect(save.request.body.bodyMd).toBe('Late note');
      save.flush(journalEntry(TODAY, { bodyMd: 'Late note' }));
      await settle();
    });

    it('ignores the answer of a day that was left before it arrived', async () => {
      await open('/journal/2026-10-24');

      void harness.navigateByUrl('/journal/2026-10-23');
      await settle();
      void harness.navigateByUrl('/journal/2026-10-22');
      await settle();

      const older = http.expectOne(`${ENTRIES}/2026-10-23`);
      const olderOnThisDay = http.expectOne((r) => r.params.get('date') === '2026-10-23');
      flushDay('2026-10-22', {
        entry: journalEntry('2026-10-22', { bodyMd: 'Newer' }),
        onThisDay: [journalEntry('2025-10-22', { bodyMd: 'A year ago' })],
      });
      await settle();
      older.flush(journalEntry('2026-10-23', { bodyMd: 'Older' }));
      olderOnThisDay.flush({ date: '2026-10-23', items: [journalEntry('2025-10-23')] });
      await settle();

      expect(editor().value()).toBe('Newer');
      expect(text(root().querySelector('h1'))).toBe('Thursday, October 22, 2026');
      expect([...root().querySelectorAll('.earlier-text')].map((item) => text(item))).toEqual([
        'A year ago',
      ]);
    });

    it('ignores the failures of a day that was left before they arrived', async () => {
      await open('/journal/2026-10-24');

      void harness.navigateByUrl('/journal/2026-10-23');
      await settle();
      void harness.navigateByUrl('/journal/2026-10-22');
      await settle();

      const older = http.expectOne(`${ENTRIES}/2026-10-23`);
      const olderOnThisDay = http.expectOne((r) => r.params.get('date') === '2026-10-23');
      flushDay('2026-10-22', {
        entry: journalEntry('2026-10-22', { bodyMd: 'Newer' }),
        onThisDay: [journalEntry('2025-10-22', { bodyMd: 'A year ago' })],
      });
      await settle();
      older.flush(...problem(503, 'Try again later.'));
      olderOnThisDay.flush(...problem(503, 'Try again later.'));
      await settle();

      expect(root().querySelector('.status[role="alert"]')).toBeNull();
      expect(editor().value()).toBe('Newer');
      expect(state()).toBe('Saved');
      expect([...root().querySelectorAll('.earlier-text')].map((item) => text(item))).toEqual([
        'A year ago',
      ]);
    });

    it('opens only the day chosen last when the route changes before the first heatmap arrived', async () => {
      setup();
      harness = await RouterTestingHarness.create();
      void harness.navigateByUrl('/journal');
      await settle();
      void harness.navigateByUrl('/journal/2026-10-20');
      await settle();

      const heatmaps = http.match((r) => r.method === 'GET' && r.url === HEATMAP_URL);
      expect(heatmaps).toHaveLength(2);
      // The newer load answers first, the first load last.
      heatmaps[1].flush(journalHeatmap(TODAY, [{ date: '2026-10-19', mood: 4 }]));
      await settle();
      heatmaps[0].flush(journalHeatmap('2026-10-24'));
      await settle();

      http.expectNone((r) => r.url === `${ENTRIES}/${TODAY}`);
      http.expectNone((r) => r.url === ON_THIS_DAY_URL && r.params.get('date') === TODAY);
      flushDay('2026-10-20', { entry: journalEntry('2026-10-20', { bodyMd: 'Hike' }) });
      await settle();

      expect(router.url).toBe('/journal/2026-10-20');
      expect(text(root().querySelector('h1'))).toBe('Tuesday, October 20, 2026');
      expect(editor().value()).toBe('Hike');
      expect(heatmapDay('2026-10-20').classList).toContain('selected');
      // The late answer of the first load does not replace the newer heatmap and today.
      expect(heatmapDay('2026-10-19').classList).toContain('entry');
      expect(heatmapDay(TODAY)).toBeTruthy(); // The older heatmap ends on the 24th.
      expect(el('.go-today')).toBeTruthy();
      expect(el('.next').disabled).toBe(false);
    });
  });

  describe('midnight', () => {
    const TOMORROW = '2026-10-26';

    /** Fakes the clock and the day check only; the page then opens at noon of `TODAY`. */
    async function openAtNoon(url: string, day: Day = {}): Promise<void> {
      vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
      vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
      await open(url, day);
    }

    async function passMidnight(): Promise<void> {
      vi.setSystemTime(new Date(`${TOMORROW}T12:00:00Z`));
      vi.advanceTimersByTime(DAY_CHECK_MS);
      await settle();
    }

    it('moves Today on to the new day, saving the old day first, with the new day in the heatmap', async () => {
      await openAtNoon('/journal', { entry: journalEntry(TODAY, { bodyMd: 'Lake', version: 2 }) });
      type('Lake, late swim');

      await passMidnight();
      flushHeatmap(journalHeatmap(TOMORROW, [{ date: TODAY }]));
      await settle();
      const save = http.expectOne((r) => r.method === 'PUT');
      expect(save.request.url).toBe(`${ENTRIES}/${TODAY}`);
      expect(save.request.headers.get('If-Match')).toBe('"2"');
      expect(save.request.body.bodyMd).toBe('Lake, late swim');
      http.expectNone((r) => r.url === `${ENTRIES}/${TOMORROW}`);
      save.flush(journalEntry(TODAY, { bodyMd: 'Lake, late swim', version: 3 }));
      await settle();
      flushDay(TOMORROW);
      await settle();

      expect(router.url).toBe('/journal');
      expect(text(root().querySelector('h1'))).toBe('Monday, October 26, 2026');
      expect(text(root().querySelector('.relative'))).toBe('Today');
      expect(editor().value()).toBe('');
      expect(heatmapDay(TOMORROW).classList).toContain('selected');
      expect(heatmapDay(TODAY).classList).toContain('entry');
      expect(el('.next').disabled).toBe(true);
    });

    it('checks the day when the tab shows again', async () => {
      await openAtNoon('/journal');
      document.dispatchEvent(new Event('visibilitychange'));
      await settle();
      http.expectNone((r) => r.url === HEATMAP_URL);

      vi.setSystemTime(new Date(`${TOMORROW}T12:00:00Z`));
      document.dispatchEvent(new Event('visibilitychange'));
      await settle();
      flushHeatmap(journalHeatmap(TOMORROW));
      await settle();
      flushDay(TOMORROW);
      await settle();

      expect(text(root().querySelector('h1'))).toBe('Monday, October 26, 2026');
    });

    it('keeps a day opened by its date, which is no longer yesterday', async () => {
      await openAtNoon('/journal/2026-10-24');
      expect(text(root().querySelector('.relative'))).toBe('Yesterday');

      await passMidnight();
      flushHeatmap(journalHeatmap(TOMORROW));
      await settle();

      expect(router.url).toBe('/journal/2026-10-24');
      expect(root().querySelector('.relative')).toBeNull();
      expect(heatmapDay(TOMORROW)).toBeTruthy();
    });

    it('keeps a day in conflict under its date instead of moving on', async () => {
      await openAtNoon('/journal', { entry: journalEntry(TODAY, { bodyMd: 'Mine', version: 2 }) });
      type('Mine, edited');
      await new Promise((resolve) => setTimeout(resolve, AUTOSAVE_DEBOUNCE_MS));
      http.expectOne((r) => r.method === 'PUT').flush(...problem(412, 'Changed elsewhere.'));
      await settle();
      expect(state()).toBe('Not saved: changed elsewhere');

      await passMidnight();
      flushHeatmap(journalHeatmap(TOMORROW));
      await settle();

      expect(router.url).toBe(`/journal/${TODAY}`);
      expect(editor().value()).toBe('Mine, edited');
      expect(state()).toBe('Not saved: changed elsewhere');
      expect(ask).not.toHaveBeenCalled();
      expect(puts()).toEqual([]);
    });

    it('checks again a minute later when the heatmap fails', async () => {
      await openAtNoon('/journal');
      await passMidnight();
      http.expectOne((r) => r.url === HEATMAP_URL).flush(...problem(503, 'Try again later.'));
      await settle();
      expect(text(root().querySelector('.relative'))).toBe('Today');

      vi.advanceTimersByTime(DAY_CHECK_MS);
      await settle();
      flushHeatmap(journalHeatmap(TOMORROW));
      await settle();
      flushDay(TOMORROW);
      await settle();
      expect(text(root().querySelector('h1'))).toBe('Monday, October 26, 2026');
    });

    it('stays on the old day while the server still says so, and asks again a minute later', async () => {
      await openAtNoon('/journal');
      await passMidnight();
      // The browser's day is ahead of the server's (clock skew).
      flushHeatmap(journalHeatmap(TODAY));
      await settle();

      http.expectNone((r) => r.url.startsWith(ENTRIES) || r.url === ON_THIS_DAY_URL);
      expect(router.url).toBe('/journal');
      expect(text(root().querySelector('h1'))).toBe('Sunday, October 25, 2026');
      expect(text(root().querySelector('.relative'))).toBe('Today');

      vi.advanceTimersByTime(DAY_CHECK_MS);
      await settle();
      flushHeatmap(journalHeatmap(TOMORROW));
      await settle();
      flushDay(TOMORROW);
      await settle();
      expect(text(root().querySelector('h1'))).toBe('Monday, October 26, 2026');
    });

    it('keeps a day saved while the new heatmap loads in that heatmap', async () => {
      const before = journalHeatmap(TODAY, [{ date: '2026-10-20', mood: 3 }]);
      vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
      vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
      await open('/journal/2026-10-24', {}, before);
      const page = harness.routeDebugElement?.componentInstance as JournalPage;
      moodButton(4).click();
      harness.detectChanges();

      await passMidnight();
      const heatmap = http.expectOne((r) => r.url === HEATMAP_URL);
      // The save lands while the heatmap request runs; its answer does not know the day yet.
      void page.flush();
      await settle();
      http
        .expectOne((r) => r.method === 'PUT' && r.url === `${ENTRIES}/2026-10-24`)
        .flush(journalEntry('2026-10-24', { bodyMd: '', mood: 4 }));
      await settle();
      heatmap.flush(journalHeatmap(TOMORROW, [{ date: '2026-10-20', mood: 3 }]));
      await settle();

      expect(heatmapDay(TOMORROW)).toBeTruthy();
      expect(heatmapDay('2026-10-24').classList).toContain('entry');
      expect(heatmapDay('2026-10-24').getAttribute('data-mood')).toBe('4');
      expect(text(root().querySelector('app-journal-heatmap .count'))).toBe(
        '2 entries in the last 12 months',
      );
      expect(state()).toBe('Saved');
    });

    it('shows only the newest heatmap when two day checks overlap and the newer answers first', async () => {
      await openAtNoon('/journal');
      await passMidnight();
      document.dispatchEvent(new Event('visibilitychange'));
      await settle();
      const [older, newer] = http.match((r) => r.url === HEATMAP_URL);
      expect(newer).toBeTruthy();

      newer.flush(journalHeatmap(TOMORROW, [{ date: TODAY, mood: 2 }]));
      await settle();
      older.flush(journalHeatmap(TODAY));
      await settle();
      // The new day loads once.
      flushDay(TOMORROW);
      await settle();

      expect(text(root().querySelector('h1'))).toBe('Monday, October 26, 2026');
      expect(heatmapDay(TOMORROW).classList).toContain('selected');
      expect(heatmapDay(TODAY).getAttribute('data-mood')).toBe('2');
      expect(el('.next').disabled).toBe(true);
    });

    it('ignores the older of two overlapping day checks when it answers first', async () => {
      await openAtNoon('/journal');
      await passMidnight();
      document.dispatchEvent(new Event('visibilitychange'));
      await settle();
      const [older, newer] = http.match((r) => r.url === HEATMAP_URL);

      older.flush(journalHeatmap(TOMORROW, [{ date: TODAY, mood: 1 }]));
      await settle();
      http.expectNone((r) => r.url.startsWith(ENTRIES) || r.url === ON_THIS_DAY_URL);
      expect(text(root().querySelector('h1'))).toBe('Sunday, October 25, 2026');

      newer.flush(journalHeatmap(TOMORROW, [{ date: TODAY, mood: 5 }]));
      await settle();
      flushDay(TOMORROW);
      await settle();
      expect(text(root().querySelector('h1'))).toBe('Monday, October 26, 2026');
      expect(heatmapDay(TODAY).getAttribute('data-mood')).toBe('5');
    });

    it('stops checking the day once the journal is left', async () => {
      await openAtNoon('/journal');
      void harness.navigateByUrl('/elsewhere');
      await settle();
      expect(router.url).toBe('/elsewhere');

      await passMidnight();
      document.dispatchEvent(new Event('visibilitychange'));
      await settle();
      http.expectNone((r) => r.url === HEATMAP_URL);
    });

    it('does not move on when the journal was left while the new heatmap loaded', async () => {
      await openAtNoon('/journal');
      await passMidnight();
      const heatmap = http.expectOne((r) => r.url === HEATMAP_URL);
      void harness.navigateByUrl('/elsewhere');
      await settle();

      heatmap.flush(journalHeatmap(TOMORROW));
      await settle();
      expect(router.url).toBe('/elsewhere');
      http.expectNone((r) => r.url.startsWith(ENTRIES) || r.url === ON_THIS_DAY_URL);
    });
  });

  describe('leaving a conflict', () => {
    /** Opens the 24th and lets its save run into a conflict. */
    async function conflict(): Promise<void> {
      await open('/journal/2026-10-24', {
        entry: journalEntry('2026-10-24', { bodyMd: 'Mine', version: 2 }),
      });
      vi.useFakeTimers();
      type('Mine, edited');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      http.expectOne((r) => r.method === 'PUT').flush(...problem(412, 'Changed elsewhere.'));
      await vi.advanceTimersByTimeAsync(0);
      vi.useRealTimers();
      harness.detectChanges();
      expect(state()).toBe('Not saved: changed elsewhere');
    }

    /** Lets the prompt's answer run while the navigation waits for it (no `whenStable`). */
    async function pending(): Promise<void> {
      await tick();
      await tick();
      harness.detectChanges();
    }

    it('stays on the day with my edits when asked to', async () => {
      await conflict();
      ask.mockResolvedValue('stay');
      void harness.navigateByUrl('/journal/2026-10-22');
      await settle();

      expect(ask).toHaveBeenCalledTimes(1);
      expect(router.url).toBe('/journal/2026-10-24');
      http.expectNone((r) => r.url === `${ENTRIES}/2026-10-22`);
      expect(editor().value()).toBe('Mine, edited');
      expect(state()).toBe('Not saved: changed elsewhere');
    });

    it('drops my edits and opens the other day when asked to', async () => {
      await conflict();
      ask.mockResolvedValue('discard');
      void harness.navigateByUrl('/journal/2026-10-22');
      await settle();
      flushDay('2026-10-22', { entry: journalEntry('2026-10-22', { bodyMd: 'Other day' }) });
      await settle();

      expect(router.url).toBe('/journal/2026-10-22');
      expect(editor().value()).toBe('Other day');
      expect(puts()).toEqual([]);
    });

    it('saves my version over the newer one, then opens the other day', async () => {
      await conflict();
      ask.mockResolvedValue('keep-mine');
      void harness.navigateByUrl('/journal/2026-10-22');
      await pending();
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${ENTRIES}/2026-10-24`)
        .flush(journalEntry('2026-10-24', { bodyMd: 'From the phone', version: 3 }));
      await pending();
      const save = http.expectOne((r) => r.method === 'PUT');
      expect(save.request.url).toBe(`${ENTRIES}/2026-10-24`);
      expect(save.request.headers.get('If-Match')).toBe('"3"');
      expect(save.request.body.bodyMd).toBe('Mine, edited');
      save.flush(journalEntry('2026-10-24', { bodyMd: 'Mine, edited', version: 4 }));
      await settle();
      flushDay('2026-10-22');
      await settle();

      expect(router.url).toBe('/journal/2026-10-22');
    });

    it('stays when saving my version fails', async () => {
      await conflict();
      ask.mockResolvedValue('keep-mine');
      void harness.navigateByUrl('/journal/2026-10-22');
      await pending();
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${ENTRIES}/2026-10-24`)
        .flush(journalEntry('2026-10-24', { bodyMd: 'From the phone', version: 3 }));
      await pending();
      http.expectOne((r) => r.method === 'PUT').flush(...problem(412, 'Changed again.'));
      await settle();

      expect(router.url).toBe('/journal/2026-10-24');
      expect(editor().value()).toBe('Mine, edited');
      expect(state()).toBe('Not saved: changed elsewhere');
    });

    it('waits for "Save my version" from the banner instead of saving again', async () => {
      await conflict();
      el('.keep-mine').click();
      await pending();
      const reload = http.expectOne((r) => r.method === 'GET' && r.url === `${ENTRIES}/2026-10-24`);
      ask.mockResolvedValue('keep-mine');
      void harness.navigateByUrl('/journal/2026-10-22');
      await pending();
      expect(ask).toHaveBeenCalledTimes(1);
      // No second reload: the one from the banner is awaited.
      http.expectNone((r) => r.method === 'GET' && r.url === `${ENTRIES}/2026-10-24`);

      reload.flush(journalEntry('2026-10-24', { bodyMd: 'From the phone', version: 3 }));
      await pending();
      const save = http.expectOne((r) => r.method === 'PUT');
      expect(save.request.headers.get('If-Match')).toBe('"3"');
      expect(save.request.body.bodyMd).toBe('Mine, edited');
      save.flush(journalEntry('2026-10-24', { bodyMd: 'Mine, edited', version: 4 }));
      await settle();
      flushDay('2026-10-22');
      await settle();

      expect(router.url).toBe('/journal/2026-10-22');
      http.expectNone((r) => r.url === `${ENTRIES}/2026-10-24`);
      expect(puts()).toEqual([]);
    });

    it('stays when "Save my version" from the banner ends in a conflict again', async () => {
      await conflict();
      el('.keep-mine').click();
      await pending();
      const reload = http.expectOne((r) => r.method === 'GET' && r.url === `${ENTRIES}/2026-10-24`);
      ask.mockResolvedValue('keep-mine');
      void harness.navigateByUrl('/journal/2026-10-22');
      await pending();

      reload.flush(journalEntry('2026-10-24', { bodyMd: 'From the phone', version: 3 }));
      await pending();
      http.expectOne((r) => r.method === 'PUT').flush(...problem(412, 'Changed again.'));
      await settle();

      expect(ask).toHaveBeenCalledTimes(1);
      expect(router.url).toBe('/journal/2026-10-24');
      http.expectNone((r) => r.url.startsWith(ENTRIES));
      expect(editor().value()).toBe('Mine, edited');
      expect(state()).toBe('Not saved: changed elsewhere');
    });

    it('asks before leaving the journal too', async () => {
      await conflict();
      void harness.navigateByUrl('/elsewhere');
      await settle();
      expect(ask).toHaveBeenCalledTimes(1);
      expect(router.url).toBe('/journal/2026-10-24');

      ask.mockResolvedValue('discard');
      void harness.navigateByUrl('/elsewhere');
      await settle();
      expect(router.url).toBe('/elsewhere');
      expect(puts()).toEqual([]);
    });

    it('does not ask without a conflict', async () => {
      await open('/journal/2026-10-24');
      void harness.navigateByUrl('/journal/2026-10-22');
      await settle();
      flushDay('2026-10-22');
      await settle();
      expect(ask).not.toHaveBeenCalled();
      expect(router.url).toBe('/journal/2026-10-22');
    });

    for (const [from, to] of [
      ['/journal', `/journal/${TODAY}`],
      [`/journal/${TODAY}`, '/journal'],
    ] as const) {
      it(`does not ask when the same day is opened by its other URL (${from} to ${to})`, async () => {
        await open(from, { entry: journalEntry(TODAY, { bodyMd: 'Mine', version: 2 }) });
        vi.useFakeTimers();
        type('Mine, edited');
        await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
        http.expectOne((r) => r.method === 'PUT').flush(...problem(412, 'Changed elsewhere.'));
        await vi.advanceTimersByTimeAsync(0);
        vi.useRealTimers();

        void harness.navigateByUrl(to);
        await settle();

        expect(ask).not.toHaveBeenCalled();
        expect(router.url).toBe(to);
        expect(editor().value()).toBe('Mine, edited');
        expect(state()).toBe('Not saved: changed elsewhere');
        expect(puts()).toEqual([]);
      });
    }

    it('saves nothing and stays when the conflict was resolved while asking', async () => {
      await conflict();
      let answer!: (choice: LeaveChoice) => void;
      ask.mockReturnValue(new Promise<LeaveChoice>((resolve) => (answer = resolve)));
      void harness.navigateByUrl('/journal/2026-10-22');
      await pending();
      expect(ask).toHaveBeenCalledTimes(1);

      // Meanwhile the conflict is resolved by taking their version.
      el('.use-theirs').click();
      await pending();
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${ENTRIES}/2026-10-24`)
        .flush(journalEntry('2026-10-24', { bodyMd: 'Theirs', version: 3 }));
      await pending();
      answer('keep-mine');
      await settle();

      expect(router.url).toBe('/journal/2026-10-24');
      http.expectNone((r) => r.url === `${ENTRIES}/2026-10-22`);
      expect(editor().value()).toBe('Theirs');
      expect(state()).toBe('Saved');
      expect(puts()).toEqual([]);
    });
  });

  describe('saves in flight', () => {
    it('saves edits typed during a save after it, with the new version', async () => {
      await open('/journal');
      vi.useFakeTimers();

      type('Swim');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      const first = http.expectOne((r) => r.method === 'PUT');
      type('Swim and sauna');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      // One save at a time: the next one waits for the first.
      expect(puts()).toEqual([]);

      first.flush(journalEntry(TODAY, { bodyMd: 'Swim', version: 1 }));
      await vi.advanceTimersByTimeAsync(0);
      const second = http.expectOne((r) => r.method === 'PUT');
      expect(second.request.headers.get('If-Match')).toBe('"1"');
      expect(second.request.body.bodyMd).toBe('Swim and sauna');
      second.flush(journalEntry(TODAY, { bodyMd: 'Swim and sauna', version: 2 }));
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
      harness.detectChanges();

      expect(puts()).toEqual([]);
      expect(state()).toBe('Saved');
    });

    it('saves edits typed during a save after their own debounce', async () => {
      await open('/journal', { entry: journalEntry(TODAY, { bodyMd: 'Swim', version: 4 }) });
      vi.useFakeTimers();

      type('Swim.');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      const first = http.expectOne((r) => r.method === 'PUT');
      type('Swim..');
      // The answer arrives while the edit is still waiting for its debounce.
      first.flush(journalEntry(TODAY, { bodyMd: 'Swim.', version: 5 }));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();
      expect(state()).toBe('Unsaved changes');

      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      const second = http.expectOne((r) => r.method === 'PUT');
      expect(second.request.headers.get('If-Match')).toBe('"5"');
      expect(second.request.body.bodyMd).toBe('Swim..');
      second.flush(journalEntry(TODAY, { bodyMd: 'Swim..', version: 6 }));
      await vi.advanceTimersByTimeAsync(0);
    });

    it('saves the last edits once when the journal is left during a save', async () => {
      await open('/journal');
      vi.useFakeTimers();

      type('Swim');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      const first = http.expectOne((r) => r.method === 'PUT');
      type('Swim and sauna');
      void harness.navigateByUrl('/elsewhere');
      await vi.advanceTimersByTimeAsync(0);
      expect(puts()).toEqual([]);

      first.flush(journalEntry(TODAY, { bodyMd: 'Swim', version: 1 }));
      await vi.advanceTimersByTimeAsync(0);
      const [second, ...more] = puts();
      expect(more).toEqual([]);
      expect(second.request.headers.get('If-Match')).toBe('"1"');
      expect(second.request.body.bodyMd).toBe('Swim and sauna');
      second.flush(journalEntry(TODAY, { bodyMd: 'Swim and sauna', version: 2 }));
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
      expect(puts()).toEqual([]);
    });

    it('saves a day older than the heatmap without changing the heatmap', async () => {
      const heatmap = journalHeatmap(TODAY, [{ date: '2026-10-20', mood: 3 }]);
      await open('/journal/2024-10-25', {}, heatmap);
      vi.useFakeTimers();

      type('Two years ago');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      http
        .expectOne((r) => r.method === 'PUT' && r.url === `${ENTRIES}/2024-10-25`)
        .flush(journalEntry('2024-10-25', { bodyMd: 'Two years ago' }));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      expect(state()).toBe('Saved');
      expect(root().querySelectorAll('app-journal-heatmap button.day.entry')).toHaveLength(1);
      expect(text(root().querySelector('app-journal-heatmap .count'))).toBe(
        '1 entry in the last 12 months',
      );
    });

    it('adds a saved day to the heatmap in date order', async () => {
      const heatmap = journalHeatmap(TODAY, [
        { date: '2026-10-20', mood: 3 },
        { date: '2026-10-24', mood: 2 },
      ]);
      await open('/journal/2026-10-22', {}, heatmap);
      vi.useFakeTimers();

      moodButton(4).click();
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      http
        .expectOne((r) => r.method === 'PUT')
        .flush(journalEntry('2026-10-22', { bodyMd: '', mood: 4 }));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      expect(
        [...root().querySelectorAll('app-journal-heatmap button.day.entry')].map((d) =>
          d.getAttribute('data-date'),
        ),
      ).toEqual(['2026-10-20', '2026-10-22', '2026-10-24']);
      expect(heatmapDay('2026-10-22').getAttribute('data-mood')).toBe('4');
      expect(text(root().querySelector('app-journal-heatmap .count'))).toBe(
        '3 entries in the last 12 months',
      );
    });
  });

  describe('errors', () => {
    it('offers to retry a failed load', async () => {
      setup();
      harness = await RouterTestingHarness.create();
      void harness.navigateByUrl('/journal');
      await settle();
      expect(text(root().querySelector('.status'))).toBe('Loading…');
      http.expectOne(HEATMAP_URL).flush(...problem(502, 'Bad gateway.'));
      await settle();

      expect(text(root().querySelector('.status[role="alert"] p'))).toBe(
        'The journal could not be loaded: Bad gateway.',
      );
      expect(root().querySelector('app-markdown-editor')).toBeNull();

      el('.status button').click();
      await settle();
      flushHeatmap();
      await settle();
      flushDay(TODAY);
      await settle();
      expect(root().querySelector('.status[role="alert"]')).toBeNull();
      expect(state()).toBe('Not written yet');
    });

    it('shows a failed entry load with a retry', async () => {
      setup();
      harness = await RouterTestingHarness.create();
      void harness.navigateByUrl('/journal');
      await settle();
      flushHeatmap();
      await settle();
      http.expectOne((r) => r.url === ON_THIS_DAY_URL).flush({ date: TODAY, items: [] });
      http.expectOne(`${ENTRIES}/${TODAY}`).flush(...problem(500, 'Database down.'));
      await settle();

      expect(text(root().querySelector('.status[role="alert"] p'))).toBe(
        'The journal could not be loaded: Database down.',
      );
      el('.status button').click();
      await settle();
      // The heatmap is known: only the day loads again.
      flushDay(TODAY, { entry: journalEntry(TODAY, { bodyMd: 'Back' }) });
      await settle();
      expect(editor().value()).toBe('Back');
    });

    it('shows a generic message for a save failure that is not an API error', async () => {
      await open('/journal');
      vi.spyOn(TestBed.inject(JournalService), 'save').mockRejectedValue(new Error('boom'));
      vi.useFakeTimers();

      type('Swim');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      harness.detectChanges();

      expect(state()).toBe('Not saved');
      expect(text(root().querySelector('.banner p'))).toBe('Something went wrong.');
    });

    it('keeps the conflict and shows the error when reloading for "Save my version" fails', async () => {
      await open('/journal', { entry: journalEntry(TODAY, { bodyMd: 'Mine', version: 2 }) });
      vi.useFakeTimers();
      type('Mine, edited');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      http.expectOne((r) => r.method === 'PUT').flush(...problem(412, 'Changed elsewhere.'));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      el('.keep-mine').click();
      await vi.advanceTimersByTimeAsync(0);
      http.expectOne((r) => r.method === 'GET').flush(...problem(503, 'Try again later.'));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      expect(state()).toBe('Not saved: changed elsewhere');
      expect(text(root().querySelector('.banner:not(.conflict) p'))).toBe('Try again later.');
      expect(editor().value()).toBe('Mine, edited');
      expect(puts()).toEqual([]);

      el('.use-theirs').click();
      await vi.advanceTimersByTimeAsync(0);
      http.expectOne((r) => r.method === 'GET').flush(...problem(503, 'Still down.'));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();
      expect(text(root().querySelector('.banner:not(.conflict) p'))).toBe('Still down.');
      expect(editor().value()).toBe('Mine, edited');
    });

    it('shows a failed save and saves again on retry', async () => {
      await open('/journal');
      vi.useFakeTimers();

      type('Swim');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      http.expectOne((r) => r.method === 'PUT').flush(...problem(503, 'Try again later.'));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      expect(state()).toBe('Not saved');
      expect(text(root().querySelector('.banner p'))).toBe('Try again later.');
      el('.retry').click();
      await vi.advanceTimersByTimeAsync(0);
      const retry = http.expectOne((r) => r.method === 'PUT');
      expect(retry.request.headers.has('If-Match')).toBe(false);
      expect(retry.request.body.bodyMd).toBe('Swim');
      retry.flush(journalEntry(TODAY, { bodyMd: 'Swim' }));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      expect(state()).toBe('Saved');
      expect(root().querySelector('.banner')).toBeNull();
    });

    it('stops saving on a conflict and saves my version over the newer one', async () => {
      await open('/journal', { entry: journalEntry(TODAY, { bodyMd: 'Mine', version: 2 }) });
      vi.useFakeTimers();

      type('Mine, edited');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      http.expectOne((r) => r.method === 'PUT').flush(...problem(412, 'Changed elsewhere.'));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      expect(state()).toBe('Not saved: changed elsewhere');
      expect(text(root().querySelector('.banner.conflict p'))).toContain(
        'This entry was changed somewhere else',
      );
      // No autosave while in conflict.
      type('Mine, edited again');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
      expect(puts()).toEqual([]);

      el('.keep-mine').click();
      await vi.advanceTimersByTimeAsync(0);
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${ENTRIES}/${TODAY}`)
        .flush(journalEntry(TODAY, { bodyMd: 'Theirs', version: 5 }));
      await vi.advanceTimersByTimeAsync(0);
      const save = http.expectOne((r) => r.method === 'PUT');
      expect(save.request.headers.get('If-Match')).toBe('"5"');
      expect(save.request.body.bodyMd).toBe('Mine, edited again');
      save.flush(journalEntry(TODAY, { bodyMd: 'Mine, edited again', version: 6 }));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      expect(state()).toBe('Saved');
      expect(root().querySelector('.banner.conflict')).toBeNull();
    });

    it('treats an entry created elsewhere (428) as a conflict and saves my version over it', async () => {
      await open('/journal');
      vi.useFakeTimers();

      type('Mine');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      const create = http.expectOne((r) => r.method === 'PUT');
      expect(create.request.headers.has('If-Match')).toBe(false);
      create.flush(...problem(428, 'If-Match is required.'));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      expect(state()).toBe('Not saved: changed elsewhere');
      expect(root().querySelector('.retry')).toBeNull();

      el('.keep-mine').click();
      await vi.advanceTimersByTimeAsync(0);
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${ENTRIES}/${TODAY}`)
        .flush(journalEntry(TODAY, { bodyMd: 'From the phone', version: 3 }));
      await vi.advanceTimersByTimeAsync(0);
      const save = http.expectOne((r) => r.method === 'PUT');
      expect(save.request.headers.get('If-Match')).toBe('"3"');
      expect(save.request.body.bodyMd).toBe('Mine');
      save.flush(journalEntry(TODAY, { bodyMd: 'Mine', version: 4 }));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      expect(state()).toBe('Saved');
    });

    for (const action of ['keep-mine', 'use-theirs'] as const) {
      it(`ignores the entry "${action}" reloads when it arrives after another day was opened`, async () => {
        await open('/journal/2026-10-24', {
          entry: journalEntry('2026-10-24', { bodyMd: 'Mine', version: 2 }),
        });
        vi.useFakeTimers();
        type('Mine, edited');
        await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
        http.expectOne((r) => r.method === 'PUT').flush(...problem(412, 'Changed elsewhere.'));
        await vi.advanceTimersByTimeAsync(0);
        harness.detectChanges();
        expect(state()).toBe('Not saved: changed elsewhere');

        el(`.${action}`).click();
        await vi.advanceTimersByTimeAsync(0);
        const reload = http.expectOne(
          (r) => r.method === 'GET' && r.url === `${ENTRIES}/2026-10-24`,
        );
        vi.useRealTimers();
        ask.mockResolvedValue('discard');
        void harness.navigateByUrl('/journal/2026-10-22');
        await settle();
        // "Discard my edits" was chosen already; "Save my version" still running asks again.
        expect(ask).toHaveBeenCalledTimes(action === 'keep-mine' ? 1 : 0);
        flushDay('2026-10-22', { entry: journalEntry('2026-10-22', { bodyMd: 'Other day' }) });
        await settle();
        reload.flush(journalEntry('2026-10-24', { bodyMd: 'Theirs', version: 5 }));
        await settle();

        expect(router.url).toBe('/journal/2026-10-22');
        expect(editor().value()).toBe('Other day');
        expect(state()).toBe('Saved');
        expect(puts()).toEqual([]);
      });

      it(`does not show a "${action}" reload failure that arrives after another day was opened`, async () => {
        await open('/journal/2026-10-24', {
          entry: journalEntry('2026-10-24', { bodyMd: 'Mine', version: 2 }),
        });
        vi.useFakeTimers();
        type('Mine, edited');
        await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
        http.expectOne((r) => r.method === 'PUT').flush(...problem(412, 'Changed elsewhere.'));
        await vi.advanceTimersByTimeAsync(0);
        harness.detectChanges();

        el(`.${action}`).click();
        await vi.advanceTimersByTimeAsync(0);
        const reload = http.expectOne(
          (r) => r.method === 'GET' && r.url === `${ENTRIES}/2026-10-24`,
        );
        vi.useRealTimers();
        ask.mockResolvedValue('discard');
        void harness.navigateByUrl('/journal/2026-10-22');
        await settle();
        // "Discard my edits" was chosen already; "Save my version" still running asks again.
        expect(ask).toHaveBeenCalledTimes(action === 'keep-mine' ? 1 : 0);
        flushDay('2026-10-22', { entry: journalEntry('2026-10-22', { bodyMd: 'Other day' }) });
        await settle();
        reload.flush(...problem(503, 'Try again later.'));
        await settle();

        expect(router.url).toBe('/journal/2026-10-22');
        expect(editor().value()).toBe('Other day');
        expect(state()).toBe('Saved');
        expect(root().querySelector('.banner')).toBeNull();
        expect(puts()).toEqual([]);
      });
    }

    it('drops my edits on a conflict when asked and shows the newer entry', async () => {
      await open('/journal', { entry: journalEntry(TODAY, { bodyMd: 'Mine', version: 2 }) });
      vi.useFakeTimers();

      type('Mine, edited');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      http.expectOne((r) => r.method === 'PUT').flush(...problem(409, 'Changed elsewhere.'));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      el('.use-theirs').click();
      await vi.advanceTimersByTimeAsync(0);
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${ENTRIES}/${TODAY}`)
        .flush(journalEntry(TODAY, { bodyMd: 'Theirs', mood: 3, version: 5 }));
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      expect(editor().value()).toBe('Theirs');
      expect(moodButton(3).getAttribute('aria-pressed')).toBe('true');
      expect(state()).toBe('Saved');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS * 2);
      expect(puts()).toEqual([]);
    });

    it('asks before closing the window with unsaved edits', async () => {
      await open('/journal');
      const unload = (): Event => {
        const event = new Event('beforeunload', { cancelable: true });
        window.dispatchEvent(event);
        return event;
      };
      expect(unload().defaultPrevented).toBe(false);

      vi.useFakeTimers();
      type('Swim');
      expect(unload().defaultPrevented).toBe(true);
      await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
      http.expectOne((r) => r.method === 'PUT').flush(journalEntry(TODAY, { bodyMd: 'Swim' }));
      await vi.advanceTimersByTimeAsync(0);
      expect(unload().defaultPrevented).toBe(false);
    });
  });
});
