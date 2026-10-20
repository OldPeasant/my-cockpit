import { Routes } from '@angular/router';

export default [
  {
    path: '',
    title: $localize`:@@nav.trash:Trash`,
    loadComponent: () => import('./trash-page'),
  },
] satisfies Routes;
