import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { Api, restoreTrashItem } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { TaskUndo, UNDO_DURATION_MS } from './task-undo';

/** A MatSnackBar double: each `open` gives a ref whose action and dismissal the test fires. */
class FakeSnackBar {
  readonly opened: {
    message: string;
    action: string;
    config: unknown;
    act: () => void;
    dismiss: () => void;
  }[] = [];

  open(message: string, action: string, config: unknown) {
    const onAction = new Subject<void>();
    const afterDismissed = new Subject<void>();
    this.opened.push({
      message,
      action,
      config,
      // Like MatSnackBar: the action fires, then the bar is dismissed.
      act: () => {
        onAction.next();
        onAction.complete();
        afterDismissed.next();
        afterDismissed.complete();
      },
      dismiss: () => {
        afterDismissed.next();
        afterDismissed.complete();
      },
    });
    return { onAction: () => onAction, afterDismissed: () => afterDismissed };
  }
}

describe('TaskUndo', () => {
  let snackBar: FakeSnackBar;
  let invoke: ReturnType<typeof vi.fn>;
  let undo: TaskUndo;

  beforeEach(() => {
    snackBar = new FakeSnackBar();
    invoke = vi.fn().mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        { provide: MatSnackBar, useValue: snackBar },
        { provide: Api, useValue: { invoke } },
      ],
    });
    undo = TestBed.inject(TaskUndo);
  });

  it('offers to reopen a completed task for a few seconds', () => {
    void undo.completed({ title: 'Water the fictitious ferns' }, () => Promise.resolve());

    expect(snackBar.opened).toHaveLength(1);
    expect(snackBar.opened[0].message).toBe('Completed “Water the fictitious ferns”');
    expect(snackBar.opened[0].action).toBe('Undo');
    expect(snackBar.opened[0].config).toEqual({ duration: UNDO_DURATION_MS });
    expect(UNDO_DURATION_MS).toBe(6000);
  });

  it('reopens the task on Undo and resolves true', async () => {
    const reopen = vi.fn().mockResolvedValue({ id: 't1' });
    const undone = undo.completed({ title: 'Water the ferns' }, reopen);

    snackBar.opened[0].act();

    await expect(undone).resolves.toBe(true);
    expect(reopen).toHaveBeenCalledTimes(1);
  });

  it('does nothing when the snackbar goes away without Undo and resolves false', async () => {
    const reopen = vi.fn().mockResolvedValue(undefined);
    const undone = undo.completed({ title: 'Water the ferns' }, reopen);

    snackBar.opened[0].dismiss();

    await expect(undone).resolves.toBe(false);
    expect(reopen).not.toHaveBeenCalled();
  });

  it('resolves false when the undo itself fails, without throwing', async () => {
    const reopen = vi.fn().mockRejectedValue(new ApiError(412, 'Precondition Failed'));
    const undone = undo.completed({ title: 'Water the ferns' }, reopen);

    snackBar.opened[0].act();

    await expect(undone).resolves.toBe(false);
    expect(reopen).toHaveBeenCalledTimes(1);
  });

  it('offers to restore a deleted task from the Trash', async () => {
    const undone = undo.deleted({ id: 'task-7', title: 'Old fictitious errand' });

    expect(snackBar.opened[0].message).toBe('Moved “Old fictitious errand” to the Trash');
    expect(snackBar.opened[0].action).toBe('Undo');
    expect(invoke).not.toHaveBeenCalled();

    snackBar.opened[0].act();

    await expect(undone).resolves.toBe(true);
    expect(invoke).toHaveBeenCalledExactlyOnceWith(restoreTrashItem, {
      type: 'TASK',
      id: 'task-7',
    });
  });

  it('restores nothing when the deletion snackbar is dismissed', async () => {
    const undone = undo.deleted({ id: 'task-7', title: 'Old errand' });

    snackBar.opened[0].dismiss();

    await expect(undone).resolves.toBe(false);
    expect(invoke).not.toHaveBeenCalled();
  });

  it('resolves false when restoring from the Trash fails (the HTTP layer shows it)', async () => {
    invoke.mockRejectedValue(new ApiError(404, 'Not Found'));
    const undone = undo.deleted({ id: 'task-7', title: 'Old errand' });

    snackBar.opened[0].act();

    await expect(undone).resolves.toBe(false);
  });
});

describe('TaskUndo with the real MatSnackBar', () => {
  afterEach(() => TestBed.inject(MatSnackBar).dismiss());

  it('shows the message with an Undo button that runs the undo', async () => {
    const reopen = vi.fn().mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [{ provide: Api, useValue: { invoke: vi.fn() } }],
    });
    const undone = TestBed.inject(TaskUndo).completed(
      { title: 'Sort the fictitious mail' },
      reopen,
    );
    await new Promise((resolve) => setTimeout(resolve));

    const container = document.querySelector('mat-snack-bar-container');
    expect(container?.textContent).toContain('Completed “Sort the fictitious mail”');
    const button = [...(container?.querySelectorAll('button') ?? [])].find((b) =>
      b.textContent?.includes('Undo'),
    );
    expect(button).toBeDefined();
    button!.click();

    await expect(undone).resolves.toBe(true);
    expect(reopen).toHaveBeenCalledTimes(1);
  });
});
