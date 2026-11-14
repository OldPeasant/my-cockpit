import { isTypingTarget, ShortcutInfo } from '../../../shell/hotkeys';

/** What a key does in the mail list or the read view. */
export type MailShortcut =
  | 'next'
  | 'previous'
  | 'select'
  | 'archive'
  | 'delete'
  | 'unread'
  | 'read'
  | 'flag'
  | 'move'
  | 'spam'
  | 'clear'
  | 'open'
  | 'reply'
  | 'replyAll'
  | 'forward'
  | 'compose'
  | 'nextAction';

/** Gmail-like keys (SPEC.md §7.3); the `?` overview shows them through {@link MAIL_LIST_HELP} and {@link MAIL_READ_HELP}. */
export const MAIL_SHORTCUTS: readonly { key: string; shortcut: MailShortcut }[] = [
  { key: 'j', shortcut: 'next' },
  { key: 'k', shortcut: 'previous' },
  { key: 'x', shortcut: 'select' },
  { key: 'e', shortcut: 'archive' },
  { key: '#', shortcut: 'delete' },
  { key: 'u', shortcut: 'unread' },
  { key: 'I', shortcut: 'read' },
  { key: 's', shortcut: 'flag' },
  { key: 'v', shortcut: 'move' },
  { key: '!', shortcut: 'spam' },
  { key: 'Escape', shortcut: 'clear' },
  { key: 'o', shortcut: 'open' },
  { key: 'r', shortcut: 'reply' },
  { key: 'a', shortcut: 'replyAll' },
  { key: 'f', shortcut: 'forward' },
  { key: 'c', shortcut: 'compose' },
  { key: 'T', shortcut: 'nextAction' },
];

const BY_KEY = new Map(MAIL_SHORTCUTS.map(({ key, shortcut }) => [key, shortcut]));
/** Shortcuts that auto-repeat while their key is held: moving the cursor, never an action. */
const REPEATABLE: ReadonlySet<MailShortcut> = new Set(['next', 'previous']);
/** Symbol keys that some layouts only type with AltGr or Option (e.g. `#` is AltGr+3 on Swiss German). */
const SYMBOLS: ReadonlySet<string> = new Set(['#', '!']);

/**
 * Maps a keydown to a mail list shortcut, or `null`: never while typing, with Ctrl/Alt/Meta, on an
 * already handled key, or on a repeated key other than `j`/`k`. Keys are case-sensitive (`I` is
 * Shift+i, as in Gmail). The symbol keys `#` and `!` are also taken when typed with AltGr (which
 * Windows reports as Ctrl+Alt) or with Option on macOS (Alt without Ctrl/Meta).
 */
export function mailShortcutFor(event: KeyboardEvent): MailShortcut | null {
  if (event.defaultPrevented || event.isComposing) return null;
  if (isTypingTarget(event.target)) return null;
  if (hasModifier(event) && !(SYMBOLS.has(event.key) && typedWithAltGraph(event))) return null;
  const shortcut = BY_KEY.get(event.key) ?? null;
  return shortcut && event.repeat && !REPEATABLE.has(shortcut) ? null : shortcut;
}

function hasModifier(event: KeyboardEvent): boolean {
  return event.ctrlKey || event.altKey || event.metaKey || event.getModifierState('AltGraph');
}

/** Whether the modifiers are those of AltGr (Windows, Linux) or Option (macOS), not a chord. */
function typedWithAltGraph(event: KeyboardEvent): boolean {
  if (event.metaKey) return false;
  return event.getModifierState('AltGraph') || event.altKey;
}

const DESCRIPTIONS: Record<MailShortcut, string> = {
  next: $localize`:@@mailShortcuts.next:Next message`,
  previous: $localize`:@@mailShortcuts.previous:Previous message`,
  select: $localize`:@@mailShortcuts.select:Select or unselect the message`,
  archive: $localize`:@@mailShortcuts.archive:Archive`,
  delete: $localize`:@@mailShortcuts.delete:Delete`,
  unread: $localize`:@@mailShortcuts.unread:Mark as unread`,
  read: $localize`:@@mailShortcuts.read:Mark as read`,
  flag: $localize`:@@mailShortcuts.flag:Star or unstar`,
  move: $localize`:@@mailShortcuts.move:Move to folder`,
  spam: $localize`:@@mailShortcuts.spam:Mark as spam`,
  clear: $localize`:@@mailShortcuts.clear:Clear the selection`,
  open: $localize`:@@mailShortcuts.open:Open the message`,
  reply: $localize`:@@mailShortcuts.reply:Reply`,
  replyAll: $localize`:@@mailShortcuts.replyAll:Reply all`,
  forward: $localize`:@@mailShortcuts.forward:Forward`,
  compose: $localize`:@@mailShortcuts.compose:Write a new message`,
  nextAction: $localize`:@@mailShortcuts.nextAction:Next action & archive`,
};

function help(shortcuts: readonly MailShortcut[]): readonly ShortcutInfo[] {
  return shortcuts.map((shortcut) => ({
    keys: MAIL_SHORTCUTS.filter((entry) => entry.shortcut === shortcut).map((entry) => entry.key),
    description: DESCRIPTIONS[shortcut],
  }));
}

/** The keys of the mail list, for the `?` overview. */
export const MAIL_LIST_HELP: readonly ShortcutInfo[] = [
  ...help([
    'next',
    'previous',
    'open',
    'select',
    'clear',
    'archive',
    'delete',
    'spam',
    'unread',
    'read',
    'flag',
    'move',
    'nextAction',
    'compose',
  ]),
  { keys: ['/'], description: $localize`:@@mailShortcuts.search:Search mail` },
];

/** The keys of the read view, for the `?` overview. */
export const MAIL_READ_HELP: readonly ShortcutInfo[] = help([
  'reply',
  'replyAll',
  'forward',
  'compose',
  'archive',
  'delete',
  'spam',
  'unread',
  'flag',
  'move',
  'nextAction',
  'next',
  'previous',
]);
