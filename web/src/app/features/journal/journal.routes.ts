import { Routes } from '@angular/router';
import { ComingSoon } from '../../shell/coming-soon';

// Placeholder until the journal feature task replaces it.
export default [
  { path: '', title: $localize`:@@nav.journal:Journal`, component: ComingSoon },
] satisfies Routes;
