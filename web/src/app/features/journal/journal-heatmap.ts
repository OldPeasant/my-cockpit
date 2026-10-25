import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  LOCALE_ID,
  output,
} from '@angular/core';
import { JournalHeatmapResponse } from '../../api/generated';
import { addDays, HeatmapCell, heatmapWeeks } from './journal-dates';

/**
 * The calendar heatmap of the journal (SPEC.md §8.5): one square per day, weeks as columns, filled
 * when the day has an entry and the stronger the better its mood. A day is a button that opens its
 * entry (`daySelected`); the arrow keys move between days (one tab stop: the selected day).
 */
@Component({
  selector: 'app-journal-heatmap',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './journal-heatmap.html',
  styleUrl: './journal-heatmap.scss',
})
export class JournalHeatmap {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly locale = inject(LOCALE_ID);

  readonly heatmap = input.required<JournalHeatmapResponse>();
  /** The day shown in the journal (`yyyy-MM-dd`). */
  readonly selected = input.required<string>();
  /** The ISO weekday the weeks start on (1 = Monday). */
  readonly firstDay = input(1);

  readonly daySelected = output<string>();

  protected readonly weeks = computed(() => {
    const heatmap = this.heatmap();
    return heatmapWeeks(heatmap.from, heatmap.to, heatmap.items, this.firstDay(), this.locale);
  });
  protected readonly count = computed(() => this.heatmap().items.length);
  /** The tab stop: the selected day when it is shown, else the last day. */
  protected readonly focusDate = computed(() => {
    const { from, to } = this.heatmap();
    const selected = this.selected();
    return selected >= from && selected <= to ? selected : to;
  });
  /** The short weekday names of the rows, for the labels next to the grid. */
  protected readonly weekdays = computed(() => {
    const names = new Intl.DateTimeFormat(this.locale, { weekday: 'short', timeZone: 'UTC' });
    const first = this.weeks()[0]?.cells ?? [];
    return first.map((cell) => names.format(new Date(`${cell.date}T00:00:00Z`)));
  });

  private readonly dayLabel = computed(
    () =>
      new Intl.DateTimeFormat(this.locale, {
        weekday: 'long',
        day: 'numeric',
        month: 'long',
        year: 'numeric',
        timeZone: 'UTC',
      }),
  );

  protected label(cell: HeatmapCell): string {
    const day = this.dayLabel().format(new Date(`${cell.date}T00:00:00Z`));
    if (!cell.hasEntry) return $localize`:@@journal.heatmap.empty:${day}:day:, no entry`;
    if (cell.mood === null) return $localize`:@@journal.heatmap.entry:${day}:day:, entry`;
    const mood = cell.mood;
    return $localize`:@@journal.heatmap.mood:${day}:day:, entry, mood ${mood}:mood: of 5`;
  }

  protected onKeydown(event: KeyboardEvent, cell: HeatmapCell): void {
    const step: Record<string, number> = {
      ArrowUp: -1,
      ArrowDown: 1,
      ArrowLeft: -7,
      ArrowRight: 7,
    };
    const days = step[event.key];
    if (days === undefined) return;
    event.preventDefault();
    const { from, to } = this.heatmap();
    const target = addDays(cell.date, days);
    if (target < from || target > to) return;
    this.host.nativeElement.querySelector<HTMLElement>(`[data-date="${target}"]`)?.focus();
  }
}
