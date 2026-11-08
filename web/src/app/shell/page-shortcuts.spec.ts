import { Component, DestroyRef, inject } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { SHELL_SHORTCUTS } from './hotkeys';
import { PageShortcuts, ShortcutSection } from './page-shortcuts';
import { ShortcutsDialog } from './shortcuts-dialog';

const NOTES: ShortcutSection = {
  title: 'Notes',
  shortcuts: [{ keys: ['n'], description: 'New note' }],
};
const TASKS: ShortcutSection = {
  title: 'Tasks',
  shortcuts: [
    { keys: ['t', 'Shift+T'], description: 'New task' },
    { keys: ['d'], description: 'Done' },
  ],
};

@Component({ selector: 'app-notes-page', template: '' })
class NotesPage {
  constructor() {
    inject(PageShortcuts).register(NOTES);
  }
}

/** A manual DestroyRef the test destroys when it wants. */
function destroyRef() {
  const callbacks: (() => void)[] = [];
  const ref = {
    onDestroy: (callback: () => void) => {
      callbacks.push(callback);
      return () => undefined;
    },
  } as unknown as DestroyRef;
  return { ref, destroy: () => callbacks.forEach((callback) => callback()) };
}

describe('PageShortcuts', () => {
  let shortcuts: PageShortcuts;

  beforeEach(() => {
    shortcuts = TestBed.inject(PageShortcuts);
  });

  it('has no sections before a page registers one', () => {
    expect(shortcuts.sections()).toEqual([]);
  });

  it("shows a page's section while the page lives (the caller's DestroyRef by default)", () => {
    const fixture = TestBed.createComponent(NotesPage);
    expect(shortcuts.sections()).toEqual([NOTES]);
    fixture.destroy();
    expect(shortcuts.sections()).toEqual([]);
  });

  it('keeps the sections in registration order and removes only the destroyed one', () => {
    const notes = destroyRef();
    const tasks = destroyRef();
    shortcuts.register(NOTES, notes.ref);
    shortcuts.register(TASKS, tasks.ref);
    expect(shortcuts.sections().map((section) => section.title)).toEqual(['Notes', 'Tasks']);
    notes.destroy();
    expect(shortcuts.sections()).toEqual([TASKS]);
    tasks.destroy();
    expect(shortcuts.sections()).toEqual([]);
  });

  it('removes one registration of a section shown twice, not both', () => {
    // Two instances of the same page (e.g. while the router swaps them) register equal sections.
    const first = destroyRef();
    const second = destroyRef();
    const copy: ShortcutSection = { ...NOTES };
    shortcuts.register(NOTES, first.ref);
    shortcuts.register(copy, second.ref);
    first.destroy();
    expect(shortcuts.sections()).toHaveLength(1);
    expect(shortcuts.sections()[0]).toBe(copy);
  });
});

describe('ShortcutsDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: MatDialogRef, useValue: { close: vi.fn() } }],
    });
  });

  function render() {
    const fixture = TestBed.createComponent(ShortcutsDialog);
    fixture.detectChanges();
    return fixture;
  }

  const lists = (root: HTMLElement) =>
    [...root.querySelectorAll('dl.shortcuts')].map((dl) =>
      [...dl.querySelectorAll('dt')].map((dt, i) => ({
        keys: [...dt.querySelectorAll('kbd')].map((kbd) => kbd.textContent?.trim()),
        description: dl.querySelectorAll('dd')[i].textContent?.trim(),
      })),
    );

  it("shows only the shell's shortcuts when no page registered any", () => {
    const root = render().nativeElement as HTMLElement;
    expect(root.querySelectorAll('h3')).toHaveLength(0);
    expect(lists(root)).toEqual([
      SHELL_SHORTCUTS.map((shortcut) => ({
        keys: [...shortcut.keys],
        description: shortcut.description,
      })),
    ]);
  });

  it("shows the shown pages' sections under their titles after the shell's", () => {
    const shortcuts = TestBed.inject(PageShortcuts);
    shortcuts.register(NOTES, destroyRef().ref);
    shortcuts.register(TASKS, destroyRef().ref);
    const root = render().nativeElement as HTMLElement;
    expect([...root.querySelectorAll('h3.section')].map((h) => h.textContent?.trim())).toEqual([
      'Notes',
      'Tasks',
    ]);
    const [, notes, tasks] = lists(root);
    expect(notes).toEqual([{ keys: ['n'], description: 'New note' }]);
    expect(tasks).toEqual([
      { keys: ['t', 'Shift+T'], description: 'New task' },
      { keys: ['d'], description: 'Done' },
    ]);
    // Alternative keys are joined by "or", single keys are not.
    const dts = root.querySelectorAll('dl.shortcuts')[2].querySelectorAll('dt');
    expect(dts[0].querySelector('.or')?.textContent?.trim()).toBe('or');
    expect(dts[1].querySelector('.or')).toBeNull();
  });

  it('follows sections that come and go while it is open', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    const notes = destroyRef();
    TestBed.inject(PageShortcuts).register(NOTES, notes.ref);
    fixture.detectChanges();
    expect(root.querySelector('h3')?.textContent?.trim()).toBe('Notes');
    notes.destroy();
    fixture.detectChanges();
    expect(root.querySelector('h3')).toBeNull();
  });
});
