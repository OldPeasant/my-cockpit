import { Routes, UrlMatcher } from '@angular/router';

/**
 * `''` (the drive root) and `:folderId` as one route, so moving between folders keeps the page (and
 * its expanded folder tree) instead of creating it anew.
 */
export const driveMatcher: UrlMatcher = (segments) => {
  if (segments.length === 0) return { consumed: [] };
  if (segments.length === 1) return { consumed: segments, posParams: { folderId: segments[0] } };
  return null;
};

export default [
  {
    matcher: driveMatcher,
    title: $localize`:@@nav.files:Files`,
    loadComponent: () => import('./browser/files-page'),
  },
] satisfies Routes;
