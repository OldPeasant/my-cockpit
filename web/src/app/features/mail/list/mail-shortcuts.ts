import { isTypingTarget } from '../../../shell/hotkeys';

/** What a key does in the mail list. */
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
  | 'clear';

/** Gmail-like keys (SPEC.md §7.3), shown in the list's shortcut help. */
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
