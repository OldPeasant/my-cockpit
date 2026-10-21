import { AreaResponse, ProjectResponse } from '../../../api/generated';

export type ProjectStatus = ProjectResponse['status'];

/** The project statuses in the order of the status picker, with their labels. */
export const PROJECT_STATUSES: readonly { value: ProjectStatus; label: string }[] = [
  { value: 'ACTIVE', label: $localize`:@@gtd.project.status.active:Active` },
  { value: 'ON_HOLD', label: $localize`:@@gtd.project.status.onHold:On hold` },
  { value: 'SOMEDAY', label: $localize`:@@gtd.project.status.someday:Someday` },
  { value: 'COMPLETED', label: $localize`:@@gtd.project.status.completed:Completed` },
  { value: 'DROPPED', label: $localize`:@@gtd.project.status.dropped:Dropped` },
];

export function statusLabel(status: ProjectStatus): string {
  return PROJECT_STATUSES.find((option) => option.value === status)?.label ?? status;
}

/** Whether a project is finished: the list hides these unless asked to show them. */
export function finished(project: ProjectResponse): boolean {
  return project.status === 'COMPLETED' || project.status === 'DROPPED';
}

/** The projects of one area; `area` is undefined for the projects without one. */
export interface ProjectGroup {
  readonly area?: AreaResponse;
  readonly projects: readonly ProjectResponse[];
  /** How many of them are stalled. */
  readonly stalled: number;
}

/**
 * The projects grouped by area (SPEC.md §5.3), areas in their order, then the projects without an
 * area (also those whose area is unknown, e.g. deleted). Empty areas are left out. Within a group
 * active projects come first, then on hold, someday and finished ones, each by title.
 */
export function groupByArea(
  projects: readonly ProjectResponse[],
  areas: readonly AreaResponse[],
): ProjectGroup[] {
  const known = new Set(areas.map((area) => area.id));
  const byArea = new Map<string | undefined, ProjectResponse[]>();
  for (const project of projects) {
    const key = project.areaId && known.has(project.areaId) ? project.areaId : undefined;
    byArea.set(key, [...(byArea.get(key) ?? []), project]);
  }
  const group = (area: AreaResponse | undefined, members: ProjectResponse[]): ProjectGroup => ({
    area,
    projects: [...members].sort(byStatusThenTitle),
    stalled: members.filter((project) => project.stalled).length,
  });
  const groups = [...areas]
    .sort((a, b) => a.position - b.position)
    .filter((area) => byArea.has(area.id))
    .map((area) => group(area, byArea.get(area.id)!));
  const loose = byArea.get(undefined);
  return loose ? [...groups, group(undefined, loose)] : groups;
}

function byStatusThenTitle(a: ProjectResponse, b: ProjectResponse): number {
  const rank = (project: ProjectResponse) =>
    PROJECT_STATUSES.findIndex((option) => option.value === project.status);
  return rank(a) - rank(b) || a.title.localeCompare(b.title);
}
