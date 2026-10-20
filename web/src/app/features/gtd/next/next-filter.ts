import { Params } from '@angular/router';
import { ProjectResponse, TaskResponse } from '../../../api/generated';
import { TaskQuery } from '../data';
import { Creativity, CREATIVITY_OPTIONS, Energy, ENERGY_OPTIONS } from '../ui';

/** The due windows of the task list (`GET /api/v1/tasks?due=`). */
export type DueWindow = NonNullable<TaskQuery['due']>;
export const DUE_WINDOWS: readonly DueWindow[] = ['overdue', 'today', 'week'];

/**
 * The filters of the Next Actions view (SPEC.md §5.3). Every field is optional; the URL query holds
 * them (`/next?context=…&maxMinutes=15`), so a filtered view can be bookmarked and shared.
 */
export interface NextFilter {
  /** Context id. */
  readonly context?: string;
  readonly energy?: Energy;
  readonly creativity?: Creativity;
  /** Only tasks estimated at most this many minutes. */
  readonly maxMinutes?: number;
  /** Area id: tasks of the area's projects. */
  readonly area?: string;
  /** Project id. */
  readonly project?: string;
  readonly due?: DueWindow;
}

/** The URL query keys, in the order they are written. */
const KEYS = [
  'context',
  'energy',
  'creativity',
  'maxMinutes',
  'area',
  'project',
  'due',
] as const satisfies readonly (keyof NextFilter)[];

/**
 * Reads a filter from URL query params (or a stored preset): unknown keys and invalid values are
 * ignored, so an old or edited link still opens the view.
 */
export function parseFilter(params: Readonly<Record<string, unknown>>): NextFilter {
  const text = (key: string): string | undefined => {
    const value = params[key];
    return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined;
  };
  const oneOf = <T extends string>(key: string, values: readonly T[]): T | undefined => {
    const value = text(key);
    return values.find((option) => option === value);
  };
  const minutes = Number(
    typeof params['maxMinutes'] === 'number' ? params['maxMinutes'] : text('maxMinutes'),
  );
  const filter: Record<string, unknown> = {
    context: text('context'),
    energy: oneOf(
      'energy',
      ENERGY_OPTIONS.map((option) => option.value),
    ),
    creativity: oneOf(
      'creativity',
      CREATIVITY_OPTIONS.map((option) => option.value),
    ),
    maxMinutes: Number.isInteger(minutes) && minutes > 0 ? minutes : undefined,
    area: text('area'),
    project: text('project'),
    due: oneOf('due', DUE_WINDOWS),
  };
  return compact(filter) as NextFilter;
}

/**
 * The URL query params of `filter` for `router.navigate([], { queryParams })`: every key is set,
 * `null` removes the ones not in the filter.
 */
export function filterParams(filter: NextFilter): Params {
  return Object.fromEntries(KEYS.map((key) => [key, filter[key] ?? null]));
}

/** `filter` without its empty fields, keys in URL order (for comparing and storing). */
export function normalizeFilter(filter: NextFilter): NextFilter {
  return compact(Object.fromEntries(KEYS.map((key) => [key, filter[key]]))) as NextFilter;
}

export function sameFilter(a: NextFilter, b: NextFilter): boolean {
  return JSON.stringify(normalizeFilter(a)) === JSON.stringify(normalizeFilter(b));
}

export function isEmptyFilter(filter: NextFilter): boolean {
  return Object.keys(normalizeFilter(filter)).length === 0;
}

/**
 * The server query of `filter`: everything but the area, which the server can't filter by
 * ({@link inArea} does that on the result).
 */
export function taskQuery(filter: NextFilter): TaskQuery {
  return compact({
    list: 'NEXT',
    context: filter.context,
    energy: filter.energy,
    creativity: filter.creativity,
    maxMinutes: filter.maxMinutes,
    projectId: filter.project,
    due: filter.due,
  }) as TaskQuery;
}

/** Whether `task` belongs to the area of `filter` (any task when it has no area). */
export function inArea(
  task: TaskResponse,
  filter: NextFilter,
  projects: ReadonlyMap<string, ProjectResponse>,
): boolean {
  if (!filter.area) return true;
  const project = task.projectId ? projects.get(task.projectId) : undefined;
  return project?.areaId === filter.area;
}

function compact(object: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(object).filter(([, value]) => value !== undefined));
}
