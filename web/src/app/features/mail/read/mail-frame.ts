import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  viewChild,
} from '@angular/core';

/**
 * The Content Security Policy of a mail body: no scripts, no frames, no forms, no connections;
 * images and fonts only from the app itself (the image proxy, `cid:` downloads) or `data:`.
 * Remote images are blocked or proxied by the server already; this is the second line.
 */
export const MAIL_FRAME_CSP =
  "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; font-src 'self' data:; " +
  "script-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'";

/**
 * The tokens of the frame's `sandbox`: no `allow-scripts`, so nothing in the mail runs. Same origin
 * (without scripts it grants the mail nothing) lets image requests carry the session cookie and the
 * view size the frame to its content; links open in a new, unsandboxed tab.
 */
export const MAIL_FRAME_SANDBOX = 'allow-same-origin allow-popups allow-popups-to-escape-sandbox';

/** The document of the frame around the server-sanitized body HTML. */
export function mailDocument(html: string): string {
  return (
    '<!doctype html><html><head><meta charset="utf-8">' +
    `<meta http-equiv="Content-Security-Policy" content="${MAIL_FRAME_CSP}">` +
    '<meta name="referrer" content="no-referrer"><base target="_blank">' +
    '<style>html{color-scheme:light}body{margin:0;padding:16px 20px;background:#fff;color:#1c1b1f;' +
    'font:15px/1.55 system-ui,sans-serif;overflow-wrap:anywhere}img{max-width:100%;height:auto}' +
    'table{max-width:100%}pre{white-space:pre-wrap}blockquote{margin-inline:0 0;padding-inline-start:12px;' +
    'border-inline-start:3px solid #cac4d0;color:#49454f}</style></head><body>' +
    html +
    '</body></html>'
  );
}

/**
 * Renders the sanitized HTML of a mail in a sandboxed `<iframe>` with a strict CSP (SPEC.md §4.1)
 * and grows the frame to the height of its content.
 */
@Component({
  selector: 'app-mail-frame',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // `sandbox` must be static: Angular refuses to bind security-sensitive iframe attributes.
  template: `<iframe
    #frame
    sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"
    referrerpolicy="no-referrer"
    [title]="label()"
    (load)="fit()"
  ></iframe>`,
  styles: `
    :host {
      display: block;
    }

    iframe {
      display: block;
      width: 100%;
      min-height: 8rem;
      border: 0;
      background: #fff;
    }
  `,
})
export class MailFrame {
  /** The sanitized body HTML (from the server). */
  readonly html = input.required<string>();
  /** Accessible name of the frame. */
  readonly label = input($localize`:@@mailRead.body:Message`);

  private readonly frame = viewChild.required<ElementRef<HTMLIFrameElement>>('frame');
  private observer?: ResizeObserver;

  constructor() {
    effect(() => {
      // Set as a property, not bound: Angular's HTML sanitizer would strip the CSP and styles.
      this.frame().nativeElement.srcdoc = mailDocument(this.html());
    });
    inject(DestroyRef).onDestroy(() => this.observer?.disconnect());
  }

  /** Sizes the frame to its document, now and whenever the content changes size (late images). */
  protected fit(): void {
    const frame = this.frame().nativeElement;
    const body = frame.contentDocument?.body;
    if (!body) return;
    const resize = () => {
      const height = frame.contentDocument?.documentElement.scrollHeight ?? 0;
      frame.style.height = `${height}px`;
    };
    resize();
    this.observer?.disconnect();
    if (typeof ResizeObserver !== 'undefined') {
      this.observer = new ResizeObserver(resize);
      this.observer.observe(body);
    }
  }
}
