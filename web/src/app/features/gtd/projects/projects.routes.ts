import { Routes } from '@angular/router';
import { ProjectDetailPage } from './project-detail-page';
import { ProjectsPage } from './projects-page';

export default [
  { path: '', title: $localize`:@@nav.projects:Projects`, component: ProjectsPage },
  { path: ':id', title: $localize`:@@nav.project:Project`, component: ProjectDetailPage },
] satisfies Routes;
