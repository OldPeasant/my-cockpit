import { ProjectResponse, TaskResponse } from '../../../api/generated';
import {
  filterParams,
  inArea,
  isEmptyFilter,
  normalizeFilter,
  parseFilter,
  sameFilter,
  taskQuery,
} from './next-filter';

const PHONE = '0192f0c4-0000-7000-8000-0000000000c1';
const PROJECT = '0192f0c4-0000-7000-8000-0000000000p1';
const AREA = '0192f0c4-0000-7000-8000-0000000000a1';

function task(fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: 't1',
    title: 'Call the plumber',
    list: 'NEXT',
    position: 1,
    contextIds: [],
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

describe('next filter', () => {
  it('reads every filter from the URL query', () => {
    expect(
      parseFilter({
        context: PHONE,
        energy: 'LOW',
        creativity: 'ROUTINE',
        maxMinutes: '15',
        area: AREA,
        project: PROJECT,
        due: 'week',
      }),
    ).toEqual({
      context: PHONE,
      energy: 'LOW',
      creativity: 'ROUTINE',
      maxMinutes: 15,
      area: AREA,
      project: PROJECT,
      due: 'week',
    });
  });

  it('ignores unknown keys and invalid values', () => {
    expect(
      parseFilter({
        energy: 'SLEEPY',
        creativity: 'low',
        maxMinutes: '-5',
        due: 'tomorrow',
        context: '  ',
        sort: 'title',
      }),
    ).toEqual({});
    expect(parseFilter({ maxMinutes: '7.5' })).toEqual({});
    expect(parseFilter({ maxMinutes: 30 })).toEqual({ maxMinutes: 30 });
  });

  it('writes every key, null for the ones not set, so navigation removes them', () => {
    expect(filterParams({ context: PHONE, maxMinutes: 15 })).toEqual({
      context: PHONE,
      energy: null,
      creativity: null,
      maxMinutes: 15,
      area: null,
      project: null,
      due: null,
    });
  });

  it('round-trips through the URL', () => {
    const filter = { context: PHONE, energy: 'HIGH', maxMinutes: 60, due: 'overdue' } as const;
    const params = Object.fromEntries(
      Object.entries(filterParams(filter))
        .filter(([, value]) => value !== null)
        .map(([key, value]) => [key, String(value)]),
    );
    expect(parseFilter(params)).toEqual(filter);
  });

  it('compares filters regardless of key order and empty fields', () => {
    expect(
      sameFilter(
        { maxMinutes: 15, context: PHONE },
        { context: PHONE, maxMinutes: 15, due: undefined },
      ),
    ).toBe(true);
    expect(sameFilter({ maxMinutes: 15 }, { maxMinutes: 30 })).toBe(false);
    expect(isEmptyFilter({ due: undefined })).toBe(true);
    expect(isEmptyFilter({ due: 'today' })).toBe(false);
    expect(Object.keys(normalizeFilter({ due: 'today', context: PHONE }))).toEqual([
      'context',
      'due',
    ]);
  });

  it('asks the server for NEXT tasks with every filter but the area', () => {
    expect(taskQuery({})).toEqual({ list: 'NEXT' });
    expect(
      taskQuery({
        context: PHONE,
        energy: 'LOW',
        creativity: 'MIXED',
        maxMinutes: 15,
        area: AREA,
        project: PROJECT,
        due: 'today',
      }),
    ).toEqual({
      list: 'NEXT',
      context: PHONE,
      energy: 'LOW',
      creativity: 'MIXED',
      maxMinutes: 15,
      projectId: PROJECT,
      due: 'today',
    });
  });

  it('keeps the tasks of the area’s projects', () => {
    const projects = new Map<string, ProjectResponse>([
      [PROJECT, { id: PROJECT, areaId: AREA } as ProjectResponse],
      ['other', { id: 'other', areaId: 'elsewhere' } as ProjectResponse],
    ]);
    expect(inArea(task({ projectId: PROJECT }), { area: AREA }, projects)).toBe(true);
    expect(inArea(task({ projectId: 'other' }), { area: AREA }, projects)).toBe(false);
    expect(inArea(task(), { area: AREA }, projects)).toBe(false);
    expect(inArea(task(), {}, projects)).toBe(true);
  });
});
