import { MAIL_SHORTCUTS, mailShortcutFor } from './mail-shortcuts';

function key(key: string, init: KeyboardEventInit = {}, target?: EventTarget): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
  if (target) Object.defineProperty(event, 'target', { value: target });
  return event;
}

describe('mailShortcutFor', () => {
  it.each([
    ['j', 'next'],
    ['k', 'previous'],
    ['x', 'select'],
    ['e', 'archive'],
    ['#', 'delete'],
    ['u', 'unread'],
    ['I', 'read'],
    ['s', 'flag'],
    ['v', 'move'],
    ['!', 'spam'],
    ['Escape', 'clear'],
  ])('maps %s to %s', (pressed, shortcut) => {
    expect(mailShortcutFor(key(pressed))).toBe(shortcut);
  });

  it('lists every mapped key in the shortcut help', () => {
    expect(MAIL_SHORTCUTS.map((s) => s.key)).toEqual([
      'j',
      'k',
      'x',
      'e',
      '#',
      'u',
      'I',
      's',
      'v',
      '!',
      'Escape',
    ]);
  });

  it('is case-sensitive: J, E and lowercase i are no shortcuts', () => {
    expect(mailShortcutFor(key('J', { shiftKey: true }))).toBeNull();
    expect(mailShortcutFor(key('E', { shiftKey: true }))).toBeNull();
    expect(mailShortcutFor(key('i'))).toBeNull();
  });

  it('ignores unmapped keys', () => {
    expect(mailShortcutFor(key('a'))).toBeNull();
    expect(mailShortcutFor(key('Enter'))).toBeNull();
  });

  it.each([{ ctrlKey: true }, { altKey: true }, { metaKey: true }])(
    'ignores keys with a modifier %o',
    (init) => {
      expect(mailShortcutFor(key('e', init))).toBeNull();
    },
  );

  it('allows Shift, which the symbol keys need', () => {
    expect(mailShortcutFor(key('#', { shiftKey: true }))).toBe('delete');
  });

  it('ignores repeated and composing keys', () => {
    expect(mailShortcutFor(key('j', { repeat: true }))).toBeNull();
    expect(mailShortcutFor(key('j', { isComposing: true }))).toBeNull();
  });

  it('ignores a key another handler already handled', () => {
    const event = key('e');
    event.preventDefault();
    expect(mailShortcutFor(event)).toBeNull();
  });

  it('ignores keys typed into text fields, but not on buttons or checkboxes', () => {
    const text = document.createElement('input');
    const area = document.createElement('textarea');
    const editable = document.createElement('div');
    editable.contentEditable = 'true';
    Object.defineProperty(editable, 'isContentEditable', { value: true });
    expect(mailShortcutFor(key('e', {}, text))).toBeNull();
    expect(mailShortcutFor(key('e', {}, area))).toBeNull();
    expect(mailShortcutFor(key('e', {}, editable))).toBeNull();

    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    expect(mailShortcutFor(key('x', {}, checkbox))).toBe('select');
    expect(mailShortcutFor(key('e', {}, document.createElement('button')))).toBe('archive');
  });
});
