import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { MailAccountResponse, MailFolderResponse } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ApiError } from '../../../core/http';
import { MailFolderDeleteDialogData, MailFolderNameDialogData } from './mail-folder-dialogs';
import { folderLabel, MailFolderTree } from './mail-folder-tree';
import { AccountTree, folderTree, MailFoldersStore } from './mail-folders-store';
import { FakeEvents } from './mail-testing';
import { MailService } from './mail.service';

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

const WORK = {
  id: 'acc-1',
  displayName: 'Ann Example',
  emailAddress: 'work@example.test',
} as MailAccountResponse;
const HOME = {
  id: 'acc-2',
  displayName: 'Ann Example',
  emailAddress: 'home@example.test',
} as MailAccountResponse;

describe('folderLabel', () => {
  it.each([
    ['INBOX', 'Inbox'],
    ['DRAFTS', 'Drafts'],
    ['SENT', 'Sent'],
    ['ARCHIVE', 'Archive'],
    ['SPAM', 'Spam'],
    ['TRASH', 'Trash'],
  ] as const)('names the %s role folder %s whatever its server name', (role, label) => {
    expect(folderLabel(folder('x', '[Gmail]/Server name', { role }))).toBe(label);
  });

  it('uses the folder name without a role', () => {
    expect(folderLabel(folder('x', 'Projects/Client A'))).toBe('Client A');
  });
});

