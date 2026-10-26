import {
  ActivatedRouteSnapshot,
  CanDeactivateFn,
  Routes,
  UrlMatchResult,
  UrlSegment,
} from '@angular/router';
import type JournalPage from './journal-page';

/**
 * `/journal` (today) and `/journal/:date` in one route, so moving between them keeps the page:
 * it saves the day it leaves before it loads the next one.
 */
export function journalDay(segments: UrlSegment[]): UrlMatchResult | null {
  if (!segments.length) return { consumed: [] };
  if (segments.length === 1) return { consumed: segments, posParams: { date: segments[0] } };
  return null;
}

/** Leaving a day asks the page first (edits in conflict); it gets the next date or page. */
export const leaveDay: CanDeactivateFn<JournalPage> = (page, _route, _state, next) => {
  let leaf: ActivatedRouteSnapshot = next.root;
  while (leaf.firstChild) leaf = leaf.firstChild;
  return page.canLeave(
    leaf.routeConfig?.matcher === journalDay ? leaf.paramMap.get('date') : undefined,
  );
};

export default [
  {
    matcher: journalDay,
    title: $localize`:@@nav.journal:Journal`,
    loadComponent: () => import('./journal-page'),
    canDeactivate: [leaveDay],
  },
] satisfies Routes;
