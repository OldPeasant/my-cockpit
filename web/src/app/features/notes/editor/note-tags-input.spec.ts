import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoteTagsInput } from './note-tags-input';

describe('NoteTagsInput', () => {
  let fixture: ComponentFixture<NoteTagsInput>;
  let element: HTMLElement;
  let changes: string[][];

  function render(tags: string[], inputs: Record<string, unknown> = {}): void {
    fixture = TestBed.createComponent(NoteTagsInput);
    fixture.componentRef.setInput('tags', tags);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    changes = [];
    fixture.componentInstance.tagsChange.subscribe((next) => changes.push(next));
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  }

  const field = () => element.querySelector<HTMLInputElement>('input.tag-input')!;
  const chips = () =>
    [...element.querySelectorAll('.chip span')].map((span) => span.textContent?.trim());
  const removeButtons = () => [...element.querySelectorAll<HTMLButtonElement>('button.remove')];
  const suggestions = () =>
    [...element.querySelectorAll<HTMLOptionElement>('datalist option')].map((o) => o.value);

  function typeText(value: string): void {
    field().value = value;
    field().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function key(name: string): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key: name, cancelable: true });
    field().dispatchEvent(event);
    fixture.detectChanges();
    return event;
  }

  it('shows the tags as chips with labelled remove buttons', () => {
    render(['Holiday', 'Travel']);
    expect(chips()).toEqual(['#Holiday', '#Travel']);
    const [first] = removeButtons();
    expect(first.getAttribute('aria-label')).toBe('Remove tag Holiday');
    expect(first.title).toBe('Remove tag Holiday');
    expect(field().getAttribute('aria-label')).toBe('Add a tag');
  });

  it('removes a tag with its chip button', () => {
    render(['Holiday', 'Travel']);
    removeButtons()[0].click();
    expect(changes).toEqual([['Travel']]);
  });

  it('adds the typed tag on Enter and clears the input, without submitting a form', () => {
    render(['Holiday']);
    typeText('  Lisbon ');
    const event = key('Enter');
    expect(event.defaultPrevented).toBe(true);
    expect(changes).toEqual([['Holiday', 'Lisbon']]);
    expect(field().value).toBe('');
  });

  it('adds the tags before a comma and keeps the text after it', () => {
    render([]);
    typeText('Work, Ideas,Plan');
    expect(changes).toEqual([['Work', 'Ideas']]);
    expect(field().value).toBe('Plan');
  });

  it('adds what is typed when the input loses focus', () => {
    render(['Holiday']);
    typeText('Budget');
    field().dispatchEvent(new Event('blur'));
    expect(changes).toEqual([['Holiday', 'Budget']]);
  });

  it('drops a leading # and ignores empty, whitespace-only and #-only input', () => {
    render([]);
    typeText('##Garden');
    key('Enter');
    expect(changes).toEqual([['Garden']]);

    for (const value of ['', '   ', '#', ' # ']) {
      typeText(value);
      key('Enter');
    }
    expect(changes).toHaveLength(1);
  });

  it('does not add a tag the note already has, regardless of case', () => {
    render(['Holiday']);
    typeText('holiday');
    key('Enter');
    typeText('#HOLIDAY');
    key('Enter');
    expect(changes).toEqual([]);
  });

  it('adds a tag typed twice in one go only once', () => {
    render([]);
    typeText('Work, work,');
    expect(changes).toEqual([['Work']]);
  });

  it('removes the last tag with Backspace in an empty input, not while text is typed', () => {
    render(['Holiday', 'Travel']);
    typeText('Lis');
    key('Backspace');
    expect(changes).toEqual([]);

    typeText('');
    key('Backspace');
    expect(changes).toEqual([['Holiday']]);
  });

  it('does nothing on Backspace without tags', () => {
    render([]);
    key('Backspace');
    expect(changes).toEqual([]);
  });

  it('suggests the known tags the note does not have yet, regardless of case', () => {
    render(['holiday'], { known: ['Holiday', 'Travel', 'Work'] });
    expect(suggestions()).toEqual(['Travel', 'Work']);
    expect(field().getAttribute('list')).toBe(element.querySelector('datalist')?.id);

    fixture.componentRef.setInput('tags', ['holiday', 'work']);
    fixture.detectChanges();
    expect(suggestions()).toEqual(['Travel']);
  });

  it('gives each instance its own suggestion list id', () => {
    render([]);
    const first = element.querySelector('datalist')?.id;
    render([]);
    expect(element.querySelector('datalist')?.id).not.toBe(first);
  });

  it('changes nothing while disabled', () => {
    render(['Holiday'], { disabled: true });
    expect(field().disabled).toBe(true);
    expect(removeButtons()[0].disabled).toBe(true);
    // Calls that reach the component anyway are ignored.
    const component = fixture.componentInstance as unknown as {
      remove(tag: string): void;
      commit(): void;
      onBackspace(): void;
      text: { set(value: string): void };
    };
    component.remove('Holiday');
    component.onBackspace();
    component.text.set('Travel');
    component.commit();
    expect(changes).toEqual([]);
  });
});
