import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { FileResponse } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import { PdfJs, setPdfJsLoader } from './pdf-viewer';
import { PreviewDialog, PreviewDialogData } from './preview-dialog';

const STAMP = '2026-10-21T08:00:00Z';

function file(n: number, name: string, mime: string): FileResponse {
  return {
    id: `0192f0c4-0000-7000-8000-0000000001${String(n).padStart(2, '0')}`,
    name,
    mime,
    size: 1234,
    sha256: 'ab'.repeat(32),
    version: 0,
    createdAt: STAMP,
    updatedAt: STAMP,
  };
}

const IMAGE = file(1, 'Beach.png', 'image/png');
const ZIP = file(2, 'Archive.zip', 'application/zip');
const PDF = file(3, 'Invoice.pdf', 'application/pdf');
const TEXT = file(4, 'Notes.txt', 'text/plain; charset=utf-8');
const MARKDOWN = file(5, 'Plan.md', 'text/plain');
const AUDIO = file(6, 'Song.mp3', 'audio/mpeg');
const VIDEO = file(7, 'Clip.mp4', 'video/mp4');
const FOLDER_FILES = [IMAGE, ZIP, PDF, TEXT, MARKDOWN, AUDIO, VIDEO];

/** pdf.js that never finishes loading: the dialog tests only check that the viewer is chosen. */
const pendingPdfJs = {
  getDocument: vi.fn(() => ({ promise: new Promise(() => undefined), destroy: vi.fn() })),
} as unknown as PdfJs;

