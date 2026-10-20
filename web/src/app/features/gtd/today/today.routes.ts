import { Routes } from '@angular/router';

export default [
  {
    path: '',
    title: $localize`:@@nav.today:Today`,
    loadComponent: () => import('./today-view'),
  },
] satisfies Routes;
