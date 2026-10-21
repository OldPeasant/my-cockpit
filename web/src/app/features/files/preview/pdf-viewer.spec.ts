import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { loadPdfJs, PDF_WORKER_SRC, PdfJs, PdfViewer, setPdfJsLoader } from './pdf-viewer';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PdfViewer],
  template: `@if (shown()) {
    <app-pdf-viewer [src]="src()" />
  }`,
})
class Host {
  readonly src = signal('/api/v1/files/0192f0c4-0000-7000-8000-000000000101/preview');
  readonly shown = signal(true);
}

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** A fake pdf.js document with `numPages` pages whose renders the test controls. */
class FakePdf {
  readonly renders: { page: number; done: Deferred<void>; cancel: ReturnType<typeof vi.fn> }[] = [];
  readonly tasks: { url: string; destroy: ReturnType<typeof vi.fn>; doc: Deferred<unknown> }[] = [];
  /** Whether renders finish at once. */
  autoRender = true;
  /** Whether getPage waits for the test (see `pageLoads`). */
  manualPages = false;
  readonly pageLoads: { page: number; load: Deferred<void> }[] = [];
  /** Whether a render is running on the canvas: pdf.js refuses a second one. */
  private busy = false;

  constructor(readonly numPages: number) {}

  readonly lib = {
    getDocument: vi.fn((params: { url: string; withCredentials: boolean }) => {
      const doc = deferred<unknown>();
      const task = { url: params.url, destroy: vi.fn(() => Promise.resolve()), doc };
      this.tasks.push(task);
      return { promise: doc.promise, destroy: task.destroy };
    }),
  } as unknown as PdfJs;

  /** Answers the latest getDocument with a document. */
  open(): void {
    const { numPages } = this;
    this.tasks[this.tasks.length - 1].doc.resolve({
      numPages,
      getPage: async (page: number) => {
        if (this.manualPages) {
          const load = deferred<void>();
          this.pageLoads.push({ page, load });
          await load.promise;
        }
        return this.page(page);
      },
    });
  }

  private page(page: number) {
    return {
      getViewport: ({ scale }: { scale: number }) => ({
        width: 600 * scale,
        height: 800 * scale,
      }),
      render: () => {
        if (this.busy) throw new Error('Cannot use the same canvas during multiple render()');
        this.busy = true;
        const done = deferred<void>();
        void done.promise.then(
          () => (this.busy = false),
          () => (this.busy = false),
        );
        const cancel = vi.fn(() => {
          const error = new Error('cancelled');
          error.name = 'RenderingCancelledException';
          done.reject(error);
        });
        this.renders.push({ page, done, cancel });
        if (this.autoRender) done.resolve();
        return { promise: done.promise, cancel };
      },
    };
  }
}

