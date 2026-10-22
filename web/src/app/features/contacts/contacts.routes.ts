import { Routes } from '@angular/router';

export default [
  {
    path: '',
    title: $localize`:@@nav.contacts:Contacts`,
    loadComponent: () => import('./contacts-page'),
  },
  {
    // `new` creates a contact, any other id shows one.
    path: ':id',
    title: $localize`:@@nav.contacts:Contacts`,
    loadComponent: () => import('./contact-detail'),
  },
] satisfies Routes;
