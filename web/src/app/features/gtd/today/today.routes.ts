import { Routes } from '@angular/router';
import { TodayHabits } from './today-habits';
import { provideTodaySection } from './today-sections';

export default [
  {
    path: '',
    title: $localize`:@@nav.today:Today`,
    providers: [provideTodaySection({ id: 'habits', order: 10, component: TodayHabits })],
    loadComponent: () => import('./today-view'),
  },
] satisfies Routes;
