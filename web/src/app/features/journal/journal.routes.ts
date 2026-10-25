import { Routes, UrlMatchResult, UrlSegment } from '@angular/router';

/**
 * `/journal` (today) and `/journal/:date` in one route, so moving between them keeps the page:
 * it saves the day it leaves before it loads the next one.
 */
export function journalDay(segments: UrlSegment[]): UrlMatchResult | null {
  if (!segments.length) return { consumed: [] };
  if (segments.length === 1) return { consumed: segments, posParams: { date: segments[0] } };
  return null;
}

export default [
  {
    matcher: journalDay,
    title: $localize`:@@nav.journal:Journal`,
    loadComponent: () => import('./journal-page'),
  },
] satisfies Routes;
