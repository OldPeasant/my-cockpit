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

  it.each([
    ['AltGr on Windows (Ctrl+Alt)', { ctrlKey: true, altKey: true }],
    ['AltGr reported as AltGraph', { modifierAltGraph: true }],
    [
      'AltGr reported as AltGraph with Ctrl+Alt',
      { ctrlKey: true, altKey: true, modifierAltGraph: true },
    ],
    ['Option on macOS', { altKey: true }],
    ['Option and Shift on macOS', { altKey: true, shiftKey: true }],
  ])('takes # and ! typed with %s', (_, init: KeyboardEventInit) => {
    expect(mailShortcutFor(key('#', init))).toBe('delete');
    expect(mailShortcutFor(key('!', init))).toBe('spam');
  });

  it.each([
    ['Ctrl', { ctrlKey: true }],
    ['Meta', { metaKey: true }],
    ['Meta+Alt', { metaKey: true, altKey: true }],
    ['Ctrl+Shift', { ctrlKey: true, shiftKey: true }],
  ])('ignores # and ! in a %s chord', (_, init: KeyboardEventInit) => {
    expect(mailShortcutFor(key('#', init))).toBeNull();
    expect(mailShortcutFor(key('!', init))).toBeNull();
  });

  it('ignores letter shortcuts typed with AltGr or Option', () => {
    expect(mailShortcutFor(key('e', { ctrlKey: true, altKey: true }))).toBeNull();
    expect(mailShortcutFor(key('e', { modifierAltGraph: true }))).toBeNull();
    expect(mailShortcutFor(key('j', { altKey: true }))).toBeNull();
  });

  it('does not repeat # typed with AltGr while held', () => {
    expect(mailShortcutFor(key('#', { ctrlKey: true, altKey: true, repeat: true }))).toBeNull();
  });

  it('allows Shift, which the symbol keys need', () => {
    expect(mailShortcutFor(key('#', { shiftKey: true }))).toBe('delete');
  });

  it('ignores composing keys', () => {
    expect(mailShortcutFor(key('j', { isComposing: true }))).toBeNull();
  });

  it('repeats j/k while held, but never an action or selection', () => {
    expect(mailShortcutFor(key('j', { repeat: true }))).toBe('next');
    expect(mailShortcutFor(key('k', { repeat: true }))).toBe('previous');
    for (const pressed of ['x', 'e', '#', 'u', 'I', 's', 'v', '!', 'Escape']) {
      expect(mailShortcutFor(key(pressed, { repeat: true }))).toBeNull();
    }
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
