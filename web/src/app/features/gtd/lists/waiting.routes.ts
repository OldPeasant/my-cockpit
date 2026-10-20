import { Routes } from '@angular/router';
import { WaitingPage } from './waiting-page';

export default [
  { path: '', title: $localize`:@@nav.waiting:Waiting`, component: WaitingPage },
] satisfies Routes;
