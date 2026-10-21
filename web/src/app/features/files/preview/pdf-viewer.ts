import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from 'pdfjs-dist';

/** Where the build copies pdf.js's worker (angular.json assets). */
export const PDF_WORKER_SRC = 'pdfjs/pdf.worker.min.mjs';

/** pdf.js, loaded on the first PDF preview only: it is large and most pages never need it. */
export type PdfJs = typeof import('pdfjs-dist');

let pdfjs: Promise<PdfJs> | null = null;

export function loadPdfJs(): Promise<PdfJs> {
  pdfjs ??= import('pdfjs-dist').then((lib) => {
    lib.GlobalWorkerOptions.workerSrc = new URL(PDF_WORKER_SRC, document.baseURI).href;
    return lib;
  });
  return pdfjs;
}

/** Replaces the pdf.js loader, for tests. */
export function setPdfJsLoader(loader: (() => Promise<PdfJs>) | null): void {
  pdfjs = loader ? loader() : null;
}

/**
 * A PDF rendered with pdf.js (SPEC.md §8.2), one page at a time on a canvas as wide as the viewer,
 * with previous/next page. The document is streamed from `src` with the session cookie.
 */
@Component({
  selector: 'app-pdf-viewer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (error()) {
      <p class="error" role="alert" i18n="@@files.preview.pdfFailed">
        The PDF could not be shown. Download it to open it.
      </p>
    } @else {
      <div class="pages" [class.loading]="!pages()">
        <canvas
          #canvas
          [attr.aria-label]="pageLabel()"
          role="img"
          [attr.data-rendered]="rendered() || null"
        ></canvas>
      </div>
      @if (pages(); as total) {
        <nav class="pager" i18n-aria-label="@@files.preview.pages" aria-label="Pages">
          <button
            type="button"
            [disabled]="page() <= 1"
            (click)="go(page() - 1)"
            i18n="@@files.preview.prevPage"
          >
            Previous page
          </button>
          <span class="count" i18n="@@files.preview.pageOf">Page {{ page() }} of {{ total }}</span>
          <button
            type="button"
            [disabled]="page() >= total"
            (click)="go(page() + 1)"
            i18n="@@files.preview.nextPage"
          >
            Next page
          </button>
        </nav>
      } @else {
        <p class="note" role="status" i18n="@@files.preview.pdfLoading">Loading the PDF…</p>
      }
    }
  `,
  styleUrl: './pdf-viewer.scss',
})
export class PdfViewer {
  readonly src = input.required<string>();

  private readonly canvas = viewChild<ElementRef<HTMLCanvasElement>>('canvas');

  protected readonly page = signal(1);
  protected readonly pages = signal<number | null>(null);
  protected readonly error = signal(false);
  /** The page number on the canvas, once drawn. */
  protected readonly rendered = signal<number | null>(null);

  private doc: PDFDocumentProxy | null = null;
  private loading: PDFDocumentLoadingTask | null = null;
  private renderTask: RenderTask | null = null;
  /** Settles when the last started render has finished or stopped after its cancel. */
  private rendering: Promise<void> = Promise.resolve();
  /** Counts render requests; a render whose number is no longer the latest gives up. */
  private renderSeq = 0;

  constructor() {
    effect(() => {
      const src = this.src();
      untracked(() => void this.open(src));
    });
    effect(() => {
      const page = this.page();
      const canvas = this.canvas()?.nativeElement;
      if (this.pages() && canvas) untracked(() => void this.render(page, canvas));
    });
    inject(DestroyRef).onDestroy(() => this.close());
  }

  protected go(page: number): void {
    const total = this.pages();
    if (total && page >= 1 && page <= total) this.page.set(page);
  }

  protected pageLabel(): string {
    const page = this.page();
    return $localize`:@@files.preview.pageImage:Page ${page}:page:`;
  }

  private async open(src: string): Promise<void> {
    this.close();
    this.error.set(false);
    this.pages.set(null);
    this.page.set(1);
    this.rendered.set(null);
    try {
      const lib = await loadPdfJs();
      if (src !== this.src()) return;
      const loading = lib.getDocument({ url: src, withCredentials: true });
      this.loading = loading;
      const doc = await loading.promise;
      // Closed or replaced meanwhile: close() already destroyed this loading task.
      if (this.loading !== loading) return;
      this.doc = doc;
      this.pages.set(doc.numPages);
    } catch {
      if (src === this.src()) this.error.set(true);
    }
  }

  private async render(number: number, canvas: HTMLCanvasElement): Promise<void> {
    const doc = this.doc;
    if (!doc) return;
    // Only the latest request draws: pdf.js refuses a second render on the same canvas.
    const seq = ++this.renderSeq;
    this.renderTask?.cancel();
    try {
      const page = await doc.getPage(number);
      if (seq !== this.renderSeq) return;
      await this.rendering;
      if (seq !== this.renderSeq) return;
      const width = canvas.parentElement?.clientWidth || 800;
      const base = page.getViewport({ scale: 1 });
      const ratio = window.devicePixelRatio || 1;
      const viewport = page.getViewport({ scale: (width / base.width) * ratio });
      canvas.width = Math.floor(viewport.width);
      canvas.height = Math.floor(viewport.height);
      canvas.style.width = `${Math.floor(viewport.width / ratio)}px`;
      const task = page.render({ canvas, viewport });
      this.renderTask = task;
      this.rendering = task.promise.then(
        () => undefined,
        () => undefined,
      );
      await task.promise;
      if (seq === this.renderSeq) this.rendered.set(number);
    } catch (error) {
      // A render cancelled for the next page, or a stale one, is no failure.
      if (
        seq === this.renderSeq &&
        (error as { name?: string } | null)?.name !== 'RenderingCancelledException'
      ) {
        this.error.set(true);
      }
    }
  }

  private close(): void {
    this.renderSeq++;
    this.renderTask?.cancel();
    this.renderTask = null;
    void this.loading?.destroy();
    this.loading = null;
    this.doc = null;
  }
}
