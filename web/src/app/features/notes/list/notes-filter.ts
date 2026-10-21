import { NoteResponse } from '../../../api/generated';

/** At most this many characters of a note's text are shown on its card. */
export const EXCERPT_LENGTH = 400;

/** Pinned notes first, then the most recently changed (as the API orders them). */
export function sortNotes(notes: readonly NoteResponse[]): NoteResponse[] {
  return [...notes].sort(
    (a, b) =>
      Number(b.pinned) - Number(a.pinned) ||
      Date.parse(b.updatedAt) - Date.parse(a.updatedAt) ||
      // UUIDv7 ids grow with time: the later created note first, so the order is stable.
      b.id.localeCompare(a.id),
  );
}

/**
 * Whether `note` matches the search text: every word of `query` must occur (regardless of case) in
 * the title, the text or a tag. An empty query matches every note.
 */
export function matchesQuery(note: NoteResponse, query: string): boolean {
  const words = query.toLocaleLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return true;
  const text = [note.title ?? '', note.displayTitle, note.bodyMd, ...note.tags]
    .join('\n')
    .toLocaleLowerCase();
  return words.every((word) => text.includes(word.replace(/^#/, '')));
}

/** The notes matching `query`, pinned first. */
export function filterNotes(notes: readonly NoteResponse[], query: string): NoteResponse[] {
  return sortNotes(notes.filter((note) => matchesQuery(note, query)));
}

/**
 * The text of a note's card as plain text: Markdown marks and images removed, links reduced to their
 * text. Without a (non-blank) title, the server derives the card's title from the first line that
 * has text once Markdown, images and `[[type:id]]` links are removed, so that line is left out here.
 */
export function excerpt(note: Pick<NoteResponse, 'title' | 'bodyMd'>): string {
  let lines = note.bodyMd.split(/\r?\n/).map(plainLine);
  if (!note.title?.trim()) {
    const first = lines.findIndex((line) => /[^\s•☐☑]/.test(line));
    lines = first < 0 ? [] : lines.slice(first + 1);
  }
  const text = lines
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  return text.length > EXCERPT_LENGTH ? `${text.slice(0, EXCERPT_LENGTH).trimEnd()}…` : text;
}

/** One Markdown line as plain text; list items and checkboxes keep a marker (`•`, `☐`, `☑`). */
function plainLine(line: string): string {
  return line
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/\[\[[a-z_]+:[^\]]+\]\]/gi, '')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^\s*(#{1,6}\s+|>\s?)/, '')
    .replace(/^(\s*)[-*+]\s+\[[xX]\]\s+/, '$1☑ ')
    .replace(/^(\s*)[-*+]\s+\[ \]\s+/, '$1☐ ')
    .replace(/^(\s*)[-*+]\s+/, '$1• ')
    .replace(/[*_`~]+/g, '')
    .trimEnd();
}

/** Tags typed as text, separated by commas or spaces, a leading `#` removed, each once. */
export function parseTags(text: string): string[] {
  const tags = new Map<string, string>();
  for (const raw of text.split(/[,\s]+/)) {
    const tag = raw.replace(/^#+/, '').trim();
    if (tag && !tags.has(tag.toLocaleLowerCase())) tags.set(tag.toLocaleLowerCase(), tag);
  }
  return [...tags.values()];
}
