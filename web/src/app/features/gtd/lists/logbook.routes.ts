import { Routes } from '@angular/router';
import { ComingSoon } from '../../../shell/coming-soon';

// Placeholder until the logbook feature task replaces it.
export default [
  { path: '', title: $localize`:@@nav.logbook:Logbook`, component: ComingSoon },
] satisfies Routes;
