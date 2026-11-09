import { inject, Injectable } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoteResponse } from '../../../api/generated';
import { ApiError, NotificationService } from '../../../core/http';
import { NotesService } from './notes.service';

/** How long the undo snackbar after a delete stays (ms). */
export const NOTE_UNDO_DURATION_MS = 8000;

/**
 * Moving a note to the Trash with an "Undo" snackbar, shared by the overview and the editor.
 * Errors of the delete itself are thrown to the caller, which shows them next to the note.
 */
@Injectable({ providedIn: 'root' })
export class NoteTrash {
  private readonly service = inject(NotesService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly notifications = inject(NotificationService);

  /** Moves the note to the Trash; throws when that fails. */
  remove(note: Pick<NoteResponse, 'id' | 'version'>): Promise<void> {
    return this.service.remove(note);
  }

  /**
   * Shows "Undo" after a delete. Resolves true once undo restored the note, false when the snackbar
   * was dismissed or the restore failed (then the error is toasted).
   */
  async offerUndo(note: Pick<NoteResponse, 'id' | 'displayTitle'>): Promise<boolean> {
    const title = note.displayTitle;
    const ref = this.snackBar.open(
      $localize`:@@notes.trashed:Moved “${title}:title:” to the Trash`,
      $localize`:@@notes.undo:Undo`,
      { duration: NOTE_UNDO_DURATION_MS },
    );
    const undone = await new Promise<boolean>((resolve) => {
      const action = ref.onAction().subscribe(() => resolve(true));
      ref.afterDismissed().subscribe(() => {
        action.unsubscribe();
        resolve(false);
      });
    });
    if (!undone) return false;
    try {
      await this.service.restore(note);
      return true;
    } catch (error) {
      const reason =
        error instanceof ApiError
          ? error.userMessage
          : $localize`:@@notes.error:Something went wrong.`;
      this.notifications.error(
        $localize`:@@notes.restoreFailed:“${title}:title:” could not be restored: ${reason}:error:`,
      );
      return false;
    }
  }
}
