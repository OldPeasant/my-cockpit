import { inject, Injectable } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { CalendarResponse, EventPatchRequest, OccurrenceResponse } from '../../../api/generated';
import { ApiError, NotificationService } from '../../../core/http';
import { EventDialog, EventDialogData, EventDialogResult } from './event-dialog';
import { EditScope, EventEditService, isConflict, isSeries } from './event-edit.service';
import { EventDraft } from './event-form';
import { addDays, addHours } from './event-times';
import { ScopePrompt } from './scope-dialog';

/** New times of an occurrence moved or resized in a view. */
export type MovedTimes = EventDraft;

export interface EditorContext {
  readonly calendars: readonly CalendarResponse[];
  /** The zone of the settings. */
  readonly timeZone: string;
}

/**
 * Event editing of the calendar views (SPEC.md §6.3): opens the event dialog for a new event or an
 * occurrence, and saves an occurrence moved or resized by dragging, asking first which occurrences
 * of a repeating event the move applies to.
 */
@Injectable({ providedIn: 'root' })
export class EventEditor {
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly notifications = inject(NotificationService);
  private readonly events = inject(EventEditService);
  private readonly scopes = inject(ScopePrompt);

  /** Opens the dialog for a new event with `draft`'s times; `true` once one was created. */
  async create(draft: EventDraft, context: EditorContext): Promise<boolean> {
    if (!context.calendars.some((calendar) => !calendar.readOnly)) {
      this.notifications.error(
        $localize`:@@calendar.edit.noWritableCalendar:Add a calendar in the settings to create events.`,
      );
      return false;
    }
    return (await this.openDialog({ mode: 'create', draft, ...context })) === 'saved';
  }

  /** Opens the dialog of `occurrence`; `true` once it was changed or deleted. */
  async open(occurrence: OccurrenceResponse, context: EditorContext): Promise<boolean> {
    return (await this.openDialog({ mode: 'edit', occurrence, ...context })) !== undefined;
  }

  /**
   * Saves `occurrence` at its new `times`. Resolves `false` when nothing was saved (the user
   * cancelled the scope prompt or saving failed): the caller then puts the event back. A conflict
   * (changed elsewhere) is shown with a reload action that calls `reload`.
   */
  async move(
    occurrence: OccurrenceResponse,
    times: MovedTimes,
    reload: () => void,
  ): Promise<boolean> {
    let scope: EditScope | undefined;
    if (isSeries(occurrence)) {
      scope = await this.scopes.ask('change');
      if (!scope) return false;
    }
    try {
      await this.events.update(occurrence, scope, movePatch(occurrence, times));
      return true;
    } catch (error) {
      if (isConflict(error)) {
        this.snackBar
          .open(
            $localize`:@@calendar.edit.movedConflict:"${occurrence.title}:title:" was changed elsewhere. Reload to see the current version, then move it again.`,
            $localize`:@@calendar.edit.reload:Reload`,
            { duration: 15000, politeness: 'assertive' },
          )
          .onAction()
          .subscribe(() => reload());
      } else {
        this.notifications.error(
          error instanceof ApiError
            ? error.userMessage
            : $localize`:@@calendar.edit.moveFailed:The event could not be moved.`,
        );
      }
      return false;
    }
  }

  private openDialog(data: EventDialogData): Promise<EventDialogResult> {
    const ref = this.dialog.open<EventDialog, EventDialogData, EventDialogResult>(EventDialog, {
      data,
      autoFocus: 'dialog',
      maxWidth: 'calc(100vw - 2rem)',
    });
    return firstValueFrom(ref.afterClosed());
  }
}

/** The patch moving `occurrence` to `times` (all-day only sent when it changes). */
export function movePatch(occurrence: OccurrenceResponse, times: MovedTimes): EventPatchRequest {
  const patch: EventPatchRequest = {};
  if (times.allDay !== occurrence.allDay) patch.allDay = times.allDay;
  if (times.allDay) {
    patch.startDate = times.startDate;
    patch.endDate = times.endDate;
  } else {
    patch.start = times.start;
    patch.end = times.end;
  }
  return patch;
}

/** What the editor needs of a FullCalendar event (`EventApi`) or selection after a drag. */
export interface DraggedRange {
  readonly allDay: boolean;
  readonly start: Date | null;
  readonly end: Date | null;
  readonly startStr: string;
  readonly endStr: string;
}

/** The times of a dragged or selected range (no end: one day or one hour). */
export function timesOf(range: DraggedRange): MovedTimes {
  if (range.allDay) {
    const startDate = range.startStr.slice(0, 10);
    const endDate = range.endStr ? range.endStr.slice(0, 10) : addDays(startDate, 1);
    return { allDay: true, startDate, endDate };
  }
  const start = range.start!.toISOString();
  return { allDay: false, start, end: range.end ? range.end.toISOString() : addHours(start, 1) };
}
