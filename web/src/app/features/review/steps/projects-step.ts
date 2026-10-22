import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  createTask,
  getReviewProjects,
  ProjectResponse,
  setProjectNextStep,
  updateProject,
} from '../../../api/generated';
import { etag } from '../../gtd/data';
import { PROJECT_STATUSES, ProjectStatus, statusLabel } from '../../gtd/projects/project-groups';
import { StepList } from './step-list';

/**
 * Step 5: each active project, stalled ones first — give a stalled one its next step, change the
 * status, or confirm it looks good. A project leaves the list once it is confirmed or no longer active.
 */
@Component({
  selector: 'app-review-projects-step',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  styleUrl: './steps.scss',
  template: `
    <p class="status" role="status">{{ status() }}</p>
    @if (loaded()) {
      <ul class="items">
        @for (project of items(); track project.id) {
          <li class="item" [class.stalled]="project.stalled">
            <div class="what">
              <a class="title" [routerLink]="['/projects', project.id]">{{ project.title }}</a>
              <span class="meta">
                @if (project.stalled) {
                  <span class="flag" i18n="@@review.projects.stalled">No next step</span>
                } @else {
                  <ng-container i18n="@@review.projects.hasNext">Has a next step</ng-container>
                }
              </span>
              @if (project.stalled) {
                <form class="inline" (submit)="addNextStep($event, project)">
                  <input
                    type="text"
                    [attr.aria-label]="nextStepLabel(project.title)"
                    placeholder="Next step"
                    i18n-placeholder="@@review.projects.nextStepPlaceholder"
                    [value]="drafts()[project.id] ?? ''"
                    (input)="setDraft(project.id, $event)"
                  />
                  <button
                    type="submit"
                    class="choice"
                    [disabled]="busy().has(project.id) || !(drafts()[project.id] ?? '').trim()"
                    i18n="@@review.projects.addNextStep"
                  >
                    Add
                  </button>
                </form>
              }
            </div>
            <div class="choices" role="group" [attr.aria-label]="project.title">
              <select
                class="choice"
                [attr.aria-label]="statusLabelFor(project.title)"
                [disabled]="busy().has(project.id)"
                (change)="changeStatus(project, $event)"
              >
                @for (option of statuses; track option.value) {
                  <option [value]="option.value" [selected]="option.value === project.status">
                    {{ option.label }}
                  </option>
                }
              </select>
              <button
                type="button"
                class="choice"
                [disabled]="busy().has(project.id)"
                (click)="keep(project)"
                i18n="@@review.projects.keep"
              >
                Looks good
              </button>
            </div>
          </li>
        } @empty {
          <li class="empty" i18n="@@review.projects.empty">No active projects left to review.</li>
        }
      </ul>
    } @else if (failed()) {
      <div role="alert">
        <p class="empty" i18n="@@review.loadError">This step could not be loaded.</p>
        <button type="button" class="choice" (click)="load()" i18n="@@review.retry">
          Try again
        </button>
      </div>
    } @else {
      <p class="empty" i18n="@@review.loading">Loading…</p>
    }
  `,
})
export class ProjectsStep extends StepList<ProjectResponse> {
  protected readonly statuses = PROJECT_STATUSES;
  /** The next step typed for each stalled project. */
  protected readonly drafts = signal<Readonly<Record<string, string>>>({});

  protected async fetch(): Promise<ProjectResponse[]> {
    return (await this.api.invoke(getReviewProjects)).items;
  }

  protected setDraft(id: string, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.drafts.update((drafts) => ({ ...drafts, [id]: value }));
  }

  /** Creates the typed next action in the project and makes it the project's next step. */
  protected addNextStep(event: Event, project: ProjectResponse): void {
    event.preventDefault();
    const title = (this.drafts()[project.id] ?? '').trim();
    if (!title) return;
    void this.decide(
      project,
      $localize`:@@review.projects.added:Added “${title}:title:” as the next step of “${project.title}:project:”.`,
      async () => {
        const task = await this.api.invoke(createTask, {
          body: { title, list: 'NEXT', projectId: project.id },
        });
        this.drafts.update((drafts) => ({ ...drafts, [project.id]: '' }));
        return this.api.invoke(setProjectNextStep, {
          id: project.id,
          body: { taskId: task.id },
        });
      },
      true,
    );
  }

  protected changeStatus(project: ProjectResponse, event: Event): void {
    const select = event.target as HTMLSelectElement;
    const status = select.value as ProjectStatus;
    if (status === project.status) return;
    // Shown again by the list if the change fails.
    select.value = project.status;
    void this.decide(
      project,
      $localize`:@@review.projects.statusChanged:“${project.title}:title:” is now ${statusLabel(status)}:status:.`,
      () =>
        this.api.invoke(updateProject, {
          id: project.id,
          'If-Match': etag(project.version),
          body: { status },
        }),
    );
  }

  protected keep(project: ProjectResponse): void {
    void this.decide(project, $localize`:@@review.kept:Kept “${project.title}:title:”.`);
  }

  protected nextStepLabel(title: string): string {
    return $localize`:@@review.projects.nextStepLabel:Next step for “${title}:title:”`;
  }

  protected statusLabelFor(title: string): string {
    return $localize`:@@review.projects.statusLabel:Status of “${title}:title:”`;
  }
}
