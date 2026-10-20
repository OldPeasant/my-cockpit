import { Routes } from '@angular/router';
import { ComingSoon } from '../../../shell/coming-soon';

// Placeholder until the waiting feature task replaces it.
export default [
  { path: '', title: $localize`:@@nav.waiting:Waiting`, component: ComingSoon },
] satisfies Routes;
