import { Routes } from '@angular/router';

export default [
  {
    path: '',
    title: $localize`:@@nav.calendar:Calendar`,
    loadComponent: () => import('./views/calendar-view'),
  },
  // The deep link of an event (links, search): the calendar opens it at its date.
  {
    path: 'events/:id',
    redirectTo: ({ params }) => `/calendar?event=${encodeURIComponent(params['id'])}`,
  },
] satisfies Routes;
