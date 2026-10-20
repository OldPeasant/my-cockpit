import { Routes } from '@angular/router';

/**
 * GTD sections, mounted at the root (`/inbox`, `/today`, `/next`, ...). Each view lives in its own sub-folder
 * with its own routes file, so the GTD view tasks never edit this file.
 */
export default [
  { path: 'inbox', loadChildren: () => import('./inbox/inbox.routes') },
  { path: 'today', loadChildren: () => import('./today/today.routes') },
  { path: 'next', loadChildren: () => import('./next/next.routes') },
  { path: 'projects', loadChildren: () => import('./projects/projects.routes') },
  { path: 'waiting', loadChildren: () => import('./lists/waiting.routes') },
  { path: 'someday', loadChildren: () => import('./lists/someday.routes') },
  { path: 'scheduled', loadChildren: () => import('./lists/scheduled.routes') },
  { path: 'logbook', loadChildren: () => import('./lists/logbook.routes') },
] satisfies Routes;
