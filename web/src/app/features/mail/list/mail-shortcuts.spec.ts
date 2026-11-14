import { MAIL_LIST_HELP, MAIL_READ_HELP, MAIL_SHORTCUTS, mailShortcutFor } from './mail-shortcuts';

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
    ['o', 'open'],
    ['r', 'reply'],
    ['a', 'replyAll'],
    ['f', 'forward'],
    ['c', 'compose'],
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
      'o',
      'r',
      'a',
      'f',
      'c',
      'T',
    ]);
  });

  it('is case-sensitive: J, E and lowercase i are no shortcuts', () => {
    expect(mailShortcutFor(key('J', { shiftKey: true }))).toBeNull();
    expect(mailShortcutFor(key('E', { shiftKey: true }))).toBeNull();
    expect(mailShortcutFor(key('i'))).toBeNull();
  });

  it('ignores unmapped keys', () => {
    expect(mailShortcutFor(key('b'))).toBeNull();
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

describe('mail shortcut help', () => {
  type Help = readonly { keys: readonly string[]; description: string }[];
  const keysOf = (help: Help) => help.map((entry) => entry.keys);
  const describeKey = (help: Help, k: string) =>
    help.find((entry) => entry.keys.includes(k))?.description;

  it('lists reply, reply all, forward and compose with their keys in the read view', () => {
    expect(describeKey(MAIL_READ_HELP, 'r')).toBe('Reply');
    expect(describeKey(MAIL_READ_HELP, 'a')).toBe('Reply all');
    expect(describeKey(MAIL_READ_HELP, 'f')).toBe('Forward');
    expect(describeKey(MAIL_READ_HELP, 'c')).toBe('Write a new message');
  });

  it('lists the read view keys for marking unread, starring, moving and stepping', () => {
    expect(keysOf(MAIL_READ_HELP)).toEqual([
      ['r'],
      ['a'],
      ['f'],
      ['c'],
      ['e'],
      ['#'],
      ['!'],
      ['u'],
      ['s'],
      ['v'],
      ['T'],
      ['j'],
      ['k'],
    ]);
    expect(describeKey(MAIL_READ_HELP, 'u')).toBe('Mark as unread');
    expect(describeKey(MAIL_READ_HELP, 's')).toBe('Star or unstar');
    expect(describeKey(MAIL_READ_HELP, 'v')).toBe('Move to folder');
    expect(describeKey(MAIL_READ_HELP, 'T')).toBe('Next action & archive');
  });

  it('lists compose and search in the list, but not the read view keys r, a and f', () => {
    const keys = keysOf(MAIL_LIST_HELP).flat();
    expect(describeKey(MAIL_LIST_HELP, 'c')).toBe('Write a new message');
    expect(describeKey(MAIL_LIST_HELP, '/')).toBe('Search mail');
    for (const k of ['j', 'k', 'o', 'x', 'Escape', 'e', '#', '!', 'u', 'I', 's', 'v']) {
      expect(keys).toContain(k);
    }
    for (const k of ['r', 'a', 'f']) expect(keys).not.toContain(k);
  });

  it('describes every entry once, with at least one key', () => {
    for (const help of [MAIL_LIST_HELP, MAIL_READ_HELP]) {
      const descriptions = help.map((entry) => entry.description);
      expect(new Set(descriptions).size).toBe(descriptions.length);
      for (const entry of help) {
        expect(entry.keys.length).toBeGreaterThan(0);
        expect(entry.description.trim()).not.toBe('');
      }
    }
  });
});
