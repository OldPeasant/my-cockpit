import { Routes } from '@angular/router';

export default [
  {
    path: '',
    title: $localize`:@@nav.habits:Habits`,
    loadComponent: () => import('./habits-page'),
  },
] satisfies Routes;
