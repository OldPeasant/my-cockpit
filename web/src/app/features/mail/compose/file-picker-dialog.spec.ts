import { TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { DriveEntryResponse, FileResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { FilePickerDialog } from './file-picker-dialog';
import { MailComposeService } from './mail-compose.service';

const ROOT_FOLDER = '0192f0c4-0000-7000-8000-00000000fd01';
const SUB_FOLDER = '0192f0c4-0000-7000-8000-00000000fd02';

function folder(id: string, name: string): DriveEntryResponse {
  return { type: 'FOLDER', folder: { id, name } } as DriveEntryResponse;
}

function file(id: string, name: string, size = 2048): DriveEntryResponse {
  return { type: 'FILE', file: { id, name, size } as FileResponse } as DriveEntryResponse;
}

interface Pending {
  folderId: string | null;
  resolve: (entries: DriveEntryResponse[]) => void;
  reject: (error: unknown) => void;
}

/** Answers each folder listing only when the test says so. */
class FakeFiles {
  calls: Pending[] = [];
  files(folderId: string | null): Promise<DriveEntryResponse[]> {
    return new Promise((resolve, reject) => this.calls.push({ folderId, resolve, reject }));
  }
}

describe('FilePickerDialog', () => {
  let service: FakeFiles;
  let ref: MatDialogRef<FilePickerDialog, FileResponse>;
  let closedWith: unknown[];

  beforeEach(() => {
    service = new FakeFiles();
    closedWith = [];
    TestBed.configureTestingModule({
      providers: [{ provide: MailComposeService, useValue: service }],
    });
    ref = TestBed.inject(MatDialog).open(FilePickerDialog);
    vi.spyOn(ref, 'close').mockImplementation((result) => void closedWith.push(result));
  });

  afterEach(() => TestBed.inject(MatDialog).closeAll());

  const pane = () => document.querySelector('app-file-picker-dialog') as HTMLElement;
  const buttons = () => [...pane().querySelectorAll('button')];
  const button = (text: string) => buttons().find((b) => b.textContent?.trim().startsWith(text))!;
  const settle = async () => {
    for (let i = 0; i < 5; i++) await Promise.resolve();
    TestBed.tick();
  };

  it('lists the root folder, folders before files with their size', async () => {
    TestBed.tick();
    expect(service.calls.map((c) => c.folderId)).toEqual([null]);
    expect(pane().querySelector('[role=status]')?.textContent).toContain('Loading');

    service.calls[0].resolve([folder(ROOT_FOLDER, 'Projects'), file('f-1', 'budget.pdf')]);
    await settle();
    const entries = [...pane().querySelectorAll('.entry')].map((e) => e.textContent?.trim());
    expect(entries[0]).toContain('Projects/');
    expect(entries[1]).toContain('budget.pdf');
    expect(entries[1]).toContain('2');
  });

  it('opens a folder, shows it in the path and goes back to the root', async () => {
    TestBed.tick();
    service.calls[0].resolve([folder(ROOT_FOLDER, 'Projects')]);
    await settle();

    button('Projects/').click();
    await settle();
    expect(service.calls.map((c) => c.folderId)).toEqual([null, ROOT_FOLDER]);
    service.calls[1].resolve([folder(SUB_FOLDER, '2027'), file('f-2', 'plan.odt')]);
    await settle();
    expect([...pane().querySelectorAll('.crumb')].map((c) => c.textContent?.trim())).toEqual([
      'Files',
      'Projects',
    ]);

    button('2027/').click();
    await settle();
    service.calls[2].resolve([]);
    await settle();
    expect(pane().textContent).toContain('This folder is empty.');

    // Back up one level through the path, then to the root.
    button('Projects').click();
    await settle();
    expect(service.calls[3].folderId).toBe(ROOT_FOLDER);
    service.calls[3].resolve([]);
    button('Files').click();
    await settle();
    expect(service.calls[4].folderId).toBeNull();
    expect([...pane().querySelectorAll('.crumb')]).toHaveLength(1);
  });

  it('closes with the chosen file', async () => {
    TestBed.tick();
    service.calls[0].resolve([file('f-1', 'budget.pdf')]);
    await settle();
    button('budget.pdf').click();
    await settle();
    expect(closedWith).toEqual([expect.objectContaining({ id: 'f-1', name: 'budget.pdf' })]);
  });

  it('closes without a file on Cancel', async () => {
    TestBed.tick();
    service.calls[0].resolve([file('f-1', 'budget.pdf')]);
    await settle();
    button('Cancel').click();
    await settle();
    expect(closedWith).toEqual([undefined]);
  });

  it('shows why a folder could not be listed, and lists again after navigating', async () => {
    TestBed.tick();
    service.calls[0].reject(new ApiError(503, 'Unavailable', 'Files are not available.'));
    await settle();
    expect(pane().querySelector('[role=alert]')?.textContent).toContain('Files are not available.');

    button('Files').click();
    await settle();
    service.calls[1].resolve([file('f-1', 'budget.pdf')]);
    await settle();
    expect(pane().querySelector('[role=alert]')).toBeNull();
    expect(pane().textContent).toContain('budget.pdf');
  });

  it('shows a generic message for an unexpected error', async () => {
    TestBed.tick();
    service.calls[0].reject(new TypeError('network'));
    await settle();
    expect(pane().querySelector('[role=alert]')?.textContent).toContain('Something went wrong.');
  });

  it('ignores a slow answer for a folder the user already left', async () => {
    TestBed.tick();
    service.calls[0].resolve([folder(ROOT_FOLDER, 'Projects')]);
    await settle();

    button('Projects/').click();
    await settle();
    button('Files').click();
    await settle();
    // The root answers first; the left folder's answer (and its failure) come later.
    service.calls[2].resolve([file('f-root', 'root.txt')]);
    await settle();
    service.calls[1].resolve([file('f-old', 'inside-projects.txt')]);
    await settle();
    expect(pane().textContent).toContain('root.txt');
    expect(pane().textContent).not.toContain('inside-projects.txt');
    expect(pane().querySelector('[role=status]')).toBeNull();
  });

  it('ignores a late failure for a folder the user already left', async () => {
    TestBed.tick();
    service.calls[0].resolve([folder(ROOT_FOLDER, 'Projects')]);
    await settle();
    button('Projects/').click();
    await settle();
    button('Files').click();
    await settle();
    service.calls[2].resolve([file('f-root', 'root.txt')]);
    await settle();
    service.calls[1].reject(new ApiError(404, 'Not Found', 'Folder gone'));
    await settle();
    expect(pane().querySelector('[role=alert]')).toBeNull();
    expect(pane().textContent).toContain('root.txt');
  });
});
