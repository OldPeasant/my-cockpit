import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { firstValueFrom } from 'rxjs';
import { ConvertToEventRequest, EventResponse } from '../../../api/generated';
import {
  addDays,
  addHours,
  instantOf,
  nextFullHour,
  wallClockOf,
} from '../../calendar/edit/event-times';
import { CalendarStore } from '../../calendar/views/calendar-store';
import { SettingsService } from '../../settings/settings.service';
import { localDate } from '../data';

export interface ToCalendarData {
  /** The title of the task, which becomes the event's title. */
  readonly title: string;
  /** The task has unsaved changes, which the event does not get. */
  readonly unsaved?: boolean;
}

/** The time fields of the dialog: wall-clock values in `timeZone`; all-day end dates inclusive. */
export interface ToCalendarForm {
  readonly calendarId: string;
  readonly allDay: boolean;
  readonly startDate: string;
  readonly startTime: string;
  readonly endDate: string;
  readonly endTime: string;
  readonly timeZone: string;
}

/** Whether the end of `form` is after its start (all-day: not before). */
export function endsAfterStart(form: ToCalendarForm): boolean {
  if (form.allDay) return form.endDate >= form.startDate;
  return `${form.endDate}T${form.endTime}` > `${form.startDate}T${form.startTime}`;
}

/** The request converting a task into the event of `form`. */
export function convertRequest(form: ToCalendarForm): ConvertToEventRequest {
  if (form.allDay) {
    return {
      calendarId: form.calendarId,
      allDay: true,
      startDate: form.startDate,
      endDate: addDays(form.endDate, 1),
    };
  }
  return {
    calendarId: form.calendarId,
    allDay: false,
    start: instantOf(form.startDate, form.startTime, form.timeZone),
    end: instantOf(form.endDate, form.endTime, form.timeZone),
    timeZone: form.timeZone,
  };
}

/** The zone of the browser, used while the settings can't be loaded. */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

/** The day (`yyyy-MM-dd`) `event` starts on, on its own clocks: the calendar opens there. */
export function eventStartDay(event: EventResponse): string {
  if (event.allDay || !event.start) return event.startDate ?? localDate();
  return wallClockOf(event.start, event.timeZone ?? browserTimeZone()).date;
}

/**
 * Asks where and when a task goes on the calendar (SPEC.md §5.3 "To calendar"): a writable
 * calendar (the default one first) and the times, timed in the zone of the settings or all-day.
 * It starts at the next full hour for one hour. Closes with the request for
 * `POST /tasks/{id}/convert-to-event`, or `undefined` when cancelled.
 */
@Component({
  selector: 'app-to-calendar-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
  ],
  templateUrl: './to-calendar-dialog.html',
  styleUrl: './to-calendar-dialog.scss',
})
export class ToCalendarDialog {
  protected readonly data = inject<ToCalendarData>(MAT_DIALOG_DATA);
  private readonly ref =
    inject<MatDialogRef<ToCalendarDialog, ConvertToEventRequest | undefined>>(MatDialogRef);
  private readonly settings = inject(SettingsService);
  protected readonly calendars = inject(CalendarStore);

  protected readonly form = new FormGroup({
    calendarId: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    allDay: new FormControl(false, { nonNullable: true }),
    startDate: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    startTime: new FormControl('', { nonNullable: true }),
    endDate: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    endTime: new FormControl('', { nonNullable: true }),
  });
  protected readonly allDay = signal(false);
  protected readonly invalidTimes = signal(false);
  /** The zone the times are in: the settings', the browser's until they are loaded. */
  protected readonly timeZone = computed(
    () => this.settings.settings()?.timeZone ?? browserTimeZone(),
  );
  /** The calendars an event can be created in. */
  protected readonly targets = computed(() =>
    this.calendars.calendars().filter((calendar) => !calendar.readOnly),
  );
  /** Whether the times were set by the user (then a late settings answer keeps them). */
  private touchedTimes = false;

  constructor() {
    this.form.controls.allDay.valueChanges.subscribe((value) => {
      this.allDay.set(value);
      // The default times may span midnight (23:00-00:00): all-day, they mean the start day.
      if (!this.touchedTimes) this.resetTimes();
    });
    this.form.valueChanges.subscribe(() => this.invalidTimes.set(false));
    this.resetTimes();
    this.pickCalendar();
    void this.prepare();
  }

  protected timesChanged(): void {
    this.touchedTimes = true;
  }

  protected retryCalendars(): void {
    void this.calendars.load().then(() => this.pickCalendar());
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value: ToCalendarForm = { ...this.form.getRawValue(), timeZone: this.timeZone() };
    if (!value.allDay && (!value.startTime || !value.endTime)) {
      this.form.markAllAsTouched();
      return;
    }
    if (!endsAfterStart(value)) {
      this.invalidTimes.set(true);
      return;
    }
    this.ref.close(convertRequest(value));
  }

  /** Loads what the dialog needs: the settings (zone) and the calendars, if not loaded yet. */
  private async prepare(): Promise<void> {
    const loads: Promise<unknown>[] = [];
    if (!this.settings.settings()) {
      loads.push(
        this.settings.load().then(
          () => {
            // The default times were in the browser's zone: show them in the settings' zone.
            if (!this.touchedTimes) this.resetTimes();
          },
          () => undefined,
        ),
      );
    }
    if (!this.calendars.loaded()) loads.push(this.calendars.load());
    await Promise.all(loads);
    this.pickCalendar();
  }

  /** The next full hour for one hour, in {@link timeZone}; all-day, the day of that hour. */
  private resetTimes(): void {
    const zone = this.timeZone();
    const start = nextFullHour(new Date(), zone);
    const from = wallClockOf(start, zone);
    const to = wallClockOf(addHours(start, 1), zone);
    const endDate = this.form.controls.allDay.value ? from.date : to.date;
    this.form.patchValue(
      { startDate: from.date, startTime: from.time, endDate, endTime: to.time },
      { emitEvent: false },
    );
  }

  /** Chooses the default calendar (or the first writable one) unless one is chosen. */
  private pickCalendar(): void {
    const control = this.form.controls.calendarId;
    const targets = this.targets();
    if (targets.some((calendar) => calendar.id === control.value)) return;
    const target = targets.find((calendar) => calendar.isDefault) ?? targets[0];
    control.setValue(target?.id ?? '');
  }
}

/** Opens the dialog; resolves with the request, or `undefined` when cancelled. */
export function askForCalendar(
  dialog: MatDialog,
  data: ToCalendarData,
): Promise<ConvertToEventRequest | undefined> {
  const ref = dialog.open<ToCalendarDialog, ToCalendarData, ConvertToEventRequest | undefined>(
    ToCalendarDialog,
    { data, autoFocus: 'first-tabbable', maxWidth: 'calc(100vw - 2rem)' },
  );
  return firstValueFrom(ref.afterClosed());
}
