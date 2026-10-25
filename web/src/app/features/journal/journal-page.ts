import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  LOCALE_ID,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { JournalEntryResponse, JournalHeatmapResponse } from '../../api/generated';
import { ApiError } from '../../core/http';
import { MarkdownEditor, withoutUploadPlaceholders } from '../notes/editor/markdown-editor';
import { NoteMarkdownView } from '../notes/editor/note-markdown-view';
import { SettingsService } from '../settings/settings.service';
import { addDays, excerpt, isDate, isoWeekday, parseTags } from './journal-dates';
import { JournalHeatmap } from './journal-heatmap';
import { JournalDraft, JournalService } from './journal.service';

/** Wait after the last keystroke before saving. */
export const AUTOSAVE_DEBOUNCE_MS = 1000;

/** Where the edits stand: saved, waiting for the debounce, saving, failed or in conflict. */
export type SaveState = 'saved' | 'pending' | 'saving' | 'error' | 'conflict';

/** The moods to pick from, 1 (bad) to 5 (great). */
export const MOODS = [1, 2, 3, 4, 5] as const;

/**
 * The journal (`/journal` for today, `/journal/:date`, SPEC.md §8.5): the entry of one day in a
 * CodeMirror Markdown editor with images by paste or drop, its mood and tags, saved as you type
 * (debounced; the first edit creates the entry, later ones send `If-Match`). Above it the calendar
 * heatmap of the last year, which opens any day; beside it the entries of the same day in earlier
 * years. Days after today (in the user's time zone) cannot be written: they lead to today.
 */
@Component({
  selector: 'app-journal-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [JournalHeatmap, MarkdownEditor, MatButtonModule, NoteMarkdownView, RouterLink],
  host: { '(window:beforeunload)': 'onBeforeUnload($event)' },
  templateUrl: './journal-page.html',
  styleUrl: './journal-page.scss',
})
export default class JournalPage {
  private readonly service = inject(JournalService);
  private readonly router = inject(Router);
  private readonly locale = inject(LOCALE_ID);
  private readonly settings = inject(SettingsService).settings;

  protected readonly moods = MOODS;
  protected readonly journalLabel = $localize`:@@nav.journal:Journal`;
  protected readonly placeholder = $localize`:@@journal.placeholder:How was the day? Paste or drop images here.`;

  /** Today in the user's time zone, from the server; null until the heatmap arrived. */
  protected readonly today = signal<string | null>(null);
  /** The day shown (`yyyy-MM-dd`); '' until known. */
  protected readonly date = signal('');
  protected readonly heatmap = signal<JournalHeatmapResponse | null>(null);
  /** The entry as last saved; null when the day has none (yet). */
  protected readonly entry = signal<JournalEntryResponse | null>(null);
  protected readonly loaded = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly onThisDay = signal<JournalEntryResponse[]>([]);
  protected readonly onThisDayError = signal(false);

  protected readonly body = signal('');
  protected readonly mood = signal<number | null>(null);
  protected readonly tagsText = signal('');
  protected readonly preview = signal(false);
  protected readonly saveState = signal<SaveState>('saved');
  protected readonly saveError = signal<string | null>(null);

  protected readonly firstDay = computed(() => isoWeekday(this.settings()?.weekStart));
  protected readonly isToday = computed(() => !!this.date() && this.date() === this.today());
  protected readonly canGoForward = computed(() => {
    const today = this.today();
    return !!today && this.date() < today;
  });
  protected readonly title = computed(() => (this.date() ? this.longDate(this.date()) : ''));
  protected readonly relative = computed(() => {
    const today = this.today();
    const date = this.date();
    if (!today || !date) return null;
    if (date === today) return $localize`:@@journal.today:Today`;
    if (date === addDays(today, -1)) return $localize`:@@journal.yesterday:Yesterday`;
    return null;
  });
  protected readonly dirty = computed(() => this.loaded() && !!changes(this.entry(), this.draft()));

