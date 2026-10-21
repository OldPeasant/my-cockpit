import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { FolderSummary } from '../../../api/generated';
import { DriveService } from './drive.service';
import { FolderTree } from './folder-tree';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));
const STAMP = '2026-10-20T10:00:00Z';

const DOCS = '0192f0c4-0000-7000-8000-0000000000d1';
const PHOTOS = '0192f0c4-0000-7000-8000-0000000000d2';
const TRIP = '0192f0c4-0000-7000-8000-0000000000d3';
const RECEIPTS = '0192f0c4-0000-7000-8000-0000000000d4';

function folder(id: string, name: string, parentId?: string): FolderSummary {
  return { id, name, parentId, version: 0, createdAt: STAMP, updatedAt: STAMP };
}

@Component({
  imports: [FolderTree],
  template: `<app-folder-tree [current]="current()" [path]="path()" />`,
})
class Host {
  readonly current = signal<string | null>(null);
  readonly path = signal<readonly string[]>([]);
  readonly tree = viewChild.required(FolderTree);
}

describe('FolderTree', () => {
  let fixture: ComponentFixture<Host>;
  let root: HTMLElement;
  /** Subfolders per parent id ('' for the root); a missing key fails the load. */
  let children: Record<string, FolderSummary[]>;
  let subfolders: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    children = {
      '': [folder(DOCS, 'Documents'), folder(PHOTOS, 'Photos')],
      [DOCS]: [folder(TRIP, 'Trip 2026', DOCS)],
      [PHOTOS]: [],
      [TRIP]: [],
    };
    subfolders = vi.fn(async (id: string | null) => {
      const list = children[id ?? ''];
      if (!list) throw new Error('unavailable');
      return list;
    });
  });

  async function create(path: readonly string[] = [], current: string | null = null) {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: DriveService, useValue: { subfolders } }],
    });
    fixture = TestBed.createComponent(Host);
    fixture.componentInstance.path.set(path);
    fixture.componentInstance.current.set(current);
    root = fixture.nativeElement as HTMLElement;
    await settle();
  }

  async function settle(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await tick();
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }

  function names(): string[] {
    return [...root.querySelectorAll('.name')].map((el) => el.textContent?.trim() ?? '');
  }

  function toggle(label: string): HTMLButtonElement {
    const el = root.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`);
    if (!el) throw new Error(`no toggle ${label}`);
    return el;
  }

  it('shows the root and its subfolders, collapsed, with the root as current', async () => {
    await create();

    expect(names()).toEqual(['My drive', 'Documents', 'Photos']);
    expect(subfolders).toHaveBeenCalledTimes(1);
    expect(subfolders).toHaveBeenCalledWith(null);
    expect(root.querySelector('.row.current .name')?.textContent?.trim()).toBe('My drive');
    expect(root.querySelector('a[aria-current="page"]')?.getAttribute('href')).toBe('/files');
    expect(toggle('Expand Documents').getAttribute('aria-expanded')).toBe('false');
  });

  it('loads a folder lazily on first expand and not again after collapsing', async () => {
    await create();

    toggle('Expand Documents').click();
    await settle();
    expect(subfolders).toHaveBeenCalledWith(DOCS);
    expect(names()).toEqual(['My drive', 'Documents', 'Trip 2026', 'Photos']);
    expect(toggle('Collapse Documents').getAttribute('aria-expanded')).toBe('true');

    toggle('Collapse Documents').click();
    await settle();
    expect(names()).toEqual(['My drive', 'Documents', 'Photos']);

    toggle('Expand Documents').click();
    await settle();
    expect(names()).toContain('Trip 2026');
    expect(subfolders.mock.calls.filter(([id]) => id === DOCS)).toHaveLength(1);
  });

  it('shows a loading note while a branch loads', async () => {
    await create();
    let release!: (folders: FolderSummary[]) => void;
    subfolders.mockImplementationOnce(
      () => new Promise<FolderSummary[]>((resolve) => (release = resolve)),
    );

    toggle('Expand Photos').click();
    await settle();
    expect(root.querySelector('.note')?.textContent?.trim()).toBe('Loading…');

    release([]);
    await settle();
    expect(root.querySelector('.note')).toBeNull();
  });

  it('expands the path to the current folder and marks it', async () => {
    await create([DOCS, TRIP], TRIP);

    expect(names()).toEqual(['My drive', 'Documents', 'Trip 2026', 'Photos']);
    expect(root.querySelector('.row.current .name')?.textContent?.trim()).toBe('Trip 2026');
    expect(root.querySelector('a[aria-current="page"]')?.getAttribute('href')).toBe(
      `/files/${TRIP}`,
    );
    expect(toggle('Collapse Documents').getAttribute('aria-expanded')).toBe('true');
    expect(toggle('Collapse Trip 2026').getAttribute('aria-expanded')).toBe('true');
  });

  it('shows a note when a branch cannot be loaded and loads it again on the next expand', async () => {
    delete children[DOCS];
    await create();

    toggle('Expand Documents').click();
    await settle();
    expect(root.querySelector('.note.error')?.textContent?.trim()).toBe(
      'Folders could not be loaded.',
    );

    children[DOCS] = [folder(TRIP, 'Trip 2026', DOCS)];
    toggle('Collapse Documents').click();
    await settle();
    toggle('Expand Documents').click();
    await settle();
    expect(root.querySelector('.note.error')).toBeNull();
    expect(names()).toContain('Trip 2026');
  });

  it('shows the failure note for the root when the drive cannot be listed', async () => {
    delete children[''];
    await create();

    expect(names()).toEqual(['My drive']);
    expect(root.querySelector('.note.error')).not.toBeNull();
  });

  it('reloads every loaded folder on refresh', async () => {
    await create([DOCS], DOCS);
    subfolders.mockClear();
    children[''] = [folder(DOCS, 'Documents')];
    children[DOCS] = [folder(TRIP, 'Trip 2026', DOCS), folder(RECEIPTS, 'Receipts', DOCS)];

    fixture.componentInstance.tree().refresh();
    await settle();

    expect(subfolders.mock.calls.map(([id]) => id).sort()).toEqual([DOCS, null].sort());
    expect(names()).toEqual(['My drive', 'Documents', 'Trip 2026', 'Receipts']);
  });

  describe('with refreshes answered out of order', () => {
    let pending: { resolve: (folders: FolderSummary[]) => void; reject: (e: Error) => void }[];

    beforeEach(async () => {
      await create();
      pending = [];
      subfolders.mockImplementation(
        () => new Promise<FolderSummary[]>((resolve, reject) => pending.push({ resolve, reject })),
      );
      const tree = fixture.componentInstance.tree();
      tree.refresh();
      tree.refresh();
      expect(pending).toHaveLength(2);
    });

    it('keeps the newer answer when the older one arrives last', async () => {
      // Photos was deleted between the two refreshes.
      pending[1].resolve([folder(DOCS, 'Documents')]);
      await settle();
      pending[0].resolve([folder(DOCS, 'Documents'), folder(PHOTOS, 'Photos')]);
      await settle();

      expect(names()).toEqual(['My drive', 'Documents']);
    });

    it('ignores a failure of the older load after the newer one succeeded', async () => {
      pending[1].resolve([folder(DOCS, 'Documents')]);
      await settle();
      pending[0].reject(new Error('unavailable'));
      await settle();

      expect(root.querySelector('.note.error')).toBeNull();
      expect(names()).toEqual(['My drive', 'Documents']);
    });
  });

  it('does not load a folder twice while its load is still running', async () => {
    let release!: (folders: FolderSummary[]) => void;
    subfolders.mockImplementationOnce(
      () => new Promise<FolderSummary[]>((resolve) => (release = resolve)),
    );
    await create();
    // The path changes while the root is still loading: the root is not requested again.
    fixture.componentInstance.path.set([DOCS]);
    await settle();
    expect(subfolders.mock.calls.filter(([id]) => id === null)).toHaveLength(1);

    release(children['']);
    await settle();
    expect(names()).toEqual(['My drive', 'Documents', 'Trip 2026', 'Photos']);
  });
});
