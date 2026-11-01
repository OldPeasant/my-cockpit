import { TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { ApiError } from '../../../core/http';
import {
  folderErrorMessage,
  MailFolderDeleteDialog,
  MailFolderDeleteDialogData,
  MailFolderNameDialog,
  MailFolderNameDialogData,
  MAX_FOLDER_NAME_LENGTH,
} from './mail-folder-dialogs';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

async function settle(): Promise<void> {
  await tick();
  TestBed.tick();
  await tick();
  TestBed.tick();
}

function escape(): void {
  document
    .querySelector('.cdk-overlay-pane')!
    .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true }));
}

describe('folderErrorMessage', () => {
  it.each([
    [0, 'The server cannot be reached. Check the connection and try again.'],
    [400, 'The mail server does not accept this name.'],
    [404, 'The folder was changed or removed elsewhere. The folder list was reloaded; try again.'],
    [412, 'The folder was changed or removed elsewhere. The folder list was reloaded; try again.'],
    [409, 'A folder with this name already exists here.'],
    [502, 'The mail server could not be reached or refused the change.'],
  ])('explains status %s in the UI language', (status, text) => {
    expect(folderErrorMessage(new ApiError(status, 'Server title', 'Server detail'))).toBe(text);
  });

  it("falls back to the server's text for other statuses", () => {
    expect(folderErrorMessage(new ApiError(500, 'Server title', 'Server detail'))).toBe(
      'Server detail',
    );
  });

  it('says something went wrong for errors that are not API errors', () => {
    expect(folderErrorMessage(new Error('boom'))).toBe('Something went wrong.');
  });
});

