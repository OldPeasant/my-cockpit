import { Routes } from '@angular/router';
import { TaskDetailPage } from './task-detail-page';

export default [
  { path: ':id', title: $localize`:@@taskDetail.pageTitle:Task`, component: TaskDetailPage },
] satisfies Routes;
