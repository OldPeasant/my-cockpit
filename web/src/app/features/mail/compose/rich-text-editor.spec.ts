import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RichTextEditor } from './rich-text-editor';

@Component({
  imports: [RichTextEditor],
  template: `<app-rich-text-editor
    label="Message"
    [html]="html()"
    (changed)="changes.push($event)"
  />`,
})
class Host {
  readonly html = signal(
    '<p>Hello <strong>Ann</strong></p><p><img src="https://x.test/a.png" alt="Logo"></p>',
  );
  readonly changes: string[] = [];
}

describe('RichTextEditor', () => {
  let fixture: ComponentFixture<Host>;

  beforeEach(async () => {
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  afterEach(() => fixture.destroy());

  const editor = () =>
    fixture.debugElement.query((d) => d.componentInstance instanceof RichTextEditor)
      .componentInstance as RichTextEditor;
  const body = () => fixture.nativeElement.querySelector('.ProseMirror') as HTMLElement;

  it('shows the starting content as a labelled text box', () => {
    expect(body().textContent).toContain('Hello Ann');
    expect(body().getAttribute('role')).toBe('textbox');
    expect(body().getAttribute('aria-label')).toBe('Message');
    expect(editor().getHtml()).toContain('<strong>Ann</strong>');
  });

  it('does not load images: they show as their alt text and stay in the HTML', () => {
    expect(body().querySelector('img')).toBeNull();
    expect(body().querySelector('.image-placeholder')?.textContent).toBe('[Logo]');
    expect(editor().getHtml()).toContain('src="https://x.test/a.png"');
  });

  it('applies formatting from the toolbar and reports the new HTML', () => {
    editor().focus();
    const bullets = fixture.nativeElement.querySelector(
      'button[aria-label="Bulleted list"]',
    ) as HTMLButtonElement;
    bullets.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.changes.at(-1)).toContain('<ul>');
    expect(bullets.getAttribute('aria-pressed')).toBe('true');
  });

  it('adds a link from the prompt and removes it with an empty answer', () => {
    editor().focus();
    const link = fixture.nativeElement.querySelector(
      'button[aria-label="Link"]',
    ) as HTMLButtonElement;
    const prompt = vi.spyOn(window, 'prompt').mockReturnValue('https://example.test');
    // Select "Hello".
    const view = (
      editor() as unknown as { editor: { commands: { setTextSelection(r: object): void } } }
    ).editor;
    view.commands.setTextSelection({ from: 1, to: 6 });
    link.click();
    expect(fixture.componentInstance.changes.at(-1)).toContain('href="https://example.test"');

    prompt.mockReturnValue('');
    link.click();
    expect(fixture.componentInstance.changes.at(-1)).not.toContain('href=');

    prompt.mockReturnValue(null);
    const count = fixture.componentInstance.changes.length;
    link.click();
    expect(fixture.componentInstance.changes).toHaveLength(count);
    prompt.mockRestore();
  });
});