describe('MailFolderNameDialog', () => {
  let ref: MatDialogRef<MailFolderNameDialog, boolean>;
  let el: HTMLElement;
  let save: ReturnType<typeof vi.fn<(name: string) => Promise<void>>>;

  afterEach(() => TestBed.inject(MatDialog).closeAll());

  async function open(extra: Partial<MailFolderNameDialogData> = {}): Promise<void> {
    save = vi.fn<(name: string) => Promise<void>>().mockResolvedValue(undefined);
    ref = TestBed.inject(MatDialog).open<MailFolderNameDialog, MailFolderNameDialogData, boolean>(
      MailFolderNameDialog,
      {
        data: { title: 'New folder', confirm: 'Create', delimiter: '/', save, ...extra },
      },
    );
    await settle();
    el = document.querySelector('app-mail-folder-name-dialog') as HTMLElement;
  }

  const input = () => el.querySelector('input') as HTMLInputElement;
  const submitButton = () => el.querySelector('button[type="submit"]') as HTMLButtonElement;
  const alert = () => el.querySelector('[role="alert"]')?.textContent?.trim();

  async function submit(value?: string): Promise<void> {
    if (value !== undefined) {
      input().value = value;
      input().dispatchEvent(new Event('input'));
    }
    submitButton().click();
    await settle();
  }

  it('shows the title, a labelled name field with focus, and the confirm label', async () => {
    await open();
    expect(el.querySelector('h2')?.textContent?.trim()).toBe('New folder');
    expect(el.querySelector('mat-label')?.textContent?.trim()).toBe('Folder name');
    expect(submitButton().textContent?.trim()).toBe('Create');
    expect(input().maxLength).toBe(MAX_FOLDER_NAME_LENGTH);
    // jsdom has no layout, so the focus trap finds nothing tabbable: check the marker it uses.
    expect(input().hasAttribute('cdkfocusinitial')).toBe(true);
  });

  it('saves the trimmed name and closes with true', async () => {
    await open();
    const closed = firstValueFrom(ref.afterClosed());
    await submit('  Receipts ');
    expect(save).toHaveBeenCalledWith('Receipts');
    expect(await closed).toBe(true);
  });

  it('saves on Enter in the field', async () => {
    await open();
    input().value = 'Receipts';
    input().dispatchEvent(new Event('input'));
    el.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(save).toHaveBeenCalledWith('Receipts');
  });

  it('requires a name that is not blank', async () => {
    await open();
    await submit('   ');
    expect(save).not.toHaveBeenCalled();
    expect(el.querySelector('mat-error')?.textContent?.trim()).toBe('Enter a name.');
  });

  it("refuses a name with the server's hierarchy delimiter", async () => {
    await open({ delimiter: '.' });
    await submit('a.b');
    expect(save).not.toHaveBeenCalled();
    expect(el.querySelector('mat-error')?.textContent?.trim()).toBe('A name cannot contain ..');
  });

  it('accepts any name in a flat namespace (no delimiter)', async () => {
    await open({ delimiter: undefined });
    await submit('a/b');
    expect(save).toHaveBeenCalledWith('a/b');
  });

  it('starts with the current name when renaming, and closes without saving it unchanged', async () => {
    await open({ title: 'Rename Alpha', confirm: 'Rename', name: 'Alpha' });
    expect(input().value).toBe('Alpha');
    const closed = firstValueFrom(ref.afterClosed());
    await submit();
    expect(save).not.toHaveBeenCalled();
    expect(await closed).toBe(false);
  });

  it.each([
    [409, 'A folder with this name already exists here.'],
    [400, 'The mail server does not accept this name.'],
    [0, 'The server cannot be reached. Check the connection and try again.'],
    [502, 'The mail server could not be reached or refused the change.'],
  ])('shows a server error (%s) in the dialog, which stays open', async (status, text) => {
    await open();
    save.mockRejectedValue(new ApiError(status, 'Server title'));
    let closed = false;
    ref.afterClosed().subscribe(() => (closed = true));

    await submit('Projects');

    expect(alert()).toBe(text);
    expect(closed).toBe(false);
    expect(submitButton().disabled).toBe(false);

    // A second try that works closes it.
    save.mockResolvedValue(undefined);
    const afterClosed = firstValueFrom(ref.afterClosed());
    await submit('Projects 2');
    expect(await afterClosed).toBe(true);
  });

  it('treats a rename to the same name with surrounding spaces as unchanged', async () => {
    await open({ title: 'Rename Alpha', confirm: 'Rename', name: 'Alpha' });
    const closed = firstValueFrom(ref.afterClosed());
    await submit('  Alpha  ');
    expect(save).not.toHaveBeenCalled();
    expect(await closed).toBe(false);
  });

  it('refuses a name longer than the server accepts', async () => {
    await open();
    await submit('x'.repeat(MAX_FOLDER_NAME_LENGTH + 1));
    expect(save).not.toHaveBeenCalled();
  });

  it('accepts a name of exactly the longest length', async () => {
    await open();
    await submit('x'.repeat(MAX_FOLDER_NAME_LENGTH));
    expect(save).toHaveBeenCalledWith('x'.repeat(MAX_FOLDER_NAME_LENGTH));
  });

  it('shows a generic text for an error that is not an API error, and stays open', async () => {
    await open();
    save.mockRejectedValue(new Error('boom'));
    let closed = false;
    ref.afterClosed().subscribe(() => (closed = true));

    await submit('Projects');

    expect(alert()).toBe('Something went wrong.');
    expect(closed).toBe(false);
    expect(ref.disableClose).toBe(false);
  });

  it('clears the previous server error when trying again', async () => {
    await open();
    save.mockRejectedValueOnce(new ApiError(409, 'Conflict'));
    await submit('Projects');
    expect(alert()).toBe('A folder with this name already exists here.');

    let finish!: () => void;
    save.mockReturnValueOnce(new Promise<void>((resolve) => (finish = resolve)));
    await submit('Projects 2');
    expect(alert()).toBeUndefined();
    finish();
    await settle();
  });

  it('cannot be closed while saving, and is disabled meanwhile', async () => {
    await open();
    let finish!: () => void;
    save.mockReturnValue(new Promise<void>((resolve) => (finish = resolve)));
    input().value = 'Receipts';
    input().dispatchEvent(new Event('input'));
    submitButton().click();
    await settle();

    expect(ref.disableClose).toBe(true);
    expect(submitButton().disabled).toBe(true);
    // A second submit while saving does not save twice.
    el.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(save).toHaveBeenCalledTimes(1);

    finish();
    await settle();
    expect(ref.getState()).not.toBe(0);
  });

  it('closes with Escape without saving', async () => {
    await open();
    const closed = firstValueFrom(ref.afterClosed());
    escape();
    expect(await closed).toBeUndefined();
    expect(save).not.toHaveBeenCalled();
  });

  it('closes with Cancel without saving', async () => {
    await open();
    const closed = firstValueFrom(ref.afterClosed());
    (el.querySelector('button[type="button"]') as HTMLButtonElement).click();
    expect(await closed).toBeFalsy();
    expect(save).not.toHaveBeenCalled();
  });
});

