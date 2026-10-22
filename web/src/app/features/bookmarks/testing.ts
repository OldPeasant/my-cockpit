import { BookmarkResponse } from '../../api/generated';

/** A fictitious bookmark for tests: in the read-later queue, unread, metadata fetched. */
export function bookmark(id: string, fields: Partial<BookmarkResponse> = {}): BookmarkResponse {
  return {
    id,
    url: `https://example.org/articles/${id}`,
    title: `Article ${id}`,
    displayTitle: `Article ${id}`,
    description: 'A fictitious article.',
    tags: [],
    readLater: true,
    metadataStatus: 'FETCHED',
    createdAt: '2026-10-20T08:00:00Z',
    updatedAt: '2026-10-20T08:00:00Z',
    version: 1,
    ...fields,
  };
}