describe('MailFolderTree', () => {
  let fixture: ComponentFixture<MailFolderTree>;
  let store: MailFoldersStore;
  let mail: {
    accounts: ReturnType<typeof vi.fn>;
    folders: ReturnType<typeof vi.fn>;
    createFolder: ReturnType<typeof vi.fn>;
    renameFolder: ReturnType<typeof vi.fn>;
    deleteFolder: ReturnType<typeof vi.fn>;
  };
  let opened: { component: unknown; data: unknown }[];

  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    // The background reload after a change never answers: the tests see the local update alone.
    mail = {
      accounts: vi.fn(() => new Promise(() => undefined)),
      folders: vi.fn(() => new Promise(() => undefined)),
      createFolder: vi.fn(),
      renameFolder: vi.fn(),
      deleteFolder: vi.fn(),
    };
    TestBed.configureTestingModule({
      imports: [MailFolderTree],
      providers: [
        provideRouter([]),
        MailFoldersStore,
        { provide: MailService, useValue: mail },
        { provide: ChangeEventsService, useValue: new FakeEvents() },
      ],
    });
    store = TestBed.inject(MailFoldersStore);
    opened = [];
    const dialog = TestBed.inject(MatDialog);
    vi.spyOn(dialog, 'open').mockImplementation(((
      component: unknown,
      config?: { data?: unknown },
    ) => {
      opened.push({ component, data: config?.data });
      return { afterClosed: () => ({ subscribe: () => undefined }) };
    }) as unknown as MatDialog['open']);
    fixture = TestBed.createComponent(MailFolderTree);
  });

  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((node) => (node.innerHTML = ''));
  });

  const standard = () => [
    folder('in', 'INBOX', { role: 'INBOX' }),
    folder('g', '[Gmail]', { selectable: false }),
    folder('gt', '[Gmail]/Bin', { role: 'TRASH' }),
    folder('p', 'Projects'),
    folder('pa', 'Projects/Alpha'),
    folder('pa1', 'Projects/Alpha/Old'),
  ];

  function show(current: string | null, folders = standard()) {
    store.trees.set([
      { account: WORK, folders: folderTree(folders) },
      {
        account: HOME,
        folders: folderTree([folder('h-in', 'INBOX', { role: 'INBOX', accountId: 'acc-2' })]),
      },
    ] as AccountTree[]);
    fixture.componentRef.setInput('current', current);
    fixture.detectChanges();
  }

  const item = (text: string) =>
    [...el().querySelectorAll<HTMLElement>('li.node')].find(
      (li) => li.querySelector('.row')?.textContent?.trim() === text,
    );

  /** Opens a folder's menu and returns the labels of its entries. */
  async function menu(text: string): Promise<HTMLButtonElement[]> {
    const trigger = item(text)?.querySelector<HTMLButtonElement>('button.folder-menu');
    if (!trigger) return [];
    trigger.click();
    fixture.detectChanges();
    await fixture.whenStable();
    return [...document.querySelectorAll<HTMLButtonElement>('.mat-mdc-menu-panel button')];
  }
  const labels = (buttons: HTMLButtonElement[]) => buttons.map((b) => b.textContent?.trim());

  const nameData = () => opened.at(-1)!.data as MailFolderNameDialogData;
  const deleteData = () => opened.at(-1)!.data as MailFolderDeleteDialogData;
  const rows = () =>
    [...el().querySelectorAll<HTMLElement>('section.account')][0].querySelectorAll('li .row');
  const shown = () => [...rows()].map((row) => row.textContent?.trim());

  it('shows only the unified Inbox, marked current, before the folders load', () => {
    fixture.detectChanges();
    const unified = el().querySelector<HTMLAnchorElement>('a.unified')!;
    expect(unified.getAttribute('href')).toBe('/mail');
    expect(unified.getAttribute('aria-current')).toBe('page');
    expect(el().querySelectorAll('section.account')).toHaveLength(0);
  });

  it('lists every account with its folders as links and groups as plain text', () => {
    show(null);
    const sections = [...el().querySelectorAll('section.account')];
    // Both accounts share the sender name; the email address tells them apart.
    expect(sections.map((s) => s.getAttribute('aria-label'))).toEqual([
      'work@example.test',
      'home@example.test',
    ]);
    expect(sections.map((s) => s.querySelector('h2')?.textContent?.trim())).toEqual([
      'work@example.test',
      'home@example.test',
    ]);
    expect(sections[0].querySelector('h2')?.getAttribute('title')).toBe('work@example.test');
    const links = [...sections[0].querySelectorAll<HTMLAnchorElement>('a.row')];
    expect(links.map((a) => [a.textContent?.trim(), a.getAttribute('href')])).toEqual([
      ['Inbox', '/mail/folders/in'],
      ['Trash', '/mail/folders/gt'],
      ['Projects', '/mail/folders/p'],
      ['Alpha', '/mail/folders/pa'],
      ['Old', '/mail/folders/pa1'],
    ]);
    const group = sections[0].querySelector('span.group');
    expect(group?.textContent?.trim()).toBe('[Gmail]');
    const items = [...sections[0].querySelectorAll<HTMLElement>('li')];
    expect(items.map((li) => li.style.getPropertyValue('--depth'))).toEqual([
      '0',
      '0',
      '1',
      '0',
      '1',
      '2',
    ]);
  });

  it('marks the shown folder as the current page, and not the unified Inbox', () => {
    show('gt');
    const current = [...el().querySelectorAll('[aria-current="page"]')];
    expect(current.map((a) => a.textContent?.trim())).toEqual(['Trash']);
    expect(el().querySelector('a.unified')?.classList).not.toContain('current');
  });

  it('shows the load error as an alert', () => {
    store.error.set('The folders could not be loaded.');
    fixture.detectChanges();
    expect(el().querySelector('[role="alert"]')?.textContent?.trim()).toBe(
      'The folders could not be loaded.',
    );
  });

  describe('actions', () => {
    it('gives each account a labelled "New folder" button', () => {
      show(null);
      const buttons = [...el().querySelectorAll<HTMLButtonElement>('button.add')];
      expect(buttons.map((b) => b.getAttribute('aria-label'))).toEqual([
        'New folder in work@example.test',
        'New folder in home@example.test',
      ]);
      expect(buttons.every((b) => b.type === 'button')).toBe(true);
    });

    it('offers New subfolder, Rename and Delete for an ordinary folder', async () => {
      show(null);
      const trigger = item('Alpha')!.querySelector('button.folder-menu')!;
      expect(trigger.getAttribute('aria-label')).toBe('Actions for Alpha');
      expect(trigger.getAttribute('aria-haspopup')).toBe('menu');
      expect(labels(await menu('Alpha'))).toEqual(['New subfolder', 'Rename', 'Delete']);
    });

    it('opens the menu from a focusable button and closes it with Escape', async () => {
      show(null);
      const trigger = item('Alpha')!.querySelector<HTMLButtonElement>('button.folder-menu')!;
      expect(trigger.tagName).toBe('BUTTON');
      expect(trigger.tabIndex).toBe(0);
      const entries = await menu('Alpha');
      expect(trigger.getAttribute('aria-expanded')).toBe('true');
      expect(entries.every((entry) => entry.getAttribute('role') === 'menuitem')).toBe(true);

      document
        .querySelector('.mat-mdc-menu-panel')!
        .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true }));
      fixture.detectChanges();
      await fixture.whenStable();

      expect(trigger.getAttribute('aria-expanded')).toBe('false');
    });

    it('offers only New subfolder for INBOX', async () => {
      show(null);
      expect(labels(await menu('Inbox'))).toEqual(['New subfolder']);
    });

    it('treats a folder named INBOX without a role as INBOX', async () => {
      show(null, [folder('in', 'inbox'), folder('x', 'Other')]);
      expect(labels(await menu('inbox'))).toEqual(['New subfolder']);
    });

    it('does not offer Delete for a folder with a role, nor for one holding it', async () => {
      show(null);
      expect(labels(await menu('Trash'))).toEqual(['New subfolder', 'Rename']);
      document.querySelectorAll('.cdk-overlay-container').forEach((n) => (n.innerHTML = ''));
      expect(labels(await menu('[Gmail]'))).toEqual(['New subfolder', 'Rename']);
    });

    it('does not offer New subfolder in a flat namespace', async () => {
      show(null, [
        folder('in', 'INBOX', { role: 'INBOX', delimiter: undefined }),
        folder('f', 'Flat', { delimiter: undefined }),
      ]);
      expect(labels(await menu('Flat'))).toEqual(['Rename', 'Delete']);
      // INBOX has nothing left to offer: no menu at all.
      expect(item('Inbox')!.querySelector('button.folder-menu')).toBeNull();
    });

    it('creates a top-level folder and shows it at once', async () => {
      show(null);
      el().querySelector<HTMLButtonElement>('button.add')!.click();
      expect(nameData().title).toBe('New folder');
      expect(nameData().confirm).toBe('Create');
      expect(nameData().delimiter).toBe('/');
      mail.createFolder.mockResolvedValue(folder('n', 'Receipts', { version: 1 }));

      await nameData().save('Receipts');
      fixture.detectChanges();

      expect(mail.createFolder).toHaveBeenCalledWith('acc-1', 'Receipts', undefined);
      expect(shown()).toContain('Receipts');
      expect(mail.accounts).toHaveBeenCalled();
    });

    it('creates a subfolder under the folder of the menu', async () => {
      show(null);
      (await menu('Projects'))[0].click();
      expect(nameData().title).toBe('New folder in Projects');
      mail.createFolder.mockResolvedValue(folder('pb', 'Projects/Beta'));

      await nameData().save('Beta');
      fixture.detectChanges();

      expect(mail.createFolder).toHaveBeenCalledWith('acc-1', 'Beta', 'p');
      expect(shown()).toEqual(['Inbox', '[Gmail]', 'Trash', 'Projects', 'Alpha', 'Old', 'Beta']);
      expect(item('Beta')!.style.getPropertyValue('--depth')).toBe('1');
    });

    it('renames a folder and moves its subfolders along at once', async () => {
      show(null);
      (await menu('Alpha'))[1].click();
      expect(nameData().title).toBe('Rename Alpha');
      expect(nameData().name).toBe('Alpha');
      mail.renameFolder.mockResolvedValue(folder('pa', 'Projects/Zulu', { version: 3 }));

      await nameData().save('Zulu');
      fixture.detectChanges();

      expect(mail.renameFolder).toHaveBeenCalledWith(expect.objectContaining({ id: 'pa' }), 'Zulu');
      expect(shown()).toEqual(['Inbox', '[Gmail]', 'Trash', 'Projects', 'Zulu', 'Old']);
      expect(store.folderById().get('pa1')?.path).toBe('Projects/Zulu/Old');
      expect(item('Old')!.style.getPropertyValue('--depth')).toBe('2');
    });

    it('lets a failed save reject, so the dialog keeps it, without changing the tree', async () => {
      show(null);
      el().querySelector<HTMLButtonElement>('button.add')!.click();
      const taken = new ApiError(409, 'Conflict', 'Name taken');
      mail.createFolder.mockRejectedValue(taken);

      await expect(nameData().save('Projects')).rejects.toBe(taken);

      expect(shown()).toEqual(['Inbox', '[Gmail]', 'Trash', 'Projects', 'Alpha', 'Old']);
      expect(mail.accounts).not.toHaveBeenCalled();
    });

    it.each([404, 412])(
      'reloads the folders when the folder changed elsewhere (%s)',
      async (status) => {
        show(null);
        (await menu('Alpha'))[1].click();
        mail.renameFolder.mockRejectedValue(new ApiError(status, 'Stale'));

        await expect(nameData().save('Zulu')).rejects.toBeInstanceOf(ApiError);

        expect(mail.accounts).toHaveBeenCalled();
      },
    );

    it('asks before deleting, counting the subfolders, and removes the folder at once', async () => {
      show(null);
      (await menu('Projects'))[2].click();
      expect(deleteData().name).toBe('Projects');
      expect(deleteData().subfolders).toBe(2);
      expect(mail.deleteFolder).not.toHaveBeenCalled();
      mail.deleteFolder.mockResolvedValue(undefined);

      await deleteData().remove();
      fixture.detectChanges();

      expect(mail.deleteFolder).toHaveBeenCalledWith(expect.objectContaining({ id: 'p' }));
      expect(shown()).toEqual(['Inbox', '[Gmail]', 'Trash']);
    });

    it('navigates to the unified Inbox when the shown folder is deleted', async () => {
      show('pa1');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      (await menu('Projects'))[2].click();
      mail.deleteFolder.mockResolvedValue(undefined);

      await deleteData().remove();

      expect(navigate).toHaveBeenCalledWith(['/mail']);
    });

    it('stays on the shown folder when another one is deleted', async () => {
      show('in');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      (await menu('Old'))[2].click();
      expect(deleteData().subfolders).toBe(0);
      mail.deleteFolder.mockResolvedValue(undefined);

      await deleteData().remove();

      expect(navigate).not.toHaveBeenCalled();
    });

    it('navigates to the unified Inbox when the deleted folder itself is shown', async () => {
      show('pa');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      (await menu('Alpha'))[2].click();
      expect(deleteData().subfolders).toBe(1);
      mail.deleteFolder.mockResolvedValue(undefined);

      await deleteData().remove();
      fixture.detectChanges();

      expect(navigate).toHaveBeenCalledWith(['/mail']);
      expect(shown()).toEqual(['Inbox', '[Gmail]', 'Trash', 'Projects']);
    });

    it('does not navigate when the unified Inbox is shown and a folder is deleted', async () => {
      show(null);
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      (await menu('Projects'))[2].click();
      mail.deleteFolder.mockResolvedValue(undefined);

      await deleteData().remove();

      expect(navigate).not.toHaveBeenCalled();
    });

    it('does not navigate when a sibling sharing the path prefix of the shown folder is deleted', async () => {
      show('p2', [...standard(), folder('p2', 'Projects2')]);
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      (await menu('Projects'))[2].click();
      expect(deleteData().subfolders).toBe(2);
      mail.deleteFolder.mockResolvedValue(undefined);

      await deleteData().remove();
      fixture.detectChanges();

      expect(navigate).not.toHaveBeenCalled();
      expect(shown()).toContain('Projects2');
    });

    it.each([412, 409])(
      'reloads the folders and keeps the folder when the server refuses the delete (%s)',
      async (status) => {
        show('pa');
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        (await menu('Alpha'))[2].click();
        mail.deleteFolder.mockRejectedValue(new ApiError(status, 'Refused'));

        await expect(deleteData().remove()).rejects.toBeInstanceOf(ApiError);
        fixture.detectChanges();

        expect(mail.accounts).toHaveBeenCalled();
        expect(navigate).not.toHaveBeenCalled();
        expect(shown()).toContain('Alpha');
      },
    );

    it('hides Delete once the reload after a refused delete (409) shows the folder has a role', async () => {
      show(null);
      (await menu('Alpha'))[2].click();
      mail.deleteFolder.mockRejectedValue(new ApiError(409, 'Conflict'));
      mail.accounts.mockResolvedValue([WORK, HOME]);
      mail.folders.mockImplementation(async (accountId: string) =>
        accountId === 'acc-1'
          ? standard().map((f) => (f.id === 'pa' ? { ...f, role: 'ARCHIVE' as const } : f))
          : [folder('h-in', 'INBOX', { role: 'INBOX', accountId: 'acc-2' })],
      );

      await expect(deleteData().remove()).rejects.toBeInstanceOf(ApiError);
      await vi.waitFor(() => expect(store.folderById().get('pa')?.role).toBe('ARCHIVE'));
      fixture.detectChanges();
      await fixture.whenStable();

      expect(mail.deleteFolder).toHaveBeenCalledTimes(1);
      expect(shown()).toEqual(['Inbox', '[Gmail]', 'Trash', 'Projects', 'Archive', 'Old']);
      expect(labels(await menu('Archive'))).toEqual(['New subfolder', 'Rename']);
    });

    it.each([
      ['502', new ApiError(502, 'Bad gateway')],
      ['0', new ApiError(0, 'Unreachable')],
      ['a non-API error', new Error('offline')],
      ['a non-API error carrying status 404', Object.assign(new Error('odd'), { status: 404 })],
    ])(
      'neither reloads nor removes the folder when the delete fails with %s',
      async (_label, failure) => {
        show('pa');
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        (await menu('Alpha'))[2].click();
        mail.deleteFolder.mockRejectedValue(failure);

        await expect(deleteData().remove()).rejects.toBe(failure);
        fixture.detectChanges();

        expect(mail.accounts).not.toHaveBeenCalled();
        expect(shown()).toContain('Alpha');
        expect(navigate).not.toHaveBeenCalled();
      },
    );

    it('does not reload when a rename is refused with 409 (the name is taken)', async () => {
      show(null);
      (await menu('Alpha'))[1].click();
      mail.renameFolder.mockRejectedValue(new ApiError(409, 'Conflict'));

      await expect(nameData().save('Old')).rejects.toBeInstanceOf(ApiError);

      expect(mail.accounts).not.toHaveBeenCalled();
    });

    it('treats a folder already gone on the server (404) as deleted, and leaves it if shown', async () => {
      show('pa1');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      (await menu('Alpha'))[2].click();
      mail.deleteFolder.mockRejectedValue(new ApiError(404, 'Not Found'));

      // Resolving lets the dialog close instead of offering a retry that can never work.
      await expect(deleteData().remove()).resolves.toBeUndefined();
      fixture.detectChanges();

      expect(shown()).toEqual(['Inbox', '[Gmail]', 'Trash', 'Projects']);
      expect(navigate).toHaveBeenCalledWith(['/mail']);
    });

    it('stays on the shown folder when another folder is already gone (404)', async () => {
      show('in');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      (await menu('Old'))[2].click();
      mail.deleteFolder.mockRejectedValue(new ApiError(404, 'Not Found'));

      await deleteData().remove();
      fixture.detectChanges();

      expect(shown()).not.toContain('Old');
      expect(navigate).not.toHaveBeenCalled();
    });

    it('reloads the folders when the parent of a new subfolder is gone (404)', async () => {
      show(null);
      (await menu('Projects'))[0].click();
      mail.createFolder.mockRejectedValue(new ApiError(404, 'Not Found'));

      await expect(nameData().save('Beta')).rejects.toBeInstanceOf(ApiError);

      expect(mail.accounts).toHaveBeenCalled();
    });

    it('does not reload on an error that is not about a stale folder', async () => {
      show(null);
      (await menu('Alpha'))[1].click();
      mail.renameFolder.mockRejectedValue(new Error('offline'));

      await expect(nameData().save('Zulu')).rejects.toThrow('offline');

      expect(mail.accounts).not.toHaveBeenCalled();
      expect(shown()).toContain('Alpha');
    });

    it('creates a folder in the account of the clicked button', async () => {
      show(null);
      el().querySelectorAll<HTMLButtonElement>('button.add')[1].click();
      mail.createFolder.mockResolvedValue(folder('h-new', 'Receipts', { accountId: 'acc-2' }));

      await nameData().save('Receipts');
      fixture.detectChanges();

      expect(mail.createFolder).toHaveBeenCalledWith('acc-2', 'Receipts', undefined);
      const home = [...el().querySelectorAll('section.account')][1];
      expect([...home.querySelectorAll('li .row')].map((r) => r.textContent?.trim())).toEqual([
        'Inbox',
        'Receipts',
      ]);
      expect(shown()).not.toContain('Receipts');
    });

    it('passes no delimiter to the new-folder dialog of a flat account', () => {
      show(null, [
        folder('in', 'INBOX', { role: 'INBOX', delimiter: undefined }),
        folder('f', 'Flat', { delimiter: undefined }),
      ]);
      el().querySelector<HTMLButtonElement>('button.add')!.click();
      expect(nameData().delimiter).toBeUndefined();
    });

    it("passes the folder's delimiter to the rename and subfolder dialogs", async () => {
      show(null, [
        folder('in', 'INBOX', { role: 'INBOX', delimiter: '.' }),
        folder('d', 'Dotted', { delimiter: '.' }),
        folder('e', 'Elsewhere', { delimiter: '.' }),
      ]);
      (await menu('Dotted'))[0].click();
      expect(nameData().delimiter).toBe('.');
      document.querySelectorAll('.cdk-overlay-container').forEach((n) => (n.innerHTML = ''));
      (await menu('Elsewhere'))[1].click();
      expect(nameData().title).toBe('Rename Elsewhere');
      expect(nameData().delimiter).toBe('.');
    });

    it('keeps the folder and the page when the delete fails', async () => {
      show('pa');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      (await menu('Alpha'))[2].click();
      mail.deleteFolder.mockRejectedValue(new ApiError(502, 'Bad gateway'));

      await expect(deleteData().remove()).rejects.toBeInstanceOf(ApiError);
      fixture.detectChanges();

      expect(shown()).toContain('Alpha');
      expect(navigate).not.toHaveBeenCalled();
    });
  });
});
