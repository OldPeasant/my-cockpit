import { Routes } from '@angular/router';
import { provideCapture } from './features/capture/capture.providers';
import { provideSearch } from './features/search/search.providers';
import { provideNavigationPalette } from './shell/navigation-palette.provider';

/**
 * Every section is a lazy `features/<feature>/<feature>.routes.ts` (list: features/README.md).
 * The shell layout is lazy too, so the initial bundle stays small.
 * Pages outside the shell layout (e.g. login) go before the shell route.
 */
export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./shell/shell'),
    providers: [provideNavigationPalette(), provideCapture(), provideSearch()],
    children: [
      {
        path: '',
        pathMatch: 'full',
        loadChildren: () => import('./features/dashboard/dashboard.routes'),
      },
      { path: 'calendar', loadChildren: () => import('./features/calendar/calendar.routes') },
      { path: 'mail', loadChildren: () => import('./features/mail/mail.routes') },
      { path: 'notes', loadChildren: () => import('./features/notes/notes.routes') },
      { path: 'files', loadChildren: () => import('./features/files/files.routes') },
      { path: 'contacts', loadChildren: () => import('./features/contacts/contacts.routes') },
      { path: 'bookmarks', loadChildren: () => import('./features/bookmarks/bookmarks.routes') },
      { path: 'journal', loadChildren: () => import('./features/journal/journal.routes') },
      { path: 'habits', loadChildren: () => import('./features/habits/habits.routes') },
      { path: 'review', loadChildren: () => import('./features/review/review.routes') },
      { path: 'settings', loadChildren: () => import('./features/settings/settings.routes') },
      { path: 'trash', loadChildren: () => import('./features/trash/trash.routes') },
      // GTD sections (/inbox, /next, /projects, /waiting, /someday, /logbook); keep it last.
      { path: '', loadChildren: () => import('./features/gtd/gtd.routes') },
      {
        path: '**',
        title: $localize`:@@notFound.title:Page not found`,
        loadComponent: () => import('./shell/not-found'),
      },
    ],
  },
];
