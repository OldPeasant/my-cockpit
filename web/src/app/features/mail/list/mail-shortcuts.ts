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

/**
 * Maps a keydown to a mail list shortcut, or `null`: never while typing, with Ctrl/Alt/Meta, on a
 * repeated or already handled key. Keys are case-sensitive (`I` is Shift+i, as in Gmail).
 */
export function mailShortcutFor(event: KeyboardEvent): MailShortcut | null {
  if (event.defaultPrevented || event.isComposing || event.repeat) return null;
  if (event.ctrlKey || event.altKey || event.metaKey || isTypingTarget(event.target)) return null;
  return BY_KEY.get(event.key) ?? null;
}
