import { Routes } from '@angular/router';

export default [
  {
    path: '',
    title: $localize`:@@nav.bookmarks:Bookmarks`,
    loadComponent: () => import('./bookmarks-page'),
  },
  {
    path: ':id',
    title: $localize`:@@nav.bookmarks:Bookmarks`,
    loadComponent: () => import('./bookmark-reader'),
  },
] satisfies Routes;
