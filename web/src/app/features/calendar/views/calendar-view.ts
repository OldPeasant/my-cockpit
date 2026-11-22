import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  OnDestroy,
  signal,
  untracked,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, Router } from '@angular/router';
import {
  CalendarOptions,
  DatesSetInfo,
  DateSelectInfo,
  EventChangeInfo,
  EventClickInfo,
  FullCalendarComponent,
  FullCalendarModule,
} from '@fullcalendar/angular';
import dayGridPlugin from '@fullcalendar/angular/daygrid';
import interactionPlugin from '@fullcalendar/angular/interaction';
import listPlugin from '@fullcalendar/angular/list';
import classicTheme from '@fullcalendar/angular/themes/classic';
import timeGridPlugin from '@fullcalendar/angular/timegrid';
import { CalendarResponse, OccurrenceResponse } from '../../../api/generated';
import { NotificationService } from '../../../core/http';
import { SettingsService } from '../../settings/settings.service';
import { EventEditService } from '../edit/event-edit.service';
import { EditorContext, EventEditor, MovedTimes, timesOf } from '../edit/event-editor';
import { addHours, nextFullHour } from '../edit/event-times';
import { occurrenceOfEvent } from '../overlay/event-links.service';
import { DayRange, OverlayTaskStore, TaskOverlay, taskMarkOf } from '../overlay/task-overlay';
import { calendarColor, CalendarStore } from './calendar-store';
import {
  dateIn,
  firstDay,
  parseDate,
  parseView,
  toEventInput,
  VIEW_TYPES,
  viewName,
} from './calendar-views';
import { CHIP_HEIGHT, HiddenChip, hiddenChips, timeGridOf } from './hidden-chips';
import { TimedEntry } from './hidden-entries';
import { OccurrenceRange, OccurrenceStore } from './occurrence-store';

/**
 * The calendar (SPEC.md §6.3): day, week (default), month and agenda views of the event
 * occurrences, in the time zone and with the week start of the settings. The side panel shows or
 * hides each calendar. View and date are in the URL (`?view=week&date=2026-03-23`).
 *
 * Editing: selecting a time range creates an event, clicking one opens it, dragging moves it and
 * dragging its end resizes it (repeating events ask which occurrences the change is for).
 *
 * The day and week views can overlay the due tasks and ticklers of the shown days (a toggle in the
 * side panel; clicking one opens the task). A link to an event (`/calendar/events/{id}`, which
 * redirects to `?event={id}`) shows its date and opens it.
 */
