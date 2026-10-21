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
});

describe('MAIL_FRAME_CSP', () => {
  it('forbids scripts and loads images only from the app or data URLs', () => {
    expect(MAIL_FRAME_CSP).toContain("default-src 'none'");
    expect(MAIL_FRAME_CSP).toContain("script-src 'none'");
    expect(MAIL_FRAME_CSP).toContain("img-src 'self' data:");
    expect(MAIL_FRAME_CSP).not.toContain('http');
  });
});