  /**
   * Uploads an image; tracked, so leaving the day waits for it. The editor puts the result in place
   * of the placeholder; when it is not shown (Preview), the page does that in the text itself.
   */
  protected readonly uploadImage = (file: File, placeholder: string): Promise<string> => {
    const upload = this.service.uploadImage(file).then((image) => image.markdown);
    // Chained one step behind `upload`, so the editor's own handler on it has run by then.
    const settled = upload
      .then(
        (markdown) => ({ markdown, failed: false, error: null as unknown }),
        (error: unknown) => ({ markdown: '', failed: true, error }),
      )
      .then(({ markdown, failed, error }) => {
        this.uploads.delete(placeholder);
        this.settleUpload(placeholder, markdown, failed, error);
      });
    this.uploads.set(placeholder, settled);
    return upload;
  };

  private readonly draft = computed<JournalDraft>(() => ({
    bodyMd: withoutUploadPlaceholders(this.body()),
    mood: this.mood(),
    tags: parseTags(this.tagsText()),
  }));
  private timer?: ReturnType<typeof setTimeout>;
  private inFlight: Promise<void> | null = null;
  private destroyed = false;
  /** Images still uploading, by their placeholder in the body: settled once put in place. */
  private readonly uploads = new Map<string, Promise<void>>();
  /** Counts the loads: a day that arrives after another one was opened is ignored. */
  private loadSeq = 0;
  /** Counts the route changes: while leaving waits, a newer route wins. */
  private openSeq = 0;

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed())
      .subscribe((params) => void this.open(params.get('date')));
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      void this.leave();
    });
  }

  /** Loads the shown day again (after an error). */
  protected async load(): Promise<void> {
    const seq = ++this.loadSeq;
    this.loadError.set(null);
    this.loaded.set(false);
    try {
      if (!this.today()) await this.loadHeatmap();
      if (seq !== this.loadSeq) return;
      const today = this.today() ?? '';
      const date = this.date() || today;
      if (date > today) {
        void this.router.navigate(['/journal'], { replaceUrl: true });
        return;
      }
      if (date !== this.date()) this.date.set(date);
      void this.loadOnThisDay(date, seq);
      const entry = await this.service.get(date);
      if (seq !== this.loadSeq) return;
      this.show(entry);
    } catch (error) {
      if (seq === this.loadSeq) this.loadError.set(message(error));
    }
  }

  protected openDay(date: string): void {
    void this.router.navigate(date === this.today() ? ['/journal'] : ['/journal', date]);
  }

  protected step(days: number): void {
    const date = addDays(this.date(), days);
    const today = this.today();
    if (today && date > today) return;
    this.openDay(date);
  }

  protected onBody(body: string): void {
    this.body.set(body);
    this.schedule();
  }

  protected setMood(mood: number): void {
    this.mood.set(this.mood() === mood ? null : mood);
    this.schedule();
  }

  protected onTags(event: Event): void {
    this.tagsText.set((event.target as HTMLInputElement).value);
    this.schedule();
  }

  /** Leaving the tags field shows them as they are saved. */
  protected tidyTags(): void {
    this.tagsText.set(parseTags(this.tagsText()).join(', '));
  }

  protected moodLabel(mood: number): string {
    const labels: Record<number, string> = {
      1: $localize`:@@journal.mood.1:Bad`,
      2: $localize`:@@journal.mood.2:Low`,
      3: $localize`:@@journal.mood.3:Okay`,
      4: $localize`:@@journal.mood.4:Good`,
      5: $localize`:@@journal.mood.5:Great`,
    };
    return labels[mood] ?? '';
  }

  protected onUploadFailed(error: unknown): void {
    this.saveError.set(
      $localize`:@@journal.uploadFailed:The image could not be uploaded: ${message(error)}:message:`,
    );
  }

  /** The year of an earlier entry and how long ago it is, e.g. "2024, 2 years ago". */
  protected yearsAgo(entry: JournalEntryResponse): string {
    const year = entry.date.slice(0, 4);
    const years = Number(this.date().slice(0, 4)) - Number(year);
    return years === 1
      ? $localize`:@@journal.onThisDay.oneYear:${year}:year:, a year ago`
      : $localize`:@@journal.onThisDay.years:${year}:year:, ${years}:years: years ago`;
  }

  protected readonly excerpt = excerpt;

  /** Saves now what is not saved yet; waits for a save that is running. */
  async flush(): Promise<void> {
    clearTimeout(this.timer);
    this.timer = undefined;
    while (this.inFlight) await this.inFlight;
    if (!this.loaded() || this.saveState() === 'conflict') return;
    const draft = changes(this.entry(), this.draft());
    if (!draft) {
      if (this.saveState() !== 'error') this.saveState.set('saved');
      return;
    }
    this.inFlight = this.persist(this.date(), this.entry(), draft);
    try {
      await this.inFlight;
    } finally {
      this.inFlight = null;
    }
  }

  protected retry(): void {
    this.saveError.set(null);
    this.saveState.set('pending');
    void this.flush();
  }

  /** Conflict: drop the local edits and show the entry as it is now. */
  protected async useTheirs(): Promise<void> {
    const seq = this.loadSeq;
    try {
      const entry = await this.service.get(this.date());
      if (seq === this.loadSeq) this.show(entry);
    } catch (error) {
      if (seq === this.loadSeq) this.saveError.set(message(error));
    }
  }

  /** Conflict: save the local edits over the newer version. */
  protected async keepMine(): Promise<void> {
    const seq = this.loadSeq;
    try {
      const entry = await this.service.get(this.date());
      // Another day was opened meanwhile: its text must not overwrite this one.
      if (seq !== this.loadSeq) return;
      this.entry.set(entry);
      this.saveError.set(null);
      this.saveState.set('pending');
      await this.flush();
    } catch (error) {
      if (seq === this.loadSeq) this.saveError.set(message(error));
    }
  }

  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.dirty() || this.saveState() === 'saving' || this.uploads.size) event.preventDefault();
  }

  /**
   * Shows the day of the route (`null`: today), after saving the edits of the day shown before. A
   * date that is no date or after today leads to today; if the save fails, it stays on that day.
   */
  private async open(param: string | null): Promise<void> {
    const seq = ++this.openSeq;
    if (param !== null && !isDate(param)) {
      void this.router.navigate(['/journal'], { replaceUrl: true });
      return;
    }
    const target = param ?? this.today() ?? '';
    if (target && target === this.date()) return;
    if (this.loaded()) await this.leave();
    if (this.destroyed || seq !== this.openSeq) return;
    if (this.saveState() === 'error' && this.dirty()) {
      void this.router.navigate(this.isToday() ? ['/journal'] : ['/journal', this.date()], {
        replaceUrl: true,
      });
      return;
    }
    this.date.set(target);
    this.entry.set(null);
    this.loaded.set(false);
    this.onThisDay.set([]);
    await this.load();
  }

  /**
   * Leaving the day (another day or page): saves what is not saved yet, then waits for the images
   * still uploading and saves them in place of their placeholders.
   */
  private async leave(): Promise<void> {
    await this.flush();
    if (!this.uploads.size) return;
    while (this.uploads.size) await Promise.all(this.uploads.values());
    await this.flush();
  }

  /**
   * An upload ended. A placeholder still in the text was not handled by the editor (it is not
   * shown, e.g. in Preview): put the image in its place (or drop it) and report a failure here.
   */
  private settleUpload(
    placeholder: string,
    markdown: string,
    failed: boolean,
    error: unknown,
  ): void {
    if (!this.body().includes(placeholder)) return;
    this.body.update((body) => body.replace(placeholder, () => markdown));
    if (failed) this.onUploadFailed(error);
    // Leaving the day saves it itself, once every upload ended.
    else if (!this.destroyed) this.schedule();
  }

  private async loadHeatmap(): Promise<void> {
    const heatmap = await this.service.heatmap();
    this.heatmap.set(heatmap);
    this.today.set(heatmap.to);
  }

  private async loadOnThisDay(date: string, seq: number): Promise<void> {
    this.onThisDayError.set(false);
    try {
      const result = await this.service.onThisDay(date);
      if (seq === this.loadSeq) this.onThisDay.set(result.items);
    } catch {
      if (seq === this.loadSeq) this.onThisDayError.set(true);
    }
  }

  private schedule(): void {
    if (this.saveState() === 'conflict') return;
    clearTimeout(this.timer);
    this.saveState.set('pending');
    this.timer = setTimeout(() => {
      this.timer = undefined;
      void this.flush();
    }, AUTOSAVE_DEBOUNCE_MS);
  }

  private async persist(
    date: string,
    entry: JournalEntryResponse | null,
    draft: JournalDraft,
  ): Promise<void> {
    this.saveState.set('saving');
    this.saveError.set(null);
    try {
      const saved = await this.service.save(date, entry, draft);
      this.markDay(saved);
      // Another day was opened while saving: its editor must keep its own state.
      if (date !== this.date()) return;
      this.entry.set(saved);
      if (!changes(saved, this.draft())) this.saveState.set('saved');
      else if (this.destroyed) {
        if (!sameDraft(draft, this.draft())) void this.flush();
        else this.saveState.set('pending');
      } else if (this.timer === undefined) this.schedule();
      else this.saveState.set('pending');
    } catch (error) {
      if (date !== this.date()) return;
      // 428: the entry was created elsewhere after the day was opened (sent without If-Match).
      if (error instanceof ApiError && [409, 412, 428].includes(error.status)) {
        clearTimeout(this.timer);
        this.timer = undefined;
        this.saveState.set('conflict');
      } else {
        this.saveState.set('error');
        this.saveError.set(message(error));
      }
    }
  }

  /** The saved entry's day in the heatmap, with its mood. */
  private markDay(saved: JournalEntryResponse): void {
    this.heatmap.update((heatmap) => {
      if (!heatmap || saved.date < heatmap.from || saved.date > heatmap.to) return heatmap;
      const day = { date: saved.date, mood: saved.mood };
      const items = heatmap.items.filter((item) => item.date !== saved.date);
      items.push(day);
      items.sort((a, b) => a.date.localeCompare(b.date));
      return { ...heatmap, items };
    });
  }

  private show(entry: JournalEntryResponse | null): void {
    clearTimeout(this.timer);
    this.timer = undefined;
    this.entry.set(entry);
    this.body.set(entry?.bodyMd ?? '');
    this.mood.set(entry?.mood ?? null);
    this.tagsText.set(entry?.tags.join(', ') ?? '');
    this.saveState.set('saved');
    this.saveError.set(null);
    this.loaded.set(true);
  }

  private longDate(date: string): string {
    return new Intl.DateTimeFormat(this.locale, {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      year: 'numeric',
      timeZone: 'UTC',
    }).format(new Date(`${date}T00:00:00Z`));
  }
}

/**
 * The draft when it differs from the saved entry, else null. A day without an entry is created
 * only once something is written, a mood picked or a tag set.
 */
function changes(entry: JournalEntryResponse | null, draft: JournalDraft): JournalDraft | null {
  if (!entry) return draft.bodyMd.trim() || draft.mood !== null || draft.tags.length ? draft : null;
  const same =
    draft.bodyMd === entry.bodyMd &&
    draft.mood === (entry.mood ?? null) &&
    tagsKey(draft.tags) === tagsKey(entry.tags);
  return same ? null : draft;
}

function sameDraft(a: JournalDraft, b: JournalDraft): boolean {
  return a.bodyMd === b.bodyMd && a.mood === b.mood && tagsKey(a.tags) === tagsKey(b.tags);
}

/** The server orders the tags (regardless of case): the order typed is no change. */
function tagsKey(tags: readonly string[]): string {
  return [...tags].sort((a, b) => a.localeCompare(b)).join('\n');
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@journal.error:Something went wrong.`;
}
