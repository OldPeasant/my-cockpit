import { Routes } from '@angular/router';

export default [
  {
    path: '',
    title: $localize`:@@nav.notes:Notes`,
    loadComponent: () => import('./list/notes-page'),
  },
] satisfies Routes;
