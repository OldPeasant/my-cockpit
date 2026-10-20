import { DatePipe } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  ElementRef,
  inject,
  Injector,
  OnInit,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { TrashItemResponse } from '../../api/generated';
import { ApiError, NotificationService } from '../../core/http';
import { ItemTypeIcon, itemTypeLabel } from '../../shared/links';
import { PurgeDialog } from './purge-dialog';
import { TrashService } from './trash.service';

const DAY_MS = 24 * 60 * 60 * 1000;

/** From this many days left on, the countdown is shown as urgent. */
export const PURGE_SOON_DAYS = 3;

/** Whole days until `purgeAt`, rounded up; 0 once it is due. */
export function daysUntilPurge(purgeAt: string, now: number = Date.now()): number {
  return Math.max(0, Math.ceil((Date.parse(purgeAt) - now) / DAY_MS));
}

/**
 * The Trash (SPEC.md §4.2): deleted items of all types, most recently deleted first, each with its
 * deletion date and the days left until it is purged; restore it, or delete it permanently after a
 * confirmation.
 */
@Component({
  selector: 'app-trash-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, ItemTypeIcon, MatButtonModule],
  templateUrl: './trash-page.html',
  styleUrl: './trash-page.scss',
})
export default class TrashPage implements OnInit {
  private readonly service = inject(TrashService);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);

  private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');
  private readonly restoreButtons = viewChildren('restoreButton', {
    read: ElementRef<HTMLButtonElement>,
  });

  /** The items loaded so far; null while the first page loads. */
  protected readonly items = signal<TrashItemResponse[] | null>(null);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly loadingMore = signal(false);
  protected readonly moreError = signal<string | null>(null);
  /** Keys of the items whose restore or delete is still running. */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());
  /** The last failed action, with the title of its item. */
  protected readonly actionError = signal<{ title: string; message: string } | null>(null);

  /** Counts the first-page loads: a page that arrives for an earlier load is ignored. */
  private loadSeq = 0;

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    const seq = ++this.loadSeq;
    this.loadError.set(null);
    this.moreError.set(null);
    this.items.set(null);
    this.nextCursor.set(null);
    try {
      const page = await this.service.list();
      if (seq !== this.loadSeq) return;
      this.items.set(page.items);
      this.nextCursor.set(page.nextCursor ?? null);
    } catch (error) {
      if (seq === this.loadSeq) this.loadError.set(message(error));
    }
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore()) return;
    const seq = this.loadSeq;
    this.loadingMore.set(true);
    this.moreError.set(null);
    try {
      const page = await this.service.list(cursor);
      if (seq !== this.loadSeq) return;
      // An item restored or deleted meanwhile cannot come back; one already shown is not doubled.
      const shown = new Set((this.items() ?? []).map(key));
      this.items.update((items) => [
        ...(items ?? []),
        ...page.items.filter((item) => !shown.has(key(item))),
      ]);
      this.nextCursor.set(page.nextCursor ?? null);
    } catch (error) {
      if (seq === this.loadSeq) this.moreError.set(message(error));
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected async restore(item: TrashItemResponse): Promise<void> {
    const done = await this.run(item, () => this.service.restore(item));
    if (done) {
      const title = item.title;
      this.notifications.info($localize`:@@trash.restored:Restored “${title}:title:”`);
    }
  }

  protected async purge(item: TrashItemResponse): Promise<void> {
    const confirmed = await firstValueFrom(
      this.dialog
        .open<PurgeDialog, { title: string }, boolean>(PurgeDialog, {
          data: { title: item.title },
        })
        .afterClosed(),
    );
    if (!confirmed) return;
    await this.run(item, () => this.service.purge(item));
  }

  protected isBusy(item: TrashItemResponse): boolean {
    return this.busy().has(key(item));
  }

  protected typeLabel(item: TrashItemResponse): string {
    return itemTypeLabel(item.type);
  }

  protected daysLeft(item: TrashItemResponse): number {
    return daysUntilPurge(item.purgeAt);
  }

  protected soon(item: TrashItemResponse): boolean {
    return this.daysLeft(item) <= PURGE_SOON_DAYS;
  }

  protected restoreLabel(item: TrashItemResponse): string {
    const title = item.title;
    return $localize`:@@trash.restoreAria:Restore ${title}:title:`;
  }

  protected purgeLabel(item: TrashItemResponse): string {
    const title = item.title;
    return $localize`:@@trash.purgeAria:Delete ${title}:title: permanently`;
  }

  /**
   * Runs a restore or delete of `item` and drops it from the list when it succeeded or the item was
   * no longer in the Trash (404: restored or deleted elsewhere). Returns whether the action succeeded.
   */
  private async run(item: TrashItemResponse, action: () => Promise<void>): Promise<boolean> {
    const id = key(item);
    if (this.busy().has(id)) return false;
    this.busy.update((ids) => new Set(ids).add(id));
    this.actionError.set(null);
    try {
      await action();
      this.drop(id);
      return true;
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) this.drop(id);
      else this.actionError.set({ title: item.title, message: message(error) });
      return false;
    } finally {
      this.busy.update((ids) => {
        const rest = new Set(ids);
        rest.delete(id);
        return rest;
      });
    }
  }

  private drop(id: string): void {
    const index = this.items()?.findIndex((item) => key(item) === id) ?? -1;
    if (index < 0) return;
    this.items.update((items) => items?.filter((item) => key(item) !== id) ?? null);
    // The removed row took the focus with it: go on with the next row, the previous one or the heading.
    afterNextRender(
      () => {
        const active = this.document.activeElement;
        if (active && active !== this.document.body) return;
        const buttons = this.restoreButtons().map((ref) => ref.nativeElement);
        const target = [buttons[index], buttons[index - 1]].find((b) => b && !b.disabled);
        (target ?? this.heading()?.nativeElement)?.focus();
      },
      { injector: this.injector },
    );
  }
}

function key(item: Pick<TrashItemResponse, 'type' | 'id'>): string {
  return `${item.type}:${item.id}`;
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@trash.error:Something went wrong.`;
}
