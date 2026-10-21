import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NoteResponse } from '../../../api/generated';
import { NoteCard } from './note-card';

function note(fields: Partial<NoteResponse> = {}): NoteResponse {
  return {
    id: '0192f0c4-0000-7000-8000-0000000000a1',
    title: 'Packing list',
    displayTitle: 'Packing list',
    bodyMd: '- [ ] Passport\n- [x] **Sunscreen**',
    tags: ['Holiday', 'Travel'],
    pinned: false,
    archived: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-03T08:00:00Z',
    version: 1,
    ...fields,
  };
}

describe('NoteCard', () => {
  let fixture: ComponentFixture<NoteCard>;
  let element: HTMLElement;

  function render(value: NoteResponse, inputs: Record<string, unknown> = {}): void {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    fixture = TestBed.createComponent(NoteCard);
    fixture.componentRef.setInput('note', value);
    for (const [name, input] of Object.entries(inputs)) fixture.componentRef.setInput(name, input);
    fixture.detectChanges();
    element = fixture.nativeElement;
  }

  const pin = () => element.querySelector<HTMLButtonElement>('button.pin')!;
  const archive = () => element.querySelector<HTMLButtonElement>('button.archive')!;

  it('shows the title, plain-text excerpt, tags and last change, linking to the editor', () => {
    render(note());

    expect(element.querySelector('.title')?.textContent?.trim()).toBe('Packing list');
    expect(element.querySelector('.text')?.textContent).toBe('☐ Passport\n☑ Sunscreen');
    const tags = [...element.querySelectorAll('.note-tags li')].map((li) => li.textContent);
    expect(tags).toEqual(['#Holiday', '#Travel']);
    const time = element.querySelector('time.updated')!;
    expect(time.getAttribute('datetime')).toBe('2026-10-03T08:00:00Z');
    expect(time.textContent?.trim()).toBe('Oct 3, 2026');
    expect(element.querySelector('a.open')?.getAttribute('href')).toBe(
      '/notes/0192f0c4-0000-7000-8000-0000000000a1',
    );
  });

  it('leaves out the text and tag list when the note has neither', () => {
    render(
      note({ title: undefined, displayTitle: 'Only a line', bodyMd: 'Only a line', tags: [] }),
    );

    expect(element.querySelector('.title')?.textContent?.trim()).toBe('Only a line');
    expect(element.querySelector('.text')).toBeNull();
    expect(element.querySelector('.note-tags')).toBeNull();
  });

  it('labels the buttons to pin and archive an unpinned, active note', () => {
    render(note());

    expect(pin().getAttribute('aria-pressed')).toBe('false');
    expect(pin().getAttribute('aria-label')).toBe('Pin Packing list');
    expect(pin().title).toBe('Pin Packing list');
    expect(archive().getAttribute('aria-label')).toBe('Archive Packing list');
    expect(archive().title).toBe('Archive Packing list');
  });

  it('labels the buttons to unpin and unarchive a pinned, archived note', () => {
    render(note({ pinned: true, archived: true }));

    expect(pin().getAttribute('aria-pressed')).toBe('true');
    expect(pin().getAttribute('aria-label')).toBe('Unpin Packing list');
    expect(archive().getAttribute('aria-label')).toBe('Unarchive Packing list');
  });

  it('updates its labels when the note changes', () => {
    render(note());
    fixture.componentRef.setInput('note', note({ pinned: true, displayTitle: 'Trip' }));
    fixture.detectChanges();

    expect(pin().getAttribute('aria-label')).toBe('Unpin Trip');
    expect(element.querySelector('.title')?.textContent?.trim()).toBe('Trip');
  });

  it('emits pin and archive when their buttons are pressed', () => {
    render(note());
    const pinned = vi.fn();
    const archived = vi.fn();
    fixture.componentInstance.pin.subscribe(pinned);
    fixture.componentInstance.archive.subscribe(archived);

    pin().click();
    expect(pinned).toHaveBeenCalledTimes(1);
    expect(archived).not.toHaveBeenCalled();

    archive().click();
    expect(archived).toHaveBeenCalledTimes(1);
    expect(pinned).toHaveBeenCalledTimes(1);
  });

  it('disables both buttons and emits nothing while busy', () => {
    render(note(), { busy: true });
    const pinned = vi.fn();
    const archived = vi.fn();
    fixture.componentInstance.pin.subscribe(pinned);
    fixture.componentInstance.archive.subscribe(archived);

    expect(pin().disabled).toBe(true);
    expect(archive().disabled).toBe(true);
    pin().click();
    archive().click();
    expect(pinned).not.toHaveBeenCalled();
    expect(archived).not.toHaveBeenCalled();
  });

  it('is enabled and not compact by default, compact for the list view', () => {
    render(note());
    expect(pin().disabled).toBe(false);
    expect(element.classList.contains('compact')).toBe(false);

    fixture.componentRef.setInput('compact', true);
    fixture.detectChanges();
    expect(element.classList.contains('compact')).toBe(true);
  });
});
