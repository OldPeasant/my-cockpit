import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAIL_FRAME_CSP, MAIL_FRAME_SANDBOX, MailFrame, mailDocument } from './mail-frame';

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
      `<meta http-equiv="Content-Security-Policy" content="${MAIL_FRAME_CSP}">`,
    );
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
      Object.defineProperty(frame().contentDocument!.documentElement, 'scrollHeight', {
        configurable: true,
        get: () => px,
      });
    }

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

describe('MAIL_FRAME_CSP', () => {
  it('forbids scripts and loads images only from the app or data URLs', () => {
    expect(MAIL_FRAME_CSP).toContain("default-src 'none'");
    expect(MAIL_FRAME_CSP).toContain("script-src 'none'");
    expect(MAIL_FRAME_CSP).toContain("img-src 'self' data:");
    expect(MAIL_FRAME_CSP).not.toContain('http');
  });
});
