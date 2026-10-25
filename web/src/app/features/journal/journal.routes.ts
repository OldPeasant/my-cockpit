import { Routes } from '@angular/router';

export default [
  {
    path: '',
    title: $localize`:@@nav.journal:Journal`,
    loadComponent: () => import('./journal-page'),
  },
  {
    path: ':date',
    title: $localize`:@@nav.journal:Journal`,
    loadComponent: () => import('./journal-page'),
  },
] satisfies Routes;
