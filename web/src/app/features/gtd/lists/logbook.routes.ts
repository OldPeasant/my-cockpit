import { Routes } from '@angular/router';
import { LogbookPage } from './logbook-page';

export default [
  { path: '', title: $localize`:@@nav.logbook:Logbook`, component: LogbookPage },
] satisfies Routes;