@Component({
  selector: 'app-calendar-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // FullCalendar renders its own DOM: its theme and our overrides must reach it unscoped.
  encapsulation: ViewEncapsulation.None,
  imports: [FullCalendarModule, MatButtonModule],
  providers: [OccurrenceStore, TaskOverlay, OverlayTaskStore],
  templateUrl: './calendar-view.html',
  styleUrls: ['./calendar-view.scss', '../overlay/task-overlay.scss'],
})
export default class CalendarView implements OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly settingsService = inject(SettingsService);
  private readonly notifications = inject(NotificationService);
  protected readonly calendars = inject(CalendarStore);
  protected readonly occurrences = inject(OccurrenceStore);
  private readonly editor = inject(EventEditor);
  private readonly eventService = inject(EventEditService);
  protected readonly overlay = inject(TaskOverlay);

  private readonly calendar = viewChild(FullCalendarComponent);

  /** View and date of the URL when the page opened (the calendar keeps them up to date after). */
  private readonly initial = (() => {
    const params = this.route.snapshot.queryParamMap;
    return {
      view: parseView(params.get('view')),
      date: parseDate(params.get('date')),
      event: params.get('event'),
    };
  })();

  protected readonly settingsFailed = signal(false);
  /** The range the calendar shows, set by FullCalendar. */
  private readonly range = signal<OccurrenceRange | undefined>(undefined);
  /** The FullCalendar view type shown. */
  private readonly viewType = signal<string>(VIEW_TYPES[this.initial.view]);
  /** Whether the shown view can overlay tasks (day and week). */
  protected readonly overlayApplies = computed(() => OVERLAY_VIEWS.has(this.viewType()));

  protected readonly settings = this.settingsService.settings;
  /** All calendar ids as one string, so a visibility toggle doesn't refetch. */
  private readonly calendarIds = computed(() =>
    this.calendars
      .calendars()
      .map((calendar) => calendar.id)
      .join(','),
  );

  protected readonly options = computed((): CalendarOptions | undefined => {
    const settings = this.settings();
    if (!settings) return undefined;
    return {
      plugins: [classicTheme, dayGridPlugin, timeGridPlugin, listPlugin, interactionPlugin],
      initialView: VIEW_TYPES[this.initial.view],
      initialDate: this.initial.date,
      timeZone: settings.timeZone,
      firstDay: firstDay(settings.weekStart),
      headerToolbar: {
        start: 'prev,next today',
        center: 'title',
        end: `${VIEW_TYPES.day},${VIEW_TYPES.week},${VIEW_TYPES.month},${VIEW_TYPES.agenda}`,
      },
      buttons: {
        // The hint is the accessible name: it must contain the visible text (WCAG 2.5.3).
        today: {
          text: $localize`:@@calendar.today:Today`,
          hint: $localize`:@@calendar.today:Today`,
        },
        [VIEW_TYPES.day]: { text: $localize`:@@calendar.view.day:Day` },
        [VIEW_TYPES.week]: { text: $localize`:@@calendar.view.week:Week` },
        [VIEW_TYPES.month]: { text: $localize`:@@calendar.view.month:Month` },
        [VIEW_TYPES.agenda]: { text: $localize`:@@calendar.view.agenda:Agenda` },
      },
      height: '100%',
      nowIndicator: true,
      scrollTime: '07:00:00',
      dayMaxEventRows: true,
      blockEventClass: 'app-block-event',
      datesSet: (info) => this.onDatesSet(info, settings.timeZone),
      editable: true,
      selectable: true,
      selectMirror: true,
      eventClick: (info) => void this.onEventClick(info),
      select: (info) => this.onSelect(info),
      eventDrop: (info) => void this.onEventChange(info),
      eventResize: (info) => void this.onEventChange(info),
    };
  });

  /** The occurrences of the visible calendars, as FullCalendar events. */
  protected readonly events = computed(() => {
    const visible = this.calendars.visibleIds();
    const byId = this.calendars.byId();
    const events = this.occurrences
      .items()
      .filter((occurrence) => visible.has(occurrence.calendarId))
      .map((occurrence) => toEventInput(occurrence, byId.get(occurrence.calendarId)));
    const timeZone = this.settings()?.timeZone;
    if (!timeZone || !this.overlayApplies()) return events;
    return [...events, ...this.overlay.entries(timeZone)];
  });

  /** The timed occurrences of the visible calendars (for the hidden-entry chips). */
  private readonly timedEntries = computed((): TimedEntry[] => {
    const visible = this.calendars.visibleIds();
    return this.occurrences
      .items()
      .filter((o) => visible.has(o.calendarId) && !o.allDay && o.start)
      .map((o) => ({ start: o.start!, end: o.end }));
  });

  /** The chips of the day columns that have entries scrolled out of view (day and week view). */
  protected readonly chips = signal<HiddenChip[]>([]);
  protected readonly chipHeight = CHIP_HEIGHT;
  private chipFrame?: number;
  private readonly onScrollOrResize = () => this.scheduleChips();
  private readonly resizeObserver =
    typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(this.onScrollOrResize);

  constructor() {
    const host = this.host.nativeElement;
    // Scroll events don't bubble: listen in the capture phase for the time grid's scroller.
    host.addEventListener('scroll', this.onScrollOrResize, true);
    window.addEventListener('resize', this.onScrollOrResize);
    this.resizeObserver?.observe(host);
    effect(() => {
      this.timedEntries();
      this.viewType();
      this.settings();
      // After FullCalendar has rendered the changed events.
      untracked(() => setTimeout(() => this.scheduleChips()));
    });
    void this.calendars.load();
    if (!this.settings()) this.loadSettings();
    effect(() => {
      const range = this.range();
      const ids = this.calendarIds();
      if (!range || !this.calendars.loaded()) return;
      untracked(() => void this.occurrences.load(range, ids ? ids.split(',') : []));
    });
    if (this.initial.event) {
      const id = this.initial.event;
      const opened = effect(() => {
        if (!this.options() || !this.calendars.loaded() || !this.calendar()) return;
        opened.destroy();
        untracked(() => void this.openLinked(id));
      });
    }
  }

  ngOnDestroy(): void {
    const host = this.host.nativeElement;
    host.removeEventListener('scroll', this.onScrollOrResize, true);
    window.removeEventListener('resize', this.onScrollOrResize);
    this.resizeObserver?.disconnect();
    if (this.chipFrame !== undefined) cancelAnimationFrame(this.chipFrame);
  }

  /** Scrolls the time grid so the nearest entry hidden in the chip's direction is in view. */
  protected showHidden(chip: HiddenChip): void {
    const grid = timeGridOf(this.host.nativeElement);
    grid?.scroller.scrollTo({ top: chip.target, behavior: 'smooth' });
  }

  protected chipLabel(chip: HiddenChip): string {
    return chip.direction === 'above'
      ? $localize`:@@calendar.hidden.aboveLabel:${chip.count}:count: events earlier, show`
      : $localize`:@@calendar.hidden.belowLabel:${chip.count}:count: events later, show`;
  }

  protected chipText(chip: HiddenChip): string {
    return chip.direction === 'above'
      ? $localize`:@@calendar.hidden.above:${chip.count}:count: earlier`
      : $localize`:@@calendar.hidden.below:${chip.count}:count: later`;
  }

  /** Recomputes the chips once per frame. */
  private scheduleChips(): void {
    if (this.chipFrame !== undefined) return;
    this.chipFrame = requestAnimationFrame(() => {
      this.chipFrame = undefined;
      this.updateChips();
    });
  }

  private updateChips(): void {
    const timeZone = this.settings()?.timeZone;
    const root = this.host.nativeElement;
    const container = root.querySelector<HTMLElement>('.calendar-main');
    const grid = timeZone && this.overlayApplies() ? timeGridOf(root) : undefined;
    if (!timeZone || !container || !grid) {
      if (this.chips().length) this.chips.set([]);
      return;
    }
    this.chips.set(
      hiddenChips(container, grid.scroller, grid.columns, this.timedEntries(), timeZone),
    );
  }

  protected setOverlay(enabled: boolean): void {
    this.overlay.setEnabled(enabled);
  }

  protected retryOverlay(): void {
    void this.overlay.refresh();
  }

  protected loadSettings(): void {
    this.settingsFailed.set(false);
    this.settingsService.load().catch(() => this.settingsFailed.set(true));
  }

  protected color(calendar: CalendarResponse): string {
    return calendarColor(calendar);
  }

  protected toggle(calendar: CalendarResponse, visible: boolean): void {
    this.calendars.setVisible(calendar, visible).catch(() => {
      this.notifications.error(
        $localize`:@@calendar.toggleFailed:The calendar "${calendar.name}:name:" could not be changed.`,
      );
    });
  }

  protected retryEvents(): void {
    if (!this.calendars.loaded()) void this.calendars.load();
    else void this.occurrences.refresh();
  }

  /** Opens the dialog for a new event in the next full hour (on the clocks of the settings zone). */
  protected newEvent(): void {
    const timeZone = this.settings()?.timeZone;
    if (!timeZone) return;
    const start = nextFullHour(new Date(), timeZone);
    void this.create({ allDay: false, start, end: addHours(start, 1) });
  }

  private onSelect(info: DateSelectInfo): void {
    info.view.calendar.unselect();
    void this.create(timesOf(info));
  }

  private async create(draft: MovedTimes): Promise<void> {
    const context = this.editorContext();
    if (context && (await this.editor.create(draft, context))) void this.occurrences.refresh();
  }

  private async onEventClick(info: EventClickInfo): Promise<void> {
    info.jsEvent.preventDefault();
    const mark = taskMarkOf(info.event.extendedProps);
    if (mark) {
      void this.router.navigate(['/tasks', mark.task.id]);
      return;
    }
    const occurrence = occurrenceOf(info.event.extendedProps);
    const context = this.editorContext();
    if (!occurrence || !context) return;
    if (await this.editor.open(occurrence, context)) void this.occurrences.refresh();
  }

  /** Saves a moved or resized occurrence; puts it back when nothing was saved. */
  private async onEventChange(info: EventChangeInfo): Promise<void> {
    const occurrence = occurrenceOf(info.event.extendedProps);
    if (!occurrence) {
      info.revert();
      return;
    }
    const saved = await this.editor.move(occurrence, timesOf(info.event), () => {
      void this.occurrences.refresh();
    });
    if (!saved) info.revert();
    // A series change moves other occurrences too: show what the server has now.
    void this.occurrences.refresh();
  }

  private editorContext(): EditorContext | undefined {
    const settings = this.settings();
    if (!settings) return undefined;
    return { calendars: this.calendars.calendars(), timeZone: settings.timeZone };
  }

  /** Shows the date of the linked event `id` and opens it (once; the URL forgets it). */
  private async openLinked(id: string): Promise<void> {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { event: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
    const context = this.editorContext();
    if (!context) return;
    let occurrence: OccurrenceResponse;
    try {
      occurrence = occurrenceOfEvent(await this.eventService.get(id));
    } catch {
      this.notifications.error(
        $localize`:@@calendar.linkedEventFailed:The linked event could not be opened. It may have been deleted.`,
      );
      return;
    }
    const date = occurrence.allDay
      ? occurrence.startDate
      : occurrence.start && dateIn(new Date(occurrence.start), context.timeZone);
    if (date) this.calendar()?.getApi().gotoDate(date);
    if (await this.editor.open(occurrence, context)) void this.occurrences.refresh();
  }

  private onDatesSet(info: DatesSetInfo, timeZone: string): void {
    // After FullCalendar has rendered the view's DOM.
    setTimeout(() => focusableScrollers(this.host.nativeElement));
    this.range.set({ from: info.start.toISOString(), to: info.end.toISOString() });
    this.viewType.set(info.view.type);
    if (OVERLAY_VIEWS.has(info.view.type))
      this.overlay.show(dayRange(info.start, info.end, timeZone));
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {
        view: viewName(info.view.type),
        date: dateIn(info.view.currentStart, timeZone),
      },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
}

/**
 * Makes the scrolling hours of FullCalendar's time grid focusable, so they can be scrolled with the keyboard also
 * when they hold no event (WCAG 2.1.1). The scroller is a generic element right inside the grid's `rowgroup`, which
 * may own rows only: the scroller takes over the `rowgroup` role and its parent becomes presentational. It is made
 * focusable also while it does not overflow, so it stays reachable when a resize makes it scroll later.
 */
function focusableScrollers(root: HTMLElement): void {
  for (const el of Array.from(
    root.querySelectorAll<HTMLElement>('full-calendar [style*="overflow"]'),
  )) {
    const parent = el.parentElement;
    if (
      el.hasAttribute('tabindex') ||
      parent?.getAttribute('role') !== 'rowgroup' ||
      !['auto', 'scroll'].includes(getComputedStyle(el).overflowY)
    )
      continue;
    parent.setAttribute('role', 'none');
    el.setAttribute('role', 'rowgroup');
    el.tabIndex = 0;
  }
}

/** The views with the task overlay. */
const OVERLAY_VIEWS: ReadonlySet<string> = new Set([VIEW_TYPES.day, VIEW_TYPES.week]);

/** The days of the range [start, end) FullCalendar shows, in `timeZone`. */
export function dayRange(start: Date, end: Date, timeZone: string): DayRange {
  return { from: dateIn(start, timeZone), to: dateIn(new Date(end.getTime() - 1), timeZone) };
}

/** The occurrence a FullCalendar event shows (see `toEventInput`). */
function occurrenceOf(props: Record<string, unknown>): OccurrenceResponse | undefined {
  return props['occurrence'] as OccurrenceResponse | undefined;
}
