import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DropHint } from './drop-hint';
import { FileDrop } from './file-drop';

describe('DropHint', () => {
  it('shows the drop text and is hidden from assistive technology', () => {
    const fixture = TestBed.createComponent(DropHint);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.classList).toContain('drop-hint');
    expect(host.getAttribute('aria-hidden')).toBe('true');
    expect(host.textContent?.trim()).toBe('Drop to upload here');
  });
});

/** A drop target as the files page builds it: the hint shows while files are dragged over. */
@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DropHint, FileDrop],
  template: `<section #drop="appFileDrop" (appFileDrop)="dropped.push($event)">
    <p class="child">Folder contents</p>
    @if (drop.dragging()) {
      <app-drop-hint />
    }
  </section>`,
})
class Target {
  readonly dropped: File[][] = [];
}

describe('FileDrop with DropHint', () => {
  let fixture: ComponentFixture<Target>;

  beforeEach(() => {
    fixture = TestBed.createComponent(Target);
    fixture.detectChanges();
  });

  const section = (): HTMLElement =>
    (fixture.nativeElement as HTMLElement).querySelector('section')!;
  const hint = (): Element | null => section().querySelector('app-drop-hint');

  function dragEvent(
    type: string,
    data: { types: string[]; files?: File[] } | null,
    relatedTarget?: EventTarget | null,
  ): DragEvent {
    const event = new Event(type, { bubbles: true, cancelable: true }) as DragEvent;
    Object.defineProperty(event, 'dataTransfer', {
      value: data && { dropEffect: 'none', ...data },
    });
    if (relatedTarget !== undefined) {
      Object.defineProperty(event, 'relatedTarget', { value: relatedTarget });
    }
    return event;
  }

  function dispatch(event: Event): Event {
    section().dispatchEvent(event);
    fixture.detectChanges();
    return event;
  }

  it('shows the hint only while files are dragged over, and emits the dropped files', () => {
    expect(hint()).toBeNull();
    expect(dispatch(dragEvent('dragenter', { types: ['Files'] })).defaultPrevented).toBe(true);
    expect(section().classList).toContain('dragging');
    expect(hint()?.textContent?.trim()).toBe('Drop to upload here');

    const receipt = new File(['fictitious receipt'], 'Receipt.pdf', { type: 'application/pdf' });
    const drop = dispatch(dragEvent('drop', { types: ['Files'], files: [receipt] }));
    expect(drop.defaultPrevented).toBe(true);
    expect(hint()).toBeNull();
    expect(fixture.componentInstance.dropped).toEqual([[receipt]]);
  });

  it('hides the hint when the drag leaves the window (no related target)', () => {
    dispatch(dragEvent('dragover', { types: ['Files'] }));
    // Moving onto a child keeps it.
    dispatch(dragEvent('dragleave', { types: ['Files'] }, section().querySelector('.child')));
    expect(hint()).not.toBeNull();
    dispatch(dragEvent('dragleave', { types: ['Files'] }, null));
    expect(hint()).toBeNull();
  });

  it('ignores drags without a data transfer and drops without files', () => {
    expect(dispatch(dragEvent('dragover', null)).defaultPrevented).toBe(false);
    expect(hint()).toBeNull();

    dispatch(dragEvent('dragover', { types: ['Files'] }));
    // A drop whose files the browser withholds still ends the hint, but emits nothing.
    const drop = dispatch(dragEvent('drop', { types: ['Files'], files: [] }));
    expect(drop.defaultPrevented).toBe(false);
    expect(hint()).toBeNull();
    expect(fixture.componentInstance.dropped).toEqual([]);
  });
});
