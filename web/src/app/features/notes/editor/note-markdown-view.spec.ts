import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { NotificationService, provideHttp } from '../../../core/http';
import { InlineLinkInfo } from './note-markdown';
import { NoteMarkdownView } from './note-markdown-view';

const TASK_ID = '0192f0c4-0000-7000-8000-0000000000a1';
const CONTACT_ID = '0192f0c4-0000-7000-8000-0000000000b1';
const FILE_ID = '0192f0c4-0000-7000-8000-0000000000f1';

@Component({
  imports: [NoteMarkdownView],
  template: `<app-note-markdown-view
    [source]="source()"
    [links]="links()"
    [readonly]="readonly()"
    (checklistToggle)="toggles.push($event)"
  />`,
})
class Host {
  readonly source = signal('');
  readonly links = signal<ReadonlyMap<string, InlineLinkInfo>>(new Map());
  readonly readonly = signal(false);
  readonly toggles: number[] = [];
}

describe('NoteMarkdownView', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let navigate: ReturnType<typeof vi.spyOn>;

  function create(source: string, links: [string, InlineLinkInfo][] = [], readonly = false): void {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    host.source.set(source);
    host.links.set(new Map(links));
    host.readonly.set(readonly);
    fixture.detectChanges();
  }

  function view(): HTMLElement {
    return (fixture.nativeElement as HTMLElement).querySelector('app-note-markdown-view')!;
  }

  /** Clicks `target`; the result tells whether the view took over the click (prevented its default). */
  function click(target: Element, init: MouseEventInit = {}): { defaultPrevented: boolean } {
    const event = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0, ...init });
    let prevented = false;
    // Seen after the view's own handler; then stop jsdom from following links it cannot load.
    const after = (e: Event) => {
      prevented = e.defaultPrevented;
      e.preventDefault();
    };
    const root = fixture.nativeElement as HTMLElement;
    root.addEventListener('click', after);
    try {
      target.dispatchEvent(event);
    } finally {
      root.removeEventListener('click', after);
    }
    return { defaultPrevented: prevented };
  }

  const chipInfo: [string, InlineLinkInfo] = [
    `TASK:${TASK_ID}`,
    { title: 'Book flights', deepLink: `/tasks/${TASK_ID}` },
  ];

  it('reports the source line of a clicked checklist item', () => {
    create('Trip\n\n- [ ] Passport\n- [x] Charger');
    const boxes = view().querySelectorAll<HTMLInputElement>('input[type=checkbox]');
    click(boxes[1]);
    click(boxes[0]);
    expect(host.toggles).toEqual([3, 2]);
  });

  it('read-only: disables the checkboxes and reports no toggles', () => {
    create('- [ ] Passport\n- [x] Charger', [], true);
    const boxes = view().querySelectorAll<HTMLInputElement>('input[type=checkbox]');
    expect(boxes).toHaveLength(2);
    expect([...boxes].every((box) => box.disabled)).toBe(true);
    expect([...boxes].map((box) => box.checked)).toEqual([false, true]);
    click(boxes[0]);
    expect(host.toggles).toEqual([]);
  });

  it('does not report clicks on checkboxes inside a quote', () => {
    create('> - [ ] quoted');
    const box = view().querySelector<HTMLInputElement>('blockquote input')!;
    expect(box.disabled).toBe(true);
    click(box);
    expect(host.toggles).toEqual([]);
  });

  it('opens a chip of a known item in the app', () => {
    create(`See [[task:${TASK_ID}]]`, [chipInfo]);
    const chip = view().querySelector('a.chip')!;
    expect(chip.textContent).toBe('Task Book flights');
    // A click on the inner title span still opens the chip.
    const event = click(chip.querySelector('.chip-title')!);
    expect(event.defaultPrevented).toBe(true);
    expect(navigate).toHaveBeenCalledWith(`/tasks/${TASK_ID}`);
  });

  it('leaves modified and non-primary clicks on a chip to the browser', () => {
    create(`See [[task:${TASK_ID}]]`, [chipInfo]);
    const chip = view().querySelector('a.chip')!;
    for (const init of [{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { button: 1 }]) {
      const event = click(chip, init);
      expect(event.defaultPrevented).toBe(false);
    }
    expect(navigate).not.toHaveBeenCalled();
  });

  it('shows an unknown or unavailable item as a chip with its type only, not a link', () => {
    create(`Call [[contact:${CONTACT_ID}]]`);
    const chip = view().querySelector('.chip')!;
    expect(chip.tagName).toBe('SPAN');
    expect(chip.textContent).toBe('Contact');
    click(chip);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('ignores clicks on plain text and external links', () => {
    create('Hello [docs](https://example.org/docs)');
    click(view().querySelector('p')!);
    const link = view().querySelector('a')!;
    expect(link.getAttribute('target')).toBe('_blank');
    const event = click(link);
    expect(event.defaultPrevented).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
    expect(host.toggles).toEqual([]);
  });

  it('shows cockpit-file images through the authenticated preview URL', () => {
    create(`![tent](cockpit-file:${FILE_ID})`);
    const img = view().querySelector('img')!;
    expect(img.getAttribute('src')).toBe(`/api/v1/files/${FILE_ID}/preview`);
    expect(img.getAttribute('alt')).toBe('tent');
  });

  it('renders no script or event handler from the source', () => {
    create('<img src=x onerror="alert(1)"> [x](javascript:alert(1)) ![y](javascript:alert(2))');
    expect(view().querySelector('img')).toBeNull();
    expect(view().querySelector('script')).toBeNull();
    expect(view().querySelector('a')).toBeNull();
    expect(view().textContent).toContain('<img src=x onerror="alert(1)">');
  });

  it('re-renders when the source or the link info changes', () => {
    create(`[[task:${TASK_ID}]]`);
    expect(view().querySelector('.chip')?.textContent).toBe('Task');
    host.links.set(new Map([chipInfo]));
    fixture.detectChanges();
    expect(view().querySelector('a.chip')?.textContent).toBe('Task Book flights');
    host.source.set('- [x] done');
    fixture.detectChanges();
    expect(view().querySelector<HTMLInputElement>('input')?.checked).toBe(true);
  });
});
