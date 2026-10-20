import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { CapturePreviewResponse, TaskResponse } from '../../api/generated';
import { NotificationService, provideHttp, SILENT_ERRORS } from '../../core/http';
import { CaptureDialog, PREVIEW_DEBOUNCE_MS } from './capture-dialog';

const UUIDV7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function task(title: string): TaskResponse {
  return {
    id: '0192f0c4-0000-7000-8000-0000000000aa',
    title,
    list: 'INBOX',
    contextIds: [],
    position: 1,
    version: 0,
    createdAt: '2026-10-21T08:00:00Z',
    updatedAt: '2026-10-21T08:00:00Z',
  } as unknown as TaskResponse;
}

describe('CaptureDialog', () => {
  let fixture: ComponentFixture<CaptureDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  let notifications: { info: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    vi.useFakeTimers();
    close = vi.fn();
    notifications = { info: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: { close } },
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CaptureDialog);
    fixture.detectChanges();
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const textField = (): HTMLInputElement => el().querySelector('#capture-text')!;
  const notesField = (): HTMLTextAreaElement => el().querySelector('textarea.notes')!;
  const saveButton = (): HTMLButtonElement => el().querySelector('button.primary')!;

  function type(value: string, field: HTMLInputElement | HTMLTextAreaElement = textField()): void {
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function press(key: string, init: KeyboardEventInit = {}, field: HTMLElement = textField()) {
    const event = new KeyboardEvent('keydown', { key, cancelable: true, ...init });
    field.dispatchEvent(event);
    return event;
  }

  /** Lets awaited promises settle and renders. */
  async function settle(): Promise<void> {
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();
  }

  function expectCapture() {
    return http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/capture');
  }

  describe('preview', () => {
    it('asks for the preview silently once typing pauses, only for the last text', () => {
      type('Buy');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS - 1);
      type('Buy milk !tomorrow @errands');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS - 1);
      http.expectNone('/api/v1/capture/preview');
      vi.advanceTimersByTime(1);
      const req = http.expectOne('/api/v1/capture/preview');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ text: 'Buy milk !tomorrow @errands' });
      expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
      req.flush({ title: 'Buy milk', list: 'INBOX', contexts: [] });
    });

    it('shows the parsed title, list, project, contexts and due date', async () => {
      type('Plan holiday #Holiday @home !2026-11-03');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      http.expectOne('/api/v1/capture/preview').flush({
        title: 'Plan holiday',
        list: 'INBOX',
        project: { id: 'p1', title: 'Holiday' },
        contexts: [{ id: 'c1', name: 'home' }],
        dueDate: '2026-11-03',
      } satisfies CapturePreviewResponse);
      await settle();
      expect(el().querySelector('.preview-title')?.textContent?.trim()).toBe('Plan holiday');
      const tokens = [...el().querySelectorAll('.token')].map((t) => t.textContent?.trim());
      expect(tokens).toEqual(['Inbox', '#Holiday', '@home', 'Due Tue, Nov 3']);
    });

    it('shows the waiting person of a ^ token and a missing title', async () => {
      type('^Anna');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      http
        .expectOne('/api/v1/capture/preview')
        .flush({ title: '', list: 'WAITING', waitingFor: 'Anna', contexts: [] });
      await settle();
      expect(el().querySelector('.preview-title')?.textContent?.trim()).toBe('No title yet');
      expect(el().querySelector('.token.waiting')?.textContent?.trim()).toBe('Waiting for Anna');
    });

    it('shows the syntax hint for an empty text and when the preview fails', async () => {
      expect(el().querySelector('.hint')).not.toBeNull();
      type('Call plumber');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      http
        .expectOne('/api/v1/capture/preview')
        .flush({ title: 'x' }, { status: 500, statusText: 'Server Error' });
      await settle();
      expect(el().querySelector('.preview-title')).toBeNull();
      expect(el().querySelector('.hint')).not.toBeNull();
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('does not ask for a preview of blank text', () => {
      type('   ');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      http.expectNone('/api/v1/capture/preview');
    });
  });

  describe('save', () => {
    it('Enter saves text and notes with a UUIDv7 id, then closes with the task', async () => {
      type('  Call plumber  ');
      type('Leaking tap in the kitchen', notesField());
      expect(press('Enter').defaultPrevented).toBe(true);
      const req = expectCapture();
      expect(req.request.body).toEqual({
        id: expect.stringMatching(UUIDV7),
        text: 'Call plumber',
        notes: 'Leaking tap in the kitchen',
      });
      const saved = task('Call plumber');
      req.flush(saved);
      await settle();
      expect(close).toHaveBeenCalledWith(saved);
      expect(notifications.info).toHaveBeenCalledWith('Captured to Inbox: Call plumber');
    });

    it('leaves out empty notes', async () => {
      type('Call plumber');
      press('Enter');
      expect(expectCapture().request.body).not.toHaveProperty('notes');
      await settle();
    });

    it('Shift+Enter saves, clears the fields and stays open with a new id for the next capture', async () => {
      type('First');
      type('Some notes', notesField());
      press('Enter', { shiftKey: true });
      const first = expectCapture();
      first.flush(task('First'));
      await settle();
      expect(close).not.toHaveBeenCalled();
      expect(textField().value).toBe('');
      expect(notesField().value).toBe('');
      expect(document.activeElement === textField() || !textField().isConnected).toBe(true);
      expect(el().querySelector('.count')?.textContent?.trim()).toBe('1 item captured');

      type('Second');
      press('Enter', { shiftKey: true });
      const second = expectCapture();
      expect(second.request.body.id).toMatch(UUIDV7);
      expect(second.request.body.id).not.toBe(first.request.body.id);
      second.flush(task('Second'));
      await settle();
      expect(el().querySelector('.count')?.textContent?.trim()).toBe('2 items captured');
    });

    it('the save button saves and closes', async () => {
      type('Call plumber');
      saveButton().click();
      expectCapture().flush(task('Call plumber'));
      await settle();
      expect(close).toHaveBeenCalled();
    });

    it('does nothing for blank text, and the save button is disabled', () => {
      type('   ');
      expect(saveButton().disabled).toBe(true);
      press('Enter');
      http.expectNone('/api/v1/capture');
    });

    it('ignores a second submit while the first is running', async () => {
      type('Call plumber');
      press('Enter');
      press('Enter');
      press('Enter', { shiftKey: true });
      fixture.detectChanges();
      expect(saveButton().disabled).toBe(true);
      const req = expectCapture();
      req.flush(task('Call plumber'));
      await settle();
      expect(close).toHaveBeenCalledTimes(1);
    });

    it('keeps the draft after a failed save and retries with the same id', async () => {
      type('Call plumber');
      press('Enter');
      const failed = expectCapture();
      failed.flush(null, { status: 0, statusText: 'Unknown Error' });
      await settle();
      expect(close).not.toHaveBeenCalled();
      expect(textField().value).toBe('Call plumber');
      expect(saveButton().disabled).toBe(false);

      press('Enter');
      const retry = expectCapture();
      expect(retry.request.body.id).toBe(failed.request.body.id);
      retry.flush(task('Call plumber'));
      await settle();
      expect(close).toHaveBeenCalled();
    });

    it('Enter in the notes adds a line, Ctrl+Enter saves', async () => {
      type('Call plumber');
      expect(press('Enter', {}, notesField()).defaultPrevented).toBe(false);
      http.expectNone('/api/v1/capture');
      press('Enter', { ctrlKey: true }, notesField());
      expectCapture().flush(task('Call plumber'));
      await settle();
      expect(close).toHaveBeenCalled();
    });

    it('does not save while an IME composition is running', () => {
      type('Call plumber');
      press('Enter', { isComposing: true });
      http.expectNone('/api/v1/capture');
    });
  });

  describe('attachments placeholder', () => {
    function fileList(...names: string[]): FileList {
      const files = names.map((name) => new File(['x'], name));
      return Object.assign(files, { item: (i: number) => files[i] }) as unknown as FileList;
    }

    it('lists dropped and pasted files as not saved, and can remove them', async () => {
      const capture = el().querySelector<HTMLElement>('.capture')!;
      const drop = new Event('drop', { cancelable: true }) as DragEvent;
      Object.defineProperty(drop, 'dataTransfer', {
        value: { files: fileList('receipt.pdf'), types: ['Files'] },
      });
      capture.dispatchEvent(drop);
      expect(drop.defaultPrevented).toBe(true);

      const paste = new Event('paste', { cancelable: true }) as ClipboardEvent;
      Object.defineProperty(paste, 'clipboardData', { value: { files: fileList('photo.png') } });
      capture.dispatchEvent(paste);
      expect(paste.defaultPrevented).toBe(true);
      fixture.detectChanges();

      const names = () => [...el().querySelectorAll('.file-name')].map((n) => n.textContent);
      expect(names()).toEqual(['receipt.pdf', 'photo.png']);
      expect(el().querySelector('.files-note')?.textContent).toContain("can't be saved yet");

      el().querySelector<HTMLButtonElement>('.file-list button')!.click();
      fixture.detectChanges();
      expect(names()).toEqual(['photo.png']);

      // Files are never sent with the capture.
      type('Expenses');
      press('Enter');
      expect(expectCapture().request.body).not.toHaveProperty('fileIds');
      await settle();
    });

    it('marks the dialog while files are dragged over it, and leaves text pastes alone', () => {
      const capture = el().querySelector<HTMLElement>('.capture')!;
      const over = new Event('dragover', { cancelable: true }) as DragEvent;
      Object.defineProperty(over, 'dataTransfer', { value: { types: ['Files'] } });
      capture.dispatchEvent(over);
      fixture.detectChanges();
      expect(over.defaultPrevented).toBe(true);
      expect(capture.classList).toContain('dragging');

      capture.dispatchEvent(new Event('dragleave'));
      fixture.detectChanges();
      expect(capture.classList).not.toContain('dragging');

      const paste = new Event('paste', { cancelable: true }) as ClipboardEvent;
      Object.defineProperty(paste, 'clipboardData', { value: { files: fileList() } });
      capture.dispatchEvent(paste);
      expect(paste.defaultPrevented).toBe(false);
    });
  });

  it('Cancel closes without saving', () => {
    el().querySelector<HTMLButtonElement>('button.quiet')!.click();
    expect(close).toHaveBeenCalledWith();
  });
});