describe('MailFolderDeleteDialog', () => {
  let ref: MatDialogRef<MailFolderDeleteDialog, boolean>;
  let el: HTMLElement;
  let remove: ReturnType<typeof vi.fn<() => Promise<void>>>;

  afterEach(() => TestBed.inject(MatDialog).closeAll());

  async function open(subfolders = 0): Promise<void> {
    remove = vi.fn<() => Promise<void>>().mockResolvedValue(undefined);
    ref = TestBed.inject(MatDialog).open<
      MailFolderDeleteDialog,
      MailFolderDeleteDialogData,
      boolean
    >(MailFolderDeleteDialog, { data: { name: 'Projects', subfolders, remove } });
    await settle();
    el = document.querySelector('app-mail-folder-delete-dialog') as HTMLElement;
  }

  const text = () => el.querySelector('.consequence')?.textContent?.replace(/\s+/g, ' ').trim();
  const confirmButton = () => el.querySelector('button.danger') as HTMLButtonElement;

  it('asks, naming the folder and saying its messages go on the mail server too', async () => {
    await open();
    expect(el.querySelector('h2')?.textContent?.trim()).toBe('Delete “Projects”?');
    expect(text()).toBe(
      'The folder and all its messages are deleted on the mail server too. This cannot be undone.',
    );
    expect(confirmButton().textContent?.trim()).toBe('Delete folder');
    expect(remove).not.toHaveBeenCalled();
  });

  it.each([
    [1, 'The folder, its subfolder and all their messages are deleted on the mail server too.'],
    [3, 'The folder, its 3 subfolders and all their messages are deleted on the mail server too.'],
  ])('mentions %s subfolder(s)', async (count, start) => {
    await open(count);
    expect(text()).toBe(`${start} This cannot be undone.`);
  });

  it('focuses Cancel first, so Enter does not delete by accident', async () => {
    await open();
    expect(el.querySelector('[cdkfocusinitial]')?.textContent?.trim()).toBe('Cancel');
  });

  it('deletes on confirm and closes with true', async () => {
    await open();
    const closed = firstValueFrom(ref.afterClosed());
    confirmButton().click();
    await settle();
    expect(remove).toHaveBeenCalledTimes(1);
    expect(await closed).toBe(true);
  });

  it('shows a server error in the dialog, which stays open', async () => {
    await open();
    remove.mockRejectedValue(new ApiError(502, 'Bad gateway'));
    let closed = false;
    ref.afterClosed().subscribe(() => (closed = true));

    confirmButton().click();
    await settle();

    expect(el.querySelector('[role="alert"]')?.textContent?.trim()).toBe(
      'The mail server could not be reached or refused the change.',
    );
    expect(closed).toBe(false);
    expect(confirmButton().disabled).toBe(false);
  });

  it('cannot be closed while deleting, disables both buttons and deletes only once', async () => {
    await open();
    let finish!: () => void;
    remove.mockReturnValue(new Promise<void>((resolve) => (finish = resolve)));
    const closed = firstValueFrom(ref.afterClosed());

    confirmButton().click();
    await settle();

    expect(ref.disableClose).toBe(true);
    expect(confirmButton().disabled).toBe(true);
    expect((el.querySelector('button[mat-dialog-close]') as HTMLButtonElement).disabled).toBe(true);
    // Escape while the delete runs does not close the dialog.
    escape();
    await settle();
    expect(ref.getState()).toBe(0);
    // A second click while deleting does not delete twice.
    confirmButton().click();
    expect(remove).toHaveBeenCalledTimes(1);

    finish();
    expect(await closed).toBe(true);
  });

  it('clears the previous error when confirming again, and closes once it works', async () => {
    await open();
    remove.mockRejectedValueOnce(new ApiError(0, 'Unknown Error'));
    confirmButton().click();
    await settle();
    expect(el.querySelector('[role="alert"]')?.textContent?.trim()).toBe(
      'The server cannot be reached. Check the connection and try again.',
    );
    expect(ref.disableClose).toBe(false);

    const closed = firstValueFrom(ref.afterClosed());
    confirmButton().click();
    await settle();
    expect(remove).toHaveBeenCalledTimes(2);
    expect(await closed).toBe(true);
  });

  it('closes with Cancel without deleting', async () => {
    await open();
    const closed = firstValueFrom(ref.afterClosed());
    (el.querySelector('button[mat-dialog-close]') as HTMLButtonElement).click();
    expect(await closed).toBeFalsy();
    expect(remove).not.toHaveBeenCalled();
  });

  it('closes with Escape without deleting', async () => {
    await open();
    const closed = firstValueFrom(ref.afterClosed());
    escape();
    expect(await closed).toBeUndefined();
    expect(remove).not.toHaveBeenCalled();
  });
});
