import { TestBed } from '@angular/core/testing';
import { ItemTypeIcon, itemTypeLabel } from './item-type';
import { ItemType } from './links.service';

const LABELS: Record<ItemType, string> = {
  TASK: 'Task',
  PROJECT: 'Project',
  NOTE: 'Note',
  EVENT: 'Event',
  EMAIL: 'Email',
  FILE: 'File',
  FOLDER: 'Folder',
  CONTACT: 'Contact',
  BOOKMARK: 'Bookmark',
  JOURNAL_ENTRY: 'Journal entry',
  HABIT: 'Habit',
};

const TYPES = Object.keys(LABELS) as ItemType[];

describe('itemTypeLabel', () => {
  it.each(TYPES)('names %s', (type) => {
    expect(itemTypeLabel(type)).toBe(LABELS[type]);
  });
});

describe('ItemTypeIcon', () => {
  function render(type: ItemType, icon?: string): HTMLElement {
    const fixture = TestBed.createComponent(ItemTypeIcon);
    fixture.componentRef.setInput('type', type);
    if (icon !== undefined) fixture.componentRef.setInput('icon', icon);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('draws a distinct glyph for every item type', () => {
    const glyphs = TYPES.map((type) => render(type).querySelector('path')?.getAttribute('d'));

    for (const glyph of glyphs) expect(glyph).toMatch(/^M/);
    expect(new Set(glyphs).size).toBe(TYPES.length);
  });

  it('is hidden from assistive technology and exposes type and server icon name', () => {
    const host = render('EMAIL', 'mail');

    expect(host.getAttribute('aria-hidden')).toBe('true');
    expect(host.getAttribute('data-type')).toBe('EMAIL');
    expect(host.getAttribute('data-icon')).toBe('mail');
  });

  it('omits data-icon when the server sends no icon name', () => {
    const host = render('TASK');

    expect(host.hasAttribute('data-icon')).toBe(false);
  });
});
