import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { AreaResponse, ProjectPatchRequest, ProjectResponse } from '../../../api/generated';
import { ProjectStore } from '../data';
import { PROJECT_STATUSES, ProjectStatus } from './project-groups';

/** The longest title the server accepts. */
export const MAX_PROJECT_TITLE = 500;

/** The editable fields of the form. */
interface Draft {
  title: string;
  status: ProjectStatus;
  areaId: string;
  dueDate: string;
  reviewDate: string;
}

/**
 * Creates a project, or edits one (`project` set): title, status, area and its due and review
 * dates. Saving sends only the fields that changed; `saved` emits the server's answer.
 */
@Component({
  selector: 'app-project-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './project-form.html',
  styleUrls: ['../task-detail/controls.scss', './project-form.scss'],
})
export class ProjectForm {
  private readonly store = inject(ProjectStore);

  /** The project to edit; unset to create one. */
  readonly project = input<ProjectResponse>();
  readonly areas = input<readonly AreaResponse[]>([]);
  /** The area a new project starts in. */
  readonly areaId = input<string>();

  readonly saved = output<ProjectResponse>();
  readonly cancelled = output<void>();

  protected readonly statuses = PROJECT_STATUSES;
  protected readonly maxTitle = MAX_PROJECT_TITLE;
  protected readonly saving = signal(false);
  protected readonly titleMissing = signal(false);
  /** Unique per form, for the label/control ids. */
  protected readonly uid = `pf-${++forms}`;

  private readonly edits = signal<Partial<Draft>>({});
  protected readonly draft = computed((): Draft => {
    const project = this.project();
    return {
      title: project?.title ?? '',
      status: project?.status ?? 'ACTIVE',
      areaId: project?.areaId ?? this.areaId() ?? '',
      dueDate: project?.dueDate ?? '',
      reviewDate: project?.reviewDate ?? '',
      ...this.edits(),
    };
  });

  protected edit(change: Partial<Draft>): void {
    this.edits.update((edits) => ({ ...edits, ...change }));
    if (change.title?.trim()) this.titleMissing.set(false);
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected async save(): Promise<void> {
    if (this.saving()) return;
    const draft = this.draft();
    const title = draft.title.trim();
    if (!title) {
      this.titleMissing.set(true);
      return;
    }
    const project = this.project();
    this.saving.set(true);
    try {
      const result = project
        ? await this.store.update(project, changes(project, { ...draft, title }))
        : await this.store.create({
            title,
            status: draft.status,
            areaId: draft.areaId || undefined,
            dueDate: draft.dueDate || undefined,
            reviewDate: draft.reviewDate || undefined,
          });
      this.edits.set({});
      this.saved.emit(result);
    } catch {
      // The store rolled the change back; the HTTP layer shows the error.
    } finally {
      this.saving.set(false);
    }
  }
}

let forms = 0;

/** The PATCH body for the fields of `draft` that differ from `project` (empty clears). */
function changes(project: ProjectResponse, draft: Draft): ProjectPatchRequest {
  const patch: ProjectPatchRequest = {};
  if (draft.title !== project.title) patch.title = draft.title;
  if (draft.status !== project.status) patch.status = draft.status;
  if (draft.areaId !== (project.areaId ?? '')) patch.areaId = draft.areaId || null;
  if (draft.dueDate !== (project.dueDate ?? '')) patch.dueDate = draft.dueDate || null;
  if (draft.reviewDate !== (project.reviewDate ?? '')) patch.reviewDate = draft.reviewDate || null;
  return patch;
}
