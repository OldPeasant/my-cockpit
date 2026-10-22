import { BookmarkResponse } from '../../api/generated';

/** The host of `url` without `www.`, or the URL itself when it cannot be parsed. */
export function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

/**
 * The URL typed into the add field as the absolute http(s) URL the server takes: a bare
 * `example.org/page` gets `https://`. `null` when it is not a web address.
 */
export function normalizeUrl(input: string): string | null {
  const text = input.trim();
  if (!text || /\s/.test(text)) return null;
  // `host:port/…` has no scheme; `mailto:…` and the like have one, but no `//`.
  const hasScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(text);
  if (!hasScheme && /^[a-z][a-z0-9+.-]*:(?!\d)/i.test(text)) return null;
  const withScheme = hasScheme ? text : `https://${text}`;
  try {
    const url = new URL(withScheme);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
    if (!url.hostname.includes('.') && url.hostname !== 'localhost') return null;
    return withScheme;
  } catch {
    return null;
  }
}

/** Tags typed as `reading, #rust  kotlin`: split at commas and spaces, `#` removed, each once. */
export function parseTags(input: string): string[] {
  const tags: string[] = [];
  for (const raw of input.split(/[\s,]+/)) {
    const tag = raw.replace(/^#+/, '').trim();
    if (tag && !tags.some((t) => t.toLowerCase() === tag.toLowerCase())) tags.push(tag);
  }
  return tags;
}

/** Whether the bookmark is in the read-later queue: in the list and not read yet. */
export function inQueue(bookmark: BookmarkResponse): boolean {
  return bookmark.readLater && !bookmark.readAt;
}

/** Only `http(s)` icons are shown; anything else could be a `javascript:` or tracking scheme. */
export function faviconOf(bookmark: BookmarkResponse): string | null {
  return bookmark.favicon && /^https?:\/\//i.test(bookmark.favicon) ? bookmark.favicon : null;
}
