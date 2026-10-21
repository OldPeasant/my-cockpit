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

    it('shows a plain Waiting token when ^ names nobody', async () => {
      type('Parcel ^');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      http
        .expectOne('/api/v1/capture/preview')
        .flush({ title: 'Parcel', list: 'WAITING', contexts: [] });
      await settle();
      const tokens = [...el().querySelectorAll('.token')].map((t) => t.textContent?.trim());
      expect(tokens).toEqual(['Waiting']);
    });

    it('ignores an older preview that answers after a newer one', async () => {
      type('Buy milk');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      const older = http.expectOne('/api/v1/capture/preview');
      type('Buy oat milk');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      const newer = http.expectOne('/api/v1/capture/preview');
      expect(newer.request.body).toEqual({ text: 'Buy oat milk' });

      newer.flush({ title: 'Buy oat milk', list: 'INBOX', contexts: [] });
      await settle();
      older.flush({ title: 'Buy milk', list: 'INBOX', contexts: [] });
      await settle();
      expect(el().querySelector('.preview-title')?.textContent?.trim()).toBe('Buy oat milk');
    });

    it('a failing older preview does not hide the newer one', async () => {
      type('Buy milk');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      const older = http.expectOne('/api/v1/capture/preview');
      type('Buy oat milk');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      http
        .expectOne('/api/v1/capture/preview')
        .flush({ title: 'Buy oat milk', list: 'INBOX', contexts: [] });
      await settle();
      older.flush(null, { status: 500, statusText: 'Server Error' });
      await settle();
      expect(el().querySelector('.preview-title')?.textContent?.trim()).toBe('Buy oat milk');
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('drops a pending preview when the text is cleared, and shows the hint', async () => {
      type('Buy milk');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      const pending = http.expectOne('/api/v1/capture/preview');
      type('');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      pending.flush({ title: 'Buy milk', list: 'INBOX', contexts: [] });
      await settle();
      expect(el().querySelector('.preview-title')).toBeNull();
      expect(el().querySelector('.hint')).not.toBeNull();
    });

    it('hides a shown preview as soon as the text is cleared, before the debounce', async () => {
      type('Buy milk');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      http
        .expectOne('/api/v1/capture/preview')
        .flush({ title: 'Buy milk', list: 'INBOX', contexts: [] });
      await settle();
      expect(el().querySelector('.preview-title')).not.toBeNull();
      type('');
      expect(el().querySelector('.preview-title')).toBeNull();
      expect(el().querySelector('.hint')).not.toBeNull();
    });

    it('hides the reading of an edited text until the preview of the new text arrives', async () => {
      type('Buy milk #Groceries');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      http.expectOne('/api/v1/capture/preview').flush({
        title: 'Buy milk',
        list: 'INBOX',
        project: { id: 'p1', title: 'Groceries' },
        contexts: [],
      });
      await settle();
      expect(el().querySelector('.token.project')?.textContent?.trim()).toBe('#Groceries');

      type('Buy milk');
      expect(el().querySelector('.preview-title')).toBeNull();
      expect(el().querySelector('.token.project')).toBeNull();
      expect(el().querySelector('.hint')).not.toBeNull();

      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      http
        .expectOne('/api/v1/capture/preview')
        .flush({ title: 'Buy milk', list: 'INBOX', contexts: [] });
      await settle();
      expect(el().querySelector('.preview-title')?.textContent?.trim()).toBe('Buy milk');
      expect(el().querySelector('.token.project')).toBeNull();
    });

    it('does not show the saved capture reading next to the next text after Shift+Enter', async () => {
      type('First #Holiday');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      http.expectOne('/api/v1/capture/preview').flush({
        title: 'First',
        list: 'INBOX',
        project: { id: 'p1', title: 'Holiday' },
        contexts: [],
      });
      await settle();
      expect(el().querySelector('.preview-title')?.textContent?.trim()).toBe('First');

      press('Enter', { shiftKey: true });
      expectCapture().flush(task('First'));
      await settle();
      // The next capture starts within the debounce, so the empty text never reaches the server.
      type('Second');
      expect(el().querySelector('.preview-title')).toBeNull();
      expect(el().querySelector('.token.project')).toBeNull();
      expect(el().querySelector('.hint')).not.toBeNull();

      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      const next = http.expectOne('/api/v1/capture/preview');
      expect(next.request.body).toEqual({ text: 'Second' });
      next.flush({ title: 'Second', list: 'INBOX', contexts: [] });
      await settle();
      expect(el().querySelector('.preview-title')?.textContent?.trim()).toBe('Second');
    });

    it('does not ask again when only surrounding spaces change', () => {
      type('Buy milk');
      vi.advanceTimersByTime(PREVIEW_DEBOUNCE_MS);
      http
        .expectOne('/api/v1/capture/preview')
        .flush({ title: 'Buy milk', list: 'INBOX', contexts: [] });
      type('Buy milk  ');
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

    it('Shift+Enter keeps text, notes and files added while the save is running', async () => {
      type('First');
      type('Old notes', notesField());
      press('Enter', { shiftKey: true });
      const first = expectCapture();
      type('Second');
      const paste = new Event('paste', { cancelable: true }) as ClipboardEvent;
      Object.defineProperty(paste, 'clipboardData', {
        value: { files: [new File(['x'], 'scan.jpg')] },
      });
      el().querySelector<HTMLElement>('.capture')!.dispatchEvent(paste);
      first.flush(task('First'));
      await settle();
      expect(textField().value).toBe('Second');
      expect(notesField().value).toBe('');
      expect([...el().querySelectorAll('.file-name')].map((n) => n.textContent)).toEqual([
        'scan.jpg',
      ]);
      expect(el().querySelector('.count')?.textContent?.trim()).toBe('1 item captured');

      press('Enter', { shiftKey: true });
      const second = expectCapture();
      expect(second.request.body.text).toBe('Second');
      expect(second.request.body.id).toMatch(UUIDV7);
      expect(second.request.body.id).not.toBe(first.request.body.id);
      second.flush(task('Second'));
      await settle();
      expect(textField().value).toBe('');
      expect(el().querySelector('.file-list')).toBeNull();
    });

    it('Shift+Enter keeps notes edited while the save is running and clears the saved text', async () => {
      type('Call plumber');
      type('Leaking tap', notesField());
      press('Enter', { shiftKey: true });
      const first = expectCapture();
      expect(first.request.body.notes).toBe('Leaking tap');
      type('Leaking tap, kitchen', notesField());
      first.flush(task('Call plumber'));
      await settle();
      expect(textField().value).toBe('');
      expect(notesField().value).toBe('Leaking tap, kitchen');

      type('Buy washer');
      press('Enter', { shiftKey: true });
      const second = expectCapture();
      expect(second.request.body).toEqual({
        id: expect.stringMatching(UUIDV7),
        text: 'Buy washer',
        notes: 'Leaking tap, kitchen',
      });
      second.flush(task('Buy washer'));
      await settle();
      expect(notesField().value).toBe('');
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

    it('other keys in the text field neither save nor are prevented', () => {
      type('Call plumber');
      expect(press('a').defaultPrevented).toBe(false);
      expect(press('Tab').defaultPrevented).toBe(false);
      http.expectNone('/api/v1/capture');
    });

    it('Ctrl+Shift+Enter in the notes saves and keeps the dialog open', async () => {
      type('Call plumber');
      type('Before Friday', notesField());
      const event = press('Enter', { ctrlKey: true, shiftKey: true }, notesField());
      expect(event.defaultPrevented).toBe(true);
      const req = expectCapture();
      expect(req.request.body).toEqual({
        id: expect.stringMatching(UUIDV7),
        text: 'Call plumber',
        notes: 'Before Friday',
      });
      req.flush(task('Call plumber'));
      await settle();
      expect(close).not.toHaveBeenCalled();
      expect(textField().value).toBe('');
      expect(notesField().value).toBe('');
    });

    it('Cmd+Enter in the notes saves on macOS', async () => {
      type('Call plumber');
      press('Enter', { metaKey: true }, notesField());
      expectCapture().flush(task('Call plumber'));
      await settle();
      expect(close).toHaveBeenCalledTimes(1);
    });

    it('leaves out whitespace-only notes', async () => {
      type('Call plumber');
      type('   \n  ', notesField());
      press('Enter');
      expect(expectCapture().request.body).not.toHaveProperty('notes');
      await settle();
    });

    it('a failed save is reported by the error interceptor and keeps the dialog open', async () => {
      type('Call plumber');
      press('Enter');
      const failed = expectCapture();
      expect(failed.request.context.get(SILENT_ERRORS)).toBe(false);
      failed.flush(
        { title: 'Bad Request', status: 400, detail: 'Text is too long' },
        { status: 400, statusText: 'Bad Request' },
      );
      await settle();
      expect(notifications.error).toHaveBeenCalledTimes(1);
      expect(notifications.info).not.toHaveBeenCalled();
      expect(close).not.toHaveBeenCalled();
      expect(textField().value).toBe('Call plumber');
    });

    it('a failed Shift+Enter keeps the draft, notes and files for the retry', async () => {
      type('Call plumber');
      type('Leaking tap', notesField());
      press('Enter', { shiftKey: true });
      const failed = expectCapture();
      failed.flush(null, { status: 503, statusText: 'Service Unavailable' });
      await settle();
      expect(textField().value).toBe('Call plumber');
      expect(notesField().value).toBe('Leaking tap');
      expect(el().querySelector('.count')).toBeNull();

      press('Enter', { shiftKey: true });
      const retry = expectCapture();
      expect(retry.request.body).toEqual(failed.request.body);
      retry.flush(task('Call plumber'));
      await settle();
      expect(el().querySelector('.count')?.textContent?.trim()).toBe('1 item captured');
    });

    it('submit() reports whether a capture was saved', async () => {
      const dialog = fixture.componentInstance;
      await expect(dialog.submit()).resolves.toBe(false);
      http.expectNone('/api/v1/capture');

      type('Call plumber');
      const saved = dialog.submit();
      const again = dialog.submit();
      await expect(again).resolves.toBe(false);
      expectCapture().flush(task('Call plumber'));
      await expect(saved).resolves.toBe(true);

      type('Plan holiday');
      const failing = dialog.submit();
      expectCapture().flush(null, { status: 500, statusText: 'Server Error' });
      await expect(failing).resolves.toBe(false);
      await settle();
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

    it('ignores drags and drops without files', () => {
      const capture = el().querySelector<HTMLElement>('.capture')!;
      const over = new Event('dragover', { cancelable: true }) as DragEvent;
      Object.defineProperty(over, 'dataTransfer', { value: { types: ['text/plain'] } });
      capture.dispatchEvent(over);
      fixture.detectChanges();
      expect(over.defaultPrevented).toBe(false);
      expect(capture.classList).not.toContain('dragging');

      const drop = new Event('drop', { cancelable: true }) as DragEvent;
      Object.defineProperty(drop, 'dataTransfer', { value: { files: fileList(), types: [] } });
      capture.dispatchEvent(drop);
      fixture.detectChanges();
      expect(drop.defaultPrevented).toBe(false);
      expect(el().querySelector('.file-list')).toBeNull();
      expect(el().querySelector('.drop')).not.toBeNull();
    });

    it('a drop ends the dragging mark', () => {
      const capture = el().querySelector<HTMLElement>('.capture')!;
      const over = new Event('dragover', { cancelable: true }) as DragEvent;
      Object.defineProperty(over, 'dataTransfer', { value: { types: ['Files'] } });
      capture.dispatchEvent(over);
      const drop = new Event('drop', { cancelable: true }) as DragEvent;
      Object.defineProperty(drop, 'dataTransfer', {
        value: { files: fileList('scan.jpg'), types: ['Files'] },
      });
      capture.dispatchEvent(drop);
      fixture.detectChanges();
      expect(capture.classList).not.toContain('dragging');
      expect([...el().querySelectorAll('.file-name')].map((n) => n.textContent)).toEqual([
        'scan.jpg',
      ]);
    });

    it('Shift+Enter clears the listed files for the next capture', async () => {
      const capture = el().querySelector<HTMLElement>('.capture')!;
      const paste = new Event('paste', { cancelable: true }) as ClipboardEvent;
      Object.defineProperty(paste, 'clipboardData', { value: { files: fileList('photo.png') } });
      capture.dispatchEvent(paste);
      type('Expenses');
      expect(el().querySelector('.file-list')).not.toBeNull();
      press('Enter', { shiftKey: true });
      expectCapture().flush(task('Expenses'));
      await settle();
      expect(el().querySelector('.file-list')).toBeNull();
    });

    it('Shift+Enter removes only the submitted files, one entry per name', async () => {
      const capture = el().querySelector<HTMLElement>('.capture')!;
      const pasteFiles = (...names: string[]) => {
        const paste = new Event('paste', { cancelable: true }) as ClipboardEvent;
        Object.defineProperty(paste, 'clipboardData', { value: { files: fileList(...names) } });
        capture.dispatchEvent(paste);
        fixture.detectChanges();
      };
      const names = () => [...el().querySelectorAll('.file-name')].map((n) => n.textContent);

      pasteFiles('scan.jpg', 'receipt.pdf');
      type('Expenses');
      press('Enter', { shiftKey: true });
      const req = expectCapture();
      // While the save runs: one submitted file is removed, a same-named and a new file are added.
      el().querySelectorAll<HTMLButtonElement>('.file-list button')[1].click();
      pasteFiles('scan.jpg', 'invoice.pdf');
      expect(names()).toEqual(['scan.jpg', 'scan.jpg', 'invoice.pdf']);

      req.flush(task('Expenses'));
      await settle();
      expect(names()).toEqual(['scan.jpg', 'invoice.pdf']);
    });
  });

  it('Cancel closes without saving', () => {
    el().querySelector<HTMLButtonElement>('button.quiet')!.click();
    expect(close).toHaveBeenCalledWith();
  });
});

// The dialog injects MatDialogRef optionally: rendered outside a MatDialog it must still save.
describe('CaptureDialog outside a MatDialog', () => {
  let fixture: ComponentFixture<CaptureDialog>;
  let http: HttpTestingController;
  let notifications: { info: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    notifications = { info: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CaptureDialog);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('saves on Enter and Cancel does not throw without a dialog ref', async () => {
    const el = fixture.nativeElement as HTMLElement;
    const text = el.querySelector<HTMLInputElement>('#capture-text')!;
    text.value = 'Water the plants';
    text.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const saved = fixture.componentInstance.submit();
    http
      .expectOne((r) => r.method === 'POST' && r.url === '/api/v1/capture')
      .flush(task('Water the plants'));
    await expect(saved).resolves.toBe(true);
    expect(notifications.info).toHaveBeenCalledWith('Captured to Inbox: Water the plants');

    expect(() => el.querySelector<HTMLButtonElement>('button.quiet')!.click()).not.toThrow();
  });
});
