import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, Router } from '@angular/router';
import { BookmarkResponse } from '../../api/generated';
import { ApiError, NotificationService } from '../../core/http';
import { BackButton, BackNavigation } from '../../shared/navigation';
import { renderMarkdown } from '../gtd/projects/markdown';
import { hostOf } from './bookmark-display';
import { BookmarksService, Snapshot } from './bookmarks.service';

/**
 * The readable snapshot of a bookmarked page (`/bookmarks/:id`), stored as a file when it was
 * saved (SPEC.md §8.4): Markdown rendered, other text as it is. Mark it read from here.
 */
@Component({
  selector: 'app-bookmark-reader',
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [BackButton, MatButtonModule],
  templateUrl: './bookmark-reader.html',
  styleUrl: './bookmark-reader.scss',
})
export default class BookmarkReader {
  private readonly service = inject(BookmarksService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /** The route's `:id`. */
  private id = '';
  /** Counts the loads: a bookmark that arrives for an earlier id is ignored. */
  private loadSeq = 0;

  protected readonly bookmark = signal<BookmarkResponse | null>(null);
  protected readonly snapshot = signal<Snapshot | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly snapshotError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly html = computed(() => {
    const snapshot = this.snapshot();
    return snapshot?.markdown ? renderMarkdown(snapshot.text) : null;
  });

  protected readonly hostOf = hostOf;

  /** The list's view and tag (query), kept on the way back to it. */
  private readonly query = toSignal(this.route.queryParams, {
    initialValue: this.route.snapshot.queryParams,
  });
  /** The back button's destination when the bookmark was not opened from a page of the app. */
  protected readonly backUrl = computed(() =>
    this.router.serializeUrl(
      this.router.createUrlTree(['/bookmarks'], { queryParams: this.query() }),
    ),
  );
  protected readonly bookmarksLabel = $localize`:@@nav.bookmarks:Bookmarks`;

  constructor() {
    // The back buttons of the pages opened from here name the bookmark.
    const backNavigation = inject(BackNavigation);
    effect(() => {
      const bookmark = this.bookmark();
      if (bookmark?.id && bookmark.displayTitle) {
        const url = this.router.serializeUrl(
          this.router.createUrlTree(['/bookmarks', bookmark.id]),
        );
        backNavigation.setLabel(url, bookmark.displayTitle);
      }
    });
    this.route.paramMap.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((params) => {
      this.id = params.get('id') ?? '';
      void this.load();
    });
  }

  protected async load(): Promise<void> {
    const seq = ++this.loadSeq;
    this.loadError.set(null);
    this.snapshotError.set(null);
    this.bookmark.set(null);
    this.snapshot.set(null);
    try {
      const bookmark = await this.service.get(this.id);
      if (seq !== this.loadSeq) return;
      this.bookmark.set(bookmark);
      if (!bookmark.snapshotFileId) return;
      try {
        const snapshot = await this.service.snapshot(bookmark.snapshotFileId);
        if (seq === this.loadSeq) this.snapshot.set(snapshot);
      } catch (error) {
        if (seq === this.loadSeq) this.snapshotError.set(message(error));
      }
    } catch (error) {
      if (seq === this.loadSeq) this.loadError.set(message(error));
    }
  }

  protected async toggleRead(): Promise<void> {
    const bookmark = this.bookmark();
    if (!bookmark || this.busy()) return;
    this.busy.set(true);
    try {
      this.bookmark.set(await this.service.update(bookmark, { read: !bookmark.readAt }));
    } catch (error) {
      this.notifications.error(message(error));
      if (error instanceof ApiError && (error.status === 409 || error.status === 412)) {
        try {
          this.bookmark.set(await this.service.get(bookmark.id));
        } catch {
          // The error is shown already.
        }
      }
    } finally {
      this.busy.set(false);
    }
  }
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@bookmarks.error:Something went wrong.`;
}
