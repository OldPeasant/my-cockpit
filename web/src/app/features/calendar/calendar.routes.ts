import { Routes } from '@angular/router';

export default [
  {
    path: '',
    title: $localize`:@@nav.calendar:Calendar`,
    loadComponent: () => import('./views/calendar-view'),
  },
] satisfies Routes;
