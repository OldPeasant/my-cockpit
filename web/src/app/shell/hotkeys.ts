/** Global shortcuts of the shell (SPEC.md §4.4, §4.5, §9.1). */
export type ShellAction = 'palette' | 'capture' | 'shortcuts';

export interface ShortcutInfo {
  readonly keys: readonly string[];
  readonly description: string;
}

/** Shown by the `?` overview; keep in sync with {@link shellActionFor}. */
export const SHELL_SHORTCUTS: readonly ShortcutInfo[] = [
  {
    keys: ['Ctrl+K'],
    description: $localize`:@@shortcuts.palette:Search and run commands`,
  },
  {
    keys: ['Q', 'Ctrl+Shift+Space'],
    description: $localize`:@@shortcuts.capture:Capture to the inbox`,
  },
  { keys: ['?'], description: $localize`:@@shortcuts.overview:Show keyboard shortcuts` },
];

/** True when the key press is meant as text: inputs, text areas, selects and editable content. */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  if (target.isContentEditable) {
    return true;
  }
  const tag = target.tagName;
  if (tag === 'TEXTAREA' || tag === 'SELECT') {
    return true;
  }
  if (tag === 'INPUT') {
    const type = (target as HTMLInputElement).type;
    return !['button', 'checkbox', 'radio', 'reset', 'submit', 'range', 'color', 'file'].includes(
      type,
    );
  }
  return false;
}

/**
 * Maps a keydown to a shell action, or `null`. `Ctrl+K` and `Ctrl+Shift+Space` also work while
 * typing (they never produce text); the single-key shortcuts `Q` and `?` are ignored in inputs.
 */
export function shellActionFor(event: KeyboardEvent): ShellAction | null {
  if (event.defaultPrevented || event.isComposing || event.repeat) {
    return null;
  }
  const key = event.key;
  const ctrl = event.ctrlKey || event.metaKey;
  if (ctrl && !event.shiftKey && !event.altKey && key.toLowerCase() === 'k') {
    return 'palette';
  }
  if (ctrl && event.shiftKey && !event.altKey && (event.code === 'Space' || key === ' ')) {
    return 'capture';
  }
  if (ctrl || event.altKey || isTypingTarget(event.target)) {
    return null;
  }
  if (!event.shiftKey && key.toLowerCase() === 'q') {
    return 'capture';
  }
  if (key === '?') {
    return 'shortcuts';
  }
  return null;
}
