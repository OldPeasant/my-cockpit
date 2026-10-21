/** How a file is previewed in the browser (SPEC.md §8.2); `null`: no preview, only download. */
export type PreviewKind = 'image' | 'pdf' | 'text' | 'markdown' | 'audio' | 'video';

/** Images the server makes thumbnails of and serves inline (`/files/{id}/thumbnail`, `/preview`). */
const IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif']);

/** Non-`text/*` types the server previews as text. */
const TEXT_TYPES = new Set([
  'application/json',
  'application/xml',
  'application/yaml',
  'application/x-yaml',
  'application/x-sh',
]);

const MARKDOWN_TYPES = new Set(['text/markdown', 'text/x-markdown']);

/**
 * The preview for a file of `mime` (parameters such as `; charset=utf-8` ignored), matching what the
 * server's `/files/{id}/preview` serves. A Markdown file stored as `text/plain` is recognised by
 * its `name`.
 */
export function previewKind(mime: string | null | undefined, name = ''): PreviewKind | null {
  const type = (mime ?? '').split(';')[0].trim().toLowerCase();
  if (IMAGE_TYPES.has(type)) return 'image';
  if (type === 'application/pdf') return 'pdf';
  if (type.startsWith('audio/')) return 'audio';
  if (type.startsWith('video/')) return 'video';
  if (MARKDOWN_TYPES.has(type)) return 'markdown';
  if (type.startsWith('text/') || TEXT_TYPES.has(type)) {
    return type === 'text/plain' && /\.(md|markdown)$/i.test(name) ? 'markdown' : 'text';
  }
  return null;
}

/** Whether the server has thumbnails for files of `mime`. */
export function hasThumbnail(mime: string | null | undefined): boolean {
  return previewKind(mime) === 'image';
}