describe('PreviewDialog', () => {
  let fixture: ComponentFixture<PreviewDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(files: readonly FileResponse[], id: string): Promise<void> {
    close = vi.fn();
    setPdfJsLoader(() => Promise.resolve(pendingPdfJs));
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: { files, id } satisfies PreviewDialogData },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PreviewDialog);
    await render();
  }

  afterEach(() => {
    http.verify();
    setPdfJsLoader(null);
  });

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const title = (): string => el().querySelector('#preview-title')?.textContent?.trim() ?? '';
  const kind = (): string | null =>
    el().querySelector('.preview')?.getAttribute('data-kind') ?? null;
  const position = (): string => el().querySelector('.position')?.textContent?.trim() ?? '';
  const prevButton = () => el().querySelector<HTMLButtonElement>('button.step.prev');
  const nextButton = () => el().querySelector<HTMLButtonElement>('button.step.next');

  async function render(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  async function key(name: string, target: HTMLElement = el()): Promise<KeyboardEvent> {
    const event = new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true });
    target.dispatchEvent(event);
    await render();
    return event;
  }

  function expectText(target: FileResponse) {
    return http.expectOne(
      (r) => r.method === 'GET' && r.url === `/api/v1/files/${target.id}/preview`,
    );
  }

  it('shows an image as a lightbox with its name, position and download link', async () => {
    await open(FOLDER_FILES, IMAGE.id);
    expect(kind()).toBe('image');
    expect(title()).toBe('Beach.png');
    // The zip has no preview and is not counted.
    expect(position()).toBe('1 of 6');
    const img = el().querySelector('img')!;
    expect(img.getAttribute('src')).toBe(`/api/v1/files/${IMAGE.id}/preview`);
    expect(img.getAttribute('alt')).toBe('Beach.png');
    const download = el().querySelector<HTMLAnchorElement>('a[download]')!;
    expect(download.getAttribute('href')).toBe(`/api/v1/files/${IMAGE.id}/content`);
  });

  it('pages through the previewable files with the arrow keys, choosing the viewer by mime', async () => {
    await open(FOLDER_FILES, IMAGE.id);
    expect(prevButton()!.disabled).toBe(true);

    expect((await key('ArrowRight')).defaultPrevented).toBe(true);
    expect(title()).toBe('Invoice.pdf');
    expect(kind()).toBe('pdf');
    expect(el().querySelector('app-pdf-viewer')).not.toBeNull();
    expect(position()).toBe('2 of 6');

    await key('ArrowRight');
    expect(title()).toBe('Notes.txt');
    expect(kind()).toBe('text');
    expect(el().querySelector('[role="status"]')?.textContent).toContain('Loading');
    expectText(TEXT).flush('Buy milk\n<b>not bold</b>');
    await render();
    expect(el().querySelector('.sheet pre')?.textContent).toBe('Buy milk\n<b>not bold</b>');
    expect(el().querySelector('.sheet b')).toBeNull();
    expect(el().querySelector('.truncated')).toBeNull();

    await key('ArrowRight');
    expect(title()).toBe('Plan.md');
    expect(kind()).toBe('markdown');
    expectText(MARKDOWN).flush('# Plan\n\nSome **bold** words', {
      headers: { 'X-Preview-Truncated': 'true' },
    });
    await render();
    const markdown = el().querySelector('.sheet .markdown')!;
    expect(markdown.querySelector('h3')?.textContent).toBe('Plan');
    expect(markdown.querySelector('strong')?.textContent).toBe('bold');
    expect(el().querySelector('.truncated')?.textContent).toContain('Only the beginning');

    await key('ArrowRight');
    expect(kind()).toBe('audio');
    const audio = el().querySelector('audio')!;
    expect(audio.getAttribute('src')).toBe(`/api/v1/files/${AUDIO.id}/preview`);
    expect(audio.hasAttribute('controls')).toBe(true);

    await key('ArrowRight');
    expect(kind()).toBe('video');
    expect(el().querySelector('video')?.getAttribute('src')).toBe(
      `/api/v1/files/${VIDEO.id}/preview`,
    );
    expect(nextButton()!.disabled).toBe(true);
    expect(position()).toBe('6 of 6');

    // At the end, → stays; ← goes back (the text is loaded again).
    await key('ArrowRight');
    expect(title()).toBe('Clip.mp4');
    await key('ArrowLeft');
    expect(title()).toBe('Song.mp3');
    await key('ArrowLeft');
    expect(title()).toBe('Plan.md');
    expectText(MARKDOWN).flush('Again');
    await render();
  });

  it('pages with the side buttons and ignores other keys', async () => {
    await open(FOLDER_FILES, VIDEO.id);
    expect(title()).toBe('Clip.mp4');
    expect((await key('Enter')).defaultPrevented).toBe(false);
    expect(title()).toBe('Clip.mp4');

    prevButton()!.click();
    await render();
    expect(title()).toBe('Song.mp3');
    nextButton()!.click();
    await render();
    expect(title()).toBe('Clip.mp4');
  });

  it('leaves arrow keys typed in a media player to the player', async () => {
    await open(FOLDER_FILES, VIDEO.id);
    const event = await key('ArrowLeft', el().querySelector('video')!);
    expect(event.defaultPrevented).toBe(false);
    expect(title()).toBe('Clip.mp4');
  });

  it('starts at the first previewable file when the given one has no preview', async () => {
    await open(FOLDER_FILES, ZIP.id);
    expect(title()).toBe('Beach.png');
  });

  it('shows no position nor side buttons for a single file', async () => {
    await open([ZIP, PDF], PDF.id);
    expect(kind()).toBe('pdf');
    expect(el().querySelector('.position')).toBeNull();
    expect(prevButton()).toBeNull();
    expect(nextButton()).toBeNull();
    // ← and → do nothing with one file.
    await key('ArrowRight');
    await key('ArrowLeft');
    expect(title()).toBe('Invoice.pdf');
  });

  it('renders nothing when no file has a preview', async () => {
    await open([ZIP], ZIP.id);
    expect(el().querySelector('.preview')).toBeNull();
  });

  it('Close closes the dialog', async () => {
    await open([IMAGE], IMAGE.id);
    [...el().querySelectorAll<HTMLButtonElement>('button')]
      .find((b) => b.textContent?.trim() === 'Close')!
      .click();
    expect(close).toHaveBeenCalled();
  });

  it('says so when an image cannot be loaded, and shows the next one again', async () => {
    const second = file(8, 'Sunset.jpg', 'image/jpeg');
    await open([IMAGE, second], IMAGE.id);
    el().querySelector('img')!.dispatchEvent(new Event('error'));
    await render();
    expect(el().querySelector('img')).toBeNull();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('could not be shown');

    await key('ArrowRight');
    expect(el().querySelector('img')?.getAttribute('alt')).toBe('Sunset.jpg');
    expect(el().querySelector('[role="alert"]')).toBeNull();
  });

  it('says so when a text cannot be loaded', async () => {
    await open([TEXT], TEXT.id);
    expectText(TEXT).flush(null, { status: 500, statusText: 'Server Error' });
    await render();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('could not be loaded');
  });

  it('ignores the text of a file the user already paged away from', async () => {
    await open([TEXT, MARKDOWN], TEXT.id);
    const stale = expectText(TEXT);
    await key('ArrowRight');
    const current = expectText(MARKDOWN);
    stale.flush('Old text');
    await render();
    expect(el().querySelector('.sheet')).toBeNull();
    expect(el().querySelector('[role="status"]')?.textContent).toContain('Loading');
    current.flush('New *text*');
    await render();
    expect(el().querySelector('.sheet .markdown em')?.textContent).toBe('text');
  });

  it('ignores a failed text of a file the user already paged away from', async () => {
    await open([TEXT, MARKDOWN], TEXT.id);
    const stale = expectText(TEXT);
    await key('ArrowRight');
    stale.flush(null, { status: 500, statusText: 'Server Error' });
    await render();
    expect(el().querySelector('[role="alert"]')).toBeNull();
    expectText(MARKDOWN).flush('Fine');
    await render();
    expect(el().querySelector('.sheet')?.textContent).toContain('Fine');
  });

  it('shows the HTML and unsafe links of a Markdown file as inert text', async () => {
    await open([MARKDOWN], MARKDOWN.id);
    expectText(MARKDOWN).flush(
      '# <img src=x onerror="alert(1)">\n\n<script>alert(2)</script> [bad](javascript:alert(3)) [ok](https://example.org)',
    );
    await render();
    const markdown = el().querySelector('.sheet .markdown')!;
    expect(markdown.querySelector('img, script')).toBeNull();
    expect(markdown.querySelector('[onerror]')).toBeNull();
    expect(markdown.textContent).toContain('<img src=x onerror="alert(1)">');
    const hrefs = [...markdown.querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(hrefs.some((href) => href?.toLowerCase().startsWith('javascript:'))).toBe(false);
    expect(hrefs).toContain('https://example.org');
  });
});
