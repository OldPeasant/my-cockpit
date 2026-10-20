import { Routes } from '@angular/router';

export default [
  {
    path: '',
    title: $localize`:@@nav.next:Next`,
    loadComponent: () => import('./next-view'),
  },
] satisfies Routes;
