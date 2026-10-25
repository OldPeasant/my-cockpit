import { Routes } from '@angular/router';

export default [
  {
    path: '',
    title: $localize`:@@nav.new:New`,
    loadComponent: () => import('./new-page'),
  },
] satisfies Routes;
