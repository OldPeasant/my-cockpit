import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { RouterLink } from '@angular/router';
import { localDate } from '../gtd/data';
import { Count, DashboardData, UPCOMING_DAYS } from './dashboard-data';
import { DashboardLayout, WidgetId } from './dashboard-layout';

/** How many entries a list widget shows; the heading links to the full list. */
export const SHOWN = 5;
/** After this many days the Last weekly review widget asks for the next review. */
export const REVIEW_DUE_DAYS = 7;

/** Title and link of each widget. */
export const WIDGETS: Record<WidgetId, { readonly title: string; readonly link: string }> = {
  today: { title: $localize`:@@dashboard.today:Today`, link: '/today' },
  inbox: { title: $localize`:@@dashboard.inbox:Inbox`, link: '/inbox' },
  upcoming: { title: $localize`:@@dashboard.upcoming:Upcoming events`, link: '/calendar' },
  waitingOverdue: {
    title: $localize`:@@dashboard.waitingOverdue:Waiting For overdue`,
    link: '/waiting',
  },
  stalledProjects: {
    title: $localize`:@@dashboard.stalledProjects:Stalled projects`,
    link: '/projects',
  },
  weeklyReview: { title: $localize`:@@dashboard.weeklyReview:Last weekly review`, link: '/review' },
  readLater: { title: $localize`:@@dashboard.readLater:Read later`, link: '/bookmarks' },
  pinnedNotes: { title: $localize`:@@dashboard.pinnedNotes:Pinned notes`, link: '/notes' },
};

/**
 * The home page (SPEC.md §8.7): the widgets the user chose, in their order. "Customize" shows or
 * hides each widget and moves it up or down; the layout is saved in the user settings
 * ({@link DashboardLayout}). Each widget loads its own data ({@link DashboardData}).
 */
@Component({
  selector: 'app-dashboard-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule, MatCheckboxModule, RouterLink],
  providers: [DashboardData],
  templateUrl: './dashboard-page.html',
  styleUrl: './dashboard-page.scss',
})
export default class DashboardPage {
  protected readonly layout = inject(DashboardLayout);
  protected readonly data = inject(DashboardData);

  protected readonly widgets = WIDGETS;
  protected readonly shown = SHOWN;
  protected readonly upcomingDays = UPCOMING_DAYS;
  protected readonly reviewDueDays = REVIEW_DUE_DAYS;
  protected readonly now = new Date();

  protected readonly customizing = signal(false);
  /** The settings could not be loaded: the default layout shows, and can't be changed. */
  protected readonly settingsFailed = signal(false);
  /** Whether the widgets can show: the stored layout is known (or could not be loaded). */
  protected readonly ready = computed(() => this.layout.loaded() || this.settingsFailed());
  protected readonly visible = computed(() => (this.ready() ? this.layout.visible() : []));

  constructor() {
    this.layout.ensureLoaded().catch(() => this.settingsFailed.set(true));
    effect(() => {
      const visible = this.visible();
      untracked(() => this.data.show(visible));
    });
  }

  protected move(id: WidgetId, offset: number): void {
    this.save(this.layout.move(id, offset));
  }

  protected setVisible(id: WidgetId, visible: boolean): void {
    this.save(this.layout.setVisible(id, visible));
  }

  protected reset(): void {
    this.save(this.layout.reset());
  }

  protected moveUpLabel(id: WidgetId): string {
    return $localize`:@@dashboard.moveUp:Move ${this.widgets[id].title}:title: up`;
  }

  protected moveDownLabel(id: WidgetId): string {
    return $localize`:@@dashboard.moveDown:Move ${this.widgets[id].title}:title: down`;
  }

  protected retry(id: WidgetId): void {
    void this.data.load(id);
  }

  /** "12", or "50+" when the list was cut at its page size. */
  protected count(count: Count): string {
    return count.more ? `${count.count}+` : String(count.count);
  }

  protected isOverdue(dueDate: string | undefined): boolean {
    return dueDate !== undefined && dueDate < localDate();
  }

  /** A failed save is toasted by the HTTP layer and rolled back by the settings service. */
  private save(result: Promise<unknown>): void {
    result.catch(() => undefined);
  }
}
