import { JournalEntryResponse, JournalHeatmapResponse } from '../../api/generated';

/** A fictitious journal entry for tests. */
export function journalEntry(
  date: string,
  fields: Partial<JournalEntryResponse> = {},
): JournalEntryResponse {
  return {
    id: `0192f0c4-0000-7000-8000-${date.replaceAll('-', '').padStart(12, '0')}`,
    date,
    displayTitle: `Journal ${date}`,
    bodyMd: 'A quiet day at the lake.',
    tags: [],
    createdAt: `${date}T20:00:00Z`,
    updatedAt: `${date}T20:00:00Z`,
    version: 1,
    ...fields,
  };
}

/** A fictitious heatmap of the last year up to `to` (the server's today). */
export function journalHeatmap(
  to: string,
  items: JournalHeatmapResponse['items'] = [],
  from = `${Number(to.slice(0, 4)) - 1}${to.slice(4)}`,
): JournalHeatmapResponse {
  return { from, to, items };
}
