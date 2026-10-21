import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MailFolderResponse } from '../../../api/generated';
import { folderTree } from './mail-folders-store';
import { MailMoveDialog, MailMoveDialogData } from './mail-move-dialog';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

function folder(
  id: string,
  path: string,
  extra: Partial<MailFolderResponse> = {},
): MailFolderResponse {
  return {
    id,
    accountId: 'acc-1',
    path,
    name: path.split('/').at(-1)!,
    delimiter: '/',
    roleOverridden: false,
    selectable: true,
    version: 0,
    ...extra,
  };
}

const FOLDERS = folderTree([
  folder('arch', '[Gmail]/All Mail', { role: 'ARCHIVE' }),
  folder('proj', 'Projects'),
  folder('alpha', 'Projects/Alpha'),
  folder('recv', 'Receipts'),
]);

describe('MailMoveDialog', () => {
  let fixture: ComponentFixture<MailMoveDialog>;
  let ref: { close: ReturnType<typeof vi.fn> };

  const el = () => fixture.nativeElement as HTMLElement;
  const input = () => el().querySelector<HTMLInputElement>('input.filter')!;
  const choices = () =>
    [...el().querySelectorAll<HTMLButtonElement>('button.folder')].map((b) =>
      b.textContent?.trim(),
    );

  async function open(data: MailMoveDialogData = { folders: FOLDERS }) {
    ref = { close: vi.fn() };
    TestBed.configureTestingModule({
      imports: [MailMoveDialog],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref },
      ],
    });
    fixture = TestBed.createComponent(MailMoveDialog);
    fixture.detectChanges();
    await tick();
  }

  function type(value: string) {
    input().value = value;
    input().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function enter(): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true });
    input().dispatchEvent(event);
    return event;
  }

  it('lists every folder by its label, indented by depth', async () => {
    await open();
    expect(choices()).toEqual(['Archive', 'Projects', 'Alpha', 'Receipts']);
    const depths = [...el().querySelectorAll<HTMLElement>('ul.folders li')].map((li) =>
      li.style.getPropertyValue('--depth'),
    );
    expect(depths).toEqual(['0', '0', '1', '0']);
  });

  it('closes with the clicked folder', async () => {
    await open();
    [...el().querySelectorAll<HTMLButtonElement>('button.folder')][2].click();
    expect(ref.close).toHaveBeenCalledWith('alpha');
  });

  it('filters case-insensitively by label or path and shows full paths, flat', async () => {
    await open();
    type('  PROJ ');
    expect(choices()).toEqual(['Projects', 'Projects/Alpha']);
    const depths = [...el().querySelectorAll<HTMLElement>('ul.folders li')].map((li) =>
      li.style.getPropertyValue('--depth'),
    );
    expect(depths).toEqual(['0', '0']);
    // The role label matches although neither name nor path contains it.
    type('archive');
    expect(choices()).toEqual(['[Gmail]/All Mail']);
    type('gmail');
    expect(choices()).toEqual(['[Gmail]/All Mail']);
  });

  it('picks the first match on Enter', async () => {
    await open();
    type('alp');
    const event = enter();
    expect(event.defaultPrevented).toBe(true);
    expect(ref.close).toHaveBeenCalledExactlyOnceWith('alpha');
  });

  it('picks the first folder on Enter without a filter', async () => {
    await open();
    enter();
    expect(ref.close).toHaveBeenCalledWith('arch');
  });

  it('says so when nothing matches, and Enter then does nothing', async () => {
    await open();
    type('no such folder');
    expect(choices()).toEqual([]);
    expect(el().querySelector('.note')?.textContent?.trim()).toBe('No matching folder.');
    const event = enter();
    expect(event.defaultPrevented).toBe(true);
    expect(ref.close).not.toHaveBeenCalled();
  });

  it('says so when the account has no other folder', async () => {
    await open({ folders: [] });
    expect(el().querySelector('.note')?.textContent?.trim()).toBe('No matching folder.');
  });

  it('closes without a result on cancel', async () => {
    await open();
    const cancel = [...el().querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === 'Cancel',
    )!;
    cancel.click();
    expect(ref.close).toHaveBeenCalledTimes(1);
    expect(ref.close.mock.calls[0][0]).toBeUndefined();
  });
});
