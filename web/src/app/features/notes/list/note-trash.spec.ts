import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { ApiError, NotificationService } from '../../../core/http';
import { NOTE_UNDO_DURATION_MS, NoteTrash } from './note-trash';
import { NotesService } from './notes.service';

const NOTE = {
  id: '0192f0c4-0000-7000-8000-0000000000b1',
  displayTitle: 'Fictional shopping list',
  version: 4,
};

describe('NoteTrash', () => {
  let trash: NoteTrash;
  let service: { remove: ReturnType<typeof vi.fn>; restore: ReturnType<typeof vi.fn> };
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };
  let snackBar: { open: ReturnType<typeof vi.fn> };
  let action: Subject<void>;
  let dismissed: Subject<void>;

  beforeEach(() => {
    action = new Subject<void>();
    dismissed = new Subject<void>();
    service = { remove: vi.fn().mockResolvedValue(undefined), restore: vi.fn() };
    notifications = { error: vi.fn(), info: vi.fn() };
    snackBar = {
      open: vi.fn(() => ({
        onAction: () => action.asObservable(),
        afterDismissed: () => dismissed.asObservable(),
      })),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: NotesService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: NotificationService, useValue: notifications },
      ],
    });
    trash = TestBed.inject(NoteTrash);
  });

  /** The snackbar closes after its action, as MatSnackBar does. */
  function pressUndo(): void {
    action.next();
    dismissed.next();
  }

  it('moves the note to the Trash through the service', async () => {
    await trash.remove(NOTE);
    expect(service.remove).toHaveBeenCalledWith(NOTE);
  });

  it('passes a failed delete on to the caller', async () => {
    const error = new Error('offline');
    service.remove.mockRejectedValue(error);
    await expect(trash.remove(NOTE)).rejects.toBe(error);
  });

  it('offers Undo for a while, naming the note', () => {
    void trash.offerUndo(NOTE);
    expect(snackBar.open).toHaveBeenCalledWith(
      'Moved “Fictional shopping list” to the Trash',
      'Undo',
      { duration: NOTE_UNDO_DURATION_MS },
    );
  });

  it('restores the note when Undo is pressed and resolves true', async () => {
    service.restore.mockResolvedValue(undefined);
    const undone = trash.offerUndo(NOTE);
    pressUndo();
    await expect(undone).resolves.toBe(true);
    expect(service.restore).toHaveBeenCalledTimes(1);
    expect(service.restore).toHaveBeenCalledWith(NOTE);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('resolves false without restoring when the snackbar is dismissed', async () => {
    const undone = trash.offerUndo(NOTE);
    dismissed.next();
    await expect(undone).resolves.toBe(false);
    expect(service.restore).not.toHaveBeenCalled();
    // A late action after the dismissal changes nothing.
    action.next();
    expect(service.restore).not.toHaveBeenCalled();
  });

  it('toasts the problem detail and resolves false when the restore fails', async () => {
    const error = new ApiError(404, 'Not Found', 'Already purged.');
    service.restore.mockRejectedValue(error);
    const undone = trash.offerUndo(NOTE);
    pressUndo();
    await expect(undone).resolves.toBe(false);
    expect(notifications.error).toHaveBeenCalledTimes(1);
    expect(notifications.error).toHaveBeenCalledWith(
      `“Fictional shopping list” could not be restored: ${error.userMessage}`,
    );
  });

  it('toasts a generic message when the restore fails unexpectedly', async () => {
    service.restore.mockRejectedValue(new Error('boom'));
    const undone = trash.offerUndo(NOTE);
    pressUndo();
    await expect(undone).resolves.toBe(false);
    expect(notifications.error).toHaveBeenCalledWith(
      '“Fictional shopping list” could not be restored: Something went wrong.',
    );
  });
});
