import { Routes } from '@angular/router';

export default [
  {
    path: '',
    title: $localize`:@@nav.notes:Notes`,
    loadComponent: () => import('./list/notes-page'),
  },
  {
    path: ':id',
    title: $localize`:@@notes.editor.title:Note`,
    loadComponent: () => import('./editor/note-editor-page'),
  },
] satisfies Routes;