describe('PdfViewer', () => {
  let fixture: ComponentFixture<Host>;
  let pdf: FakePdf;

  async function create(pages = 3): Promise<void> {
    pdf = new FakePdf(pages);
    setPdfJsLoader(() => Promise.resolve(pdf.lib));
    fixture = TestBed.createComponent(Host);
    await render();
  }

  afterEach(() => setPdfJsLoader(null));

  async function render(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await fixture.whenStable();
      await new Promise((resolve) => setTimeout(resolve));
    }
    fixture.detectChanges();
  }

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const canvas = (): HTMLCanvasElement => el().querySelector('canvas')!;
  const count = (): string => el().querySelector('.count')?.textContent?.trim() ?? '';
  const button = (text: string): HTMLButtonElement =>
    [...el().querySelectorAll<HTMLButtonElement>('.pager button')].find(
      (b) => b.textContent?.trim() === text,
    )!;

  it('loads the document with the session cookie and draws the first page', async () => {
    await create();
    expect(el().querySelector('[role="status"]')?.textContent).toContain('Loading the PDF');
    expect(pdf.lib.getDocument).toHaveBeenCalledWith({
      url: fixture.componentInstance.src(),
      withCredentials: true,
    });

    pdf.open();
    await render();
    expect(count()).toBe('Page 1 of 3');
    expect(pdf.renders.map((r) => r.page)).toEqual([1]);
    expect(canvas().getAttribute('data-rendered')).toBe('1');
    expect(canvas().getAttribute('aria-label')).toBe('Page 1');
    // jsdom has no layout: the viewer falls back to 800 px.
    expect(canvas().style.width).toBe('800px');
    expect(button('Previous page').disabled).toBe(true);
    expect(button('Next page').disabled).toBe(false);
  });

  it('moves between pages and stops at the ends', async () => {
    await create(2);
    pdf.open();
    await render();

    button('Next page').click();
    await render();
    expect(count()).toBe('Page 2 of 2');
    expect(canvas().getAttribute('data-rendered')).toBe('2');
    expect(button('Next page').disabled).toBe(true);

    button('Previous page').click();
    await render();
    expect(count()).toBe('Page 1 of 2');
    expect(pdf.renders.map((r) => r.page)).toEqual([1, 2, 1]);
  });

  it('cancels a running render for the next page without showing an error', async () => {
    await create();
    pdf.autoRender = false;
    pdf.open();
    await render();
    expect(canvas().hasAttribute('data-rendered')).toBe(false);

    button('Next page').click();
    await render();
    expect(pdf.renders[0].cancel).toHaveBeenCalled();
    pdf.renders[1].done.resolve();
    await render();
    expect(canvas().getAttribute('data-rendered')).toBe('2');
    expect(el().querySelector('[role="alert"]')).toBeNull();
  });

  it('draws only the latest page when pages change while one is still loading', async () => {
    await create();
    pdf.manualPages = true;
    pdf.autoRender = false;
    pdf.open();
    await render();
    // Page 1 loads and starts drawing; then Next twice while page 2 is still loading.
    pdf.pageLoads[0].load.resolve();
    await render();
    expect(pdf.renders.map((r) => r.page)).toEqual([1]);
    button('Next page').click();
    await render();
    button('Next page').click();
    await render();
    expect(pdf.renders[0].cancel).toHaveBeenCalled();
    expect(pdf.pageLoads.map((l) => l.page)).toEqual([1, 2, 3]);

    // Page 3 answers first, then the stale page 2: only page 3 is drawn.
    pdf.pageLoads[2].load.resolve();
    await render();
    pdf.pageLoads[1].load.resolve();
    await render();
    expect(pdf.renders.map((r) => r.page)).toEqual([1, 3]);
    pdf.renders[1].done.resolve();
    await render();
    expect(count()).toBe('Page 3 of 3');
    expect(canvas().getAttribute('data-rendered')).toBe('3');
    expect(el().querySelector('[role="alert"]')).toBeNull();
  });

  it('a stale render that fails does not show an error', async () => {
    await create();
    pdf.autoRender = false;
    pdf.open();
    await render();
    const first = pdf.renders[0];
    // The cancel of page 1 is not honored: its render fails later, after page 2 was requested.
    first.cancel.mockImplementation(() => undefined);
    button('Next page').click();
    await render();
    first.done.reject(new Error('Rendering failed'));
    await render();
    expect(el().querySelector('[role="alert"]')).toBeNull();
    // Page 2 started only after page 1 had stopped.
    expect(pdf.renders.map((r) => r.page)).toEqual([1, 2]);
    pdf.renders[1].done.resolve();
    await render();
    expect(canvas().getAttribute('data-rendered')).toBe('2');
  });

  it('shows an error when the document cannot be loaded', async () => {
    await create();
    pdf.tasks[0].doc.reject(new Error('Invalid PDF structure'));
    await render();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('could not be shown');
    expect(el().querySelector('canvas')).toBeNull();
  });

  it('shows an error when a page fails to render', async () => {
    await create();
    pdf.autoRender = false;
    pdf.open();
    await render();
    pdf.renders[0].done.reject(new Error('broken page'));
    await render();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('could not be shown');
  });

  it('opens a new source from page 1 and drops the old document', async () => {
    await create();
    pdf.open();
    await render();
    button('Next page').click();
    await render();

    const next = '/api/v1/files/0192f0c4-0000-7000-8000-000000000102/preview';
    fixture.componentInstance.src.set(next);
    await render();
    expect(pdf.tasks[0].destroy).toHaveBeenCalled();
    expect(pdf.tasks[1].url).toBe(next);
    pdf.open();
    await render();
    expect(count()).toBe('Page 1 of 3');
  });

  it('ignores a document that finishes loading after the source changed', async () => {
    await create();
    const first = pdf.tasks[0];
    fixture.componentInstance.src.set('/api/v1/files/0192f0c4-0000-7000-8000-000000000103/preview');
    await render();
    // The first load answers late, and with an error: neither shows.
    first.doc.reject(new Error('destroyed'));
    await render();
    expect(el().querySelector('[role="alert"]')).toBeNull();
    pdf.open();
    await render();
    expect(count()).toBe('Page 1 of 3');
  });

  it('does not show an older document that loads successfully after the source changed', async () => {
    await create();
    const first = pdf.tasks[0];
    fixture.componentInstance.src.set('/api/v1/files/0192f0c4-0000-7000-8000-000000000104/preview');
    await render();
    // The first document answers late, with 9 pages: the viewer keeps waiting for the new one.
    first.doc.resolve({ numPages: 9, getPage: vi.fn() });
    await render();
    expect(count()).not.toContain('of 9');
    expect(el().querySelector('[role="alert"]')).toBeNull();
    pdf.open();
    await render();
    expect(count()).toBe('Page 1 of 3');
  });

  it('destroys the loading task when the viewer goes away', async () => {
    await create();
    pdf.open();
    await render();
    fixture.componentInstance.shown.set(false);
    await render();
    expect(pdf.tasks[0].destroy).toHaveBeenCalled();
  });
});

describe('loadPdfJs', () => {
  afterEach(() => setPdfJsLoader(null));

  it('returns the same loader result until it is replaced', async () => {
    const lib = { name: 'fake pdf.js' } as unknown as PdfJs;
    setPdfJsLoader(() => Promise.resolve(lib));
    await expect(loadPdfJs()).resolves.toBe(lib);
    expect(loadPdfJs()).toBe(loadPdfJs());
  });

  it('serves the worker from the copied assets', () => {
    expect(PDF_WORKER_SRC).toBe('pdfjs/pdf.worker.min.mjs');
  });
});
