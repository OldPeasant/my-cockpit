import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MailFolderResponse } from '../../../api/generated';
import { folderLabel, MailFolderTree } from './mail-folder-tree';
import { AccountTree, folderTree, MailFoldersStore } from './mail-folders-store';
import { signal } from '@angular/core';

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
  let store: {
    trees: ReturnType<typeof signal<AccountTree[] | null>>;
    error: ReturnType<typeof signal<string | null>>;
  };

  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    store = { trees: signal<AccountTree[] | null>(null), error: signal<string | null>(null) };
    TestBed.configureTestingModule({
      imports: [MailFolderTree],
      providers: [provideRouter([]), { provide: MailFoldersStore, useValue: store }],
    });
    fixture = TestBed.createComponent(MailFolderTree);
  });

  function show(current: string | null) {
    store.trees.set([
      {
        account: { id: 'acc-1', displayName: 'Work', emailAddress: 'work@example.test' },
        folders: folderTree([
          folder('in', 'INBOX', { role: 'INBOX' }),
          folder('g', '[Gmail]', { selectable: false }),
          folder('gt', '[Gmail]/Bin', { role: 'TRASH' }),
        ]),
      },
      {
        account: { id: 'acc-2', displayName: 'Home', emailAddress: 'home@example.test' },
        folders: folderTree([folder('h-in', 'INBOX', { role: 'INBOX', accountId: 'acc-2' })]),
      },
    ] as AccountTree[]);
    fixture.componentRef.setInput('current', current);
    fixture.detectChanges();
  }

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
    expect(sections.map((s) => s.getAttribute('aria-label'))).toEqual(['Work', 'Home']);
    expect(sections[0].querySelector('h2')?.getAttribute('title')).toBe('work@example.test');
    const links = [...sections[0].querySelectorAll<HTMLAnchorElement>('a.row')];
    expect(links.map((a) => [a.textContent?.trim(), a.getAttribute('href')])).toEqual([
      ['Inbox', '/mail/folders/in'],
      ['Trash', '/mail/folders/gt'],
    ]);
    const group = sections[0].querySelector('span.group');
    expect(group?.textContent?.trim()).toBe('[Gmail]');
    const items = [...sections[0].querySelectorAll<HTMLElement>('li')];
    expect(items.map((li) => li.style.getPropertyValue('--depth'))).toEqual(['0', '0', '1']);
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
});
