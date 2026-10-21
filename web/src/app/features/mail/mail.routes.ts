import { Routes, UrlMatcher } from '@angular/router';

/**
 * `''` (the unified Inbox) and `folders/:folderId` as one route, so moving between folders keeps the
 * page (and its folder tree) instead of creating it anew.
 */
export const mailListMatcher: UrlMatcher = (segments) => {
  if (segments.length === 0) return { consumed: [] };
  if (segments.length === 2 && segments[0].path === 'folders') {
    return { consumed: segments, posParams: { folderId: segments[1] } };
  }
  return null;
};

export default [
  {
    matcher: mailListMatcher,
    title: $localize`:@@nav.mail:Mail`,
    loadComponent: () => import('./list/mail-list-page'),
  },
  {
    path: 'messages/:id',
    title: $localize`:@@mailRead.title:Message`,
    loadComponent: () => import('./read/mail-read-page'),
  },
] satisfies Routes;
