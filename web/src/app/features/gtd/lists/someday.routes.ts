import { Routes } from '@angular/router';
import { ComingSoon } from '../../../shell/coming-soon';

// Placeholder until the someday feature task replaces it.
export default [
  { path: '', title: $localize`:@@nav.someday:Someday`, component: ComingSoon },
] satisfies Routes;
