import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
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
import { EditorContext, EventEditor, MovedTimes, timesOf } from '../edit/event-editor';
import { addHours, nextFullHour } from '../edit/event-times';
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
import { OccurrenceRange, OccurrenceStore } from './occurrence-store';

/**
 * The calendar (SPEC.md §6.3): day, week (default), month and agenda views of the event
 * occurrences, in the time zone and with the week start of the settings. The side panel shows or
 * hides each calendar. View and date are in the URL (`?view=week&date=2026-03-23`).
 *
 * Editing: selecting a time range creates an event, clicking one opens it, dragging moves it and
 * dragging its end resizes it (repeating events ask which occurrences the change is for).
 */
@Component({
  selector: 'app-calendar-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // FullCalendar renders its own DOM: its theme and our overrides must reach it unscoped.
  encapsulation: ViewEncapsulation.None,
  imports: [FullCalendarModule, MatButtonModule],
  providers: [OccurrenceStore],
  templateUrl: './calendar-view.html',
  styleUrl: './calendar-view.scss',
})
export default class CalendarView {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly settingsService = inject(SettingsService);
  private readonly notifications = inject(NotificationService);
  protected readonly calendars = inject(CalendarStore);
  protected readonly occurrences = inject(OccurrenceStore);
  private readonly editor = inject(EventEditor);

  /** View and date of the URL when the page opened (the calendar keeps them up to date after). */
  private readonly initial = (() => {
    const params = this.route.snapshot.queryParamMap;
    return { view: parseView(params.get('view')), date: parseDate(params.get('date')) };
  })();

  protected readonly settingsFailed = signal(false);
  /** The range the calendar shows, set by FullCalendar. */
  private readonly range = signal<OccurrenceRange | undefined>(undefined);

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
        today: { text: $localize`:@@calendar.today:Today` },
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
    return this.occurrences
      .items()
      .filter((occurrence) => visible.has(occurrence.calendarId))
      .map((occurrence) => toEventInput(occurrence, byId.get(occurrence.calendarId)));
  });

  constructor() {
    void this.calendars.load();
    if (!this.settings()) this.loadSettings();
    effect(() => {
      const range = this.range();
      const ids = this.calendarIds();
      if (!range || !this.calendars.loaded()) return;
      untracked(() => void this.occurrences.load(range, ids ? ids.split(',') : []));
    });
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

  private onDatesSet(info: DatesSetInfo, timeZone: string): void {
    this.range.set({ from: info.start.toISOString(), to: info.end.toISOString() });
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

/** The occurrence a FullCalendar event shows (see `toEventInput`). */
function occurrenceOf(props: Record<string, unknown>): OccurrenceResponse | undefined {
  return props['occurrence'] as OccurrenceResponse | undefined;
}
