import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAIL_FRAME_SANDBOX, MailFrame, mailDocument, mailFrameCsp } from './mail-frame';

describe('MailFrame', () => {
  let fixture: ComponentFixture<MailFrame>;

  beforeEach(() => {
    fixture = TestBed.createComponent(MailFrame);
    fixture.componentRef.setInput('html', '<p>Hi</p>');
    fixture.detectChanges();
  });

  const frame = () => (fixture.nativeElement as HTMLElement).querySelector('iframe')!;

  it('sandboxes the frame without allow-scripts or allow-forms', () => {
    expect(frame().getAttribute('sandbox')).toBe(MAIL_FRAME_SANDBOX);
    const tokens = frame().getAttribute('sandbox')!.split(' ');
    expect(tokens).not.toContain('allow-scripts');
    expect(tokens).not.toContain('allow-forms');
    expect(tokens).not.toContain('allow-top-navigation');
    expect(frame().getAttribute('referrerpolicy')).toBe('no-referrer');
  });

  it('puts the body into a document with the CSP', () => {
    expect(frame().srcdoc).toBe(mailDocument('<p>Hi</p>'));
    expect(frame().srcdoc).toContain(
      `<meta http-equiv="Content-Security-Policy" content="${mailFrameCsp(location.origin)}">`,
    );
  });

  describe('the CSP', () => {
    /** The directives of a CSP by name, each with its source list. */
    const directives = (csp: string) =>
      new Map(
        csp.split(';').map((part) => {
          const [name, ...sources] = part.trim().split(/\s+/);
          return [name, sources] as const;
        }),
      );
    const csp = directives(mailFrameCsp('https://cockpit.example.test'));

    it('names the app origin next to self for images and fonts (Firefox srcdoc)', () => {
      expect(csp.get('img-src')).toEqual(["'self'", 'https://cockpit.example.test', 'data:']);
      expect(csp.get('font-src')).toEqual(["'self'", 'https://cockpit.example.test', 'data:']);
    });

    it('allows no foreign host, no scripts and nothing by default', () => {
      expect(csp.get('default-src')).toEqual(["'none'"]);
      expect(csp.get('script-src')).toEqual(["'none'"]);
      expect(csp.get('frame-src')).toEqual(["'none'"]);
      expect(csp.get('form-action')).toEqual(["'none'"]);
      expect(csp.get('base-uri')).toEqual(["'none'"]);
      expect(csp.get('style-src')).toEqual(["'unsafe-inline'"]);
      const hosts = [...csp.values()]
        .flat()
        .filter((source) => /^[a-z][a-z0-9+.-]*:\/\/|^\*/.test(source));
      expect(hosts).toEqual(['https://cockpit.example.test', 'https://cockpit.example.test']);
      expect([...csp.keys()]).not.toContain('connect-src');
    });

    it('allows exactly the listed sources in every directive (no other host or keyword)', () => {
      const origin = 'https://cockpit.example.test';
      expect(Object.fromEntries(csp)).toEqual({
        'default-src': ["'none'"],
        'img-src': ["'self'", origin, 'data:'],
        'style-src': ["'unsafe-inline'"],
        'font-src': ["'self'", origin, 'data:'],
        'script-src': ["'none'"],
        'frame-src': ["'none'"],
        'form-action': ["'none'"],
        'base-uri': ["'none'"],
      });
    });

    it('keeps the port of a development origin', () => {
      const dev = directives(mailFrameCsp('http://localhost:4200'));
      expect(dev.get('img-src')).toEqual(["'self'", 'http://localhost:4200', 'data:']);
      expect(dev.get('font-src')).toEqual(["'self'", 'http://localhost:4200', 'data:']);
    });

    it('uses the page origin by default', () => {
      expect(mailFrameCsp()).toBe(mailFrameCsp(location.origin));
      expect(directives(mailFrameCsp()).get('img-src')).toContain(location.origin);
    });

    it('names the page origin in the CSP of the rendered frame', () => {
      const meta = new DOMParser()
        .parseFromString(frame().srcdoc, 'text/html')
        .querySelector('meta[http-equiv="Content-Security-Policy"]');
      const rendered = directives(meta?.getAttribute('content') ?? '');
      expect(rendered.get('img-src')).toEqual(["'self'", location.origin, 'data:']);
      expect(rendered.get('font-src')).toEqual(["'self'", location.origin, 'data:']);
      expect(rendered.get('default-src')).toEqual(["'none'"]);
    });

    it('builds the document with a given origin', () => {
      const doc = mailDocument('<p>Hi</p>', 'https://other.example.test');
      expect(doc).toContain(`content="${mailFrameCsp('https://other.example.test')}"`);
      expect(doc).not.toContain(location.origin);
    });
  });

  it('follows a changed body', () => {
    fixture.componentRef.setInput('html', '<p>Other</p>');
    fixture.detectChanges();
    expect(frame().srcdoc).toContain('<p>Other</p>');
  });

  it('names the frame', () => {
    fixture.componentRef.setInput('label', 'Quarterly report');
    fixture.detectChanges();
    expect(frame().title).toBe('Quarterly report');
  });

  it('has an accessible name by default', () => {
    expect(frame().title).toBe('Message');
  });

  describe('fitting to the content', () => {
    let observers: { callback: () => void; observed: Element[]; disconnected: boolean }[];

    beforeEach(() => {
      observers = [];
      vi.stubGlobal(
        'ResizeObserver',
        class {
          readonly record: (typeof observers)[number];
          constructor(callback: () => void) {
            this.record = { callback, observed: [], disconnected: false };
            observers.push(this.record);
          }
          observe(target: Element) {
            this.record.observed.push(target);
          }
          disconnect() {
            this.record.disconnected = true;
          }
        },
      );
    });
    afterEach(() => vi.unstubAllGlobals());

    /** Gives the frame's document a content height (jsdom does no layout). */
    function contentHeight(px: number) {
      Object.defineProperty(frame().contentDocument!.body, 'scrollHeight', {
        configurable: true,
        get: () => px,
      });
    }

    /** The root element's scrollHeight, which in standards mode never drops below the frame. */
    function rootHeight(px: number) {
      Object.defineProperty(frame().contentDocument!.documentElement, 'scrollHeight', {
        configurable: true,
        get: () => px,
      });
    }

    it('shrinks again when the content gets shorter (measures the body, not the root)', () => {
      contentHeight(900);
      rootHeight(900);
      frame().dispatchEvent(new Event('load'));
      expect(frame().style.height).toBe('900px');

      // The window widens and the text reflows into fewer lines; the root stays at the frame height.
      contentHeight(300);
      observers[0].callback();
      expect(frame().style.height).toBe('300px');
    });

    it('grows to its document height on load and follows later size changes', () => {
      expect(frame().contentDocument?.body).toBeTruthy();
      contentHeight(420);
      frame().dispatchEvent(new Event('load'));
      expect(frame().style.height).toBe('420px');
      expect(observers).toHaveLength(1);
      expect(observers[0].observed).toEqual([frame().contentDocument!.body]);

      // A late image makes the content taller.
      contentHeight(900);
      observers[0].callback();
      expect(frame().style.height).toBe('900px');
    });

    it('observes only the latest document after another load, and stops on destroy', () => {
      frame().dispatchEvent(new Event('load'));
      frame().dispatchEvent(new Event('load'));
      expect(observers).toHaveLength(2);
      expect(observers[0].disconnected).toBe(true);
      expect(observers[1].disconnected).toBe(false);
      fixture.destroy();
      expect(observers[1].disconnected).toBe(true);
    });

    it('still sizes the frame once where ResizeObserver is missing', () => {
      vi.stubGlobal('ResizeObserver', undefined);
      contentHeight(300);
      frame().dispatchEvent(new Event('load'));
      expect(frame().style.height).toBe('300px');
    });
  });
});
