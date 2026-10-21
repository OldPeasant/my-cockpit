import { AreaResponse, ProjectResponse } from '../../../api/generated';
import { finished, groupByArea, statusLabel } from './project-groups';

function project(id: string, fields: Partial<ProjectResponse> = {}): ProjectResponse {
  return {
    id,
    title: `Project ${id}`,
    status: 'ACTIVE',
    stalled: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

function area(id: string, position: number): AreaResponse {
  return { id, title: `Area ${id}`, position, version: 1 };
}

describe('groupByArea', () => {
  it('groups by area in area order, projects without an area last', () => {
    const groups = groupByArea(
      [
        project('p1', { areaId: 'work' }),
        project('p2'),
        project('p3', { areaId: 'home' }),
        project('p4', { areaId: 'gone' }),
      ],
      [area('work', 2), area('home', 1), area('health', 3)],
    );

    expect(groups.map((g) => g.area?.id)).toEqual(['home', 'work', undefined]);
    expect(groups.map((g) => g.projects.map((p) => p.id))).toEqual([['p3'], ['p1'], ['p2', 'p4']]);
  });

  it('orders by status, then title, and counts stalled projects', () => {
    const [group] = groupByArea(
      [
        project('a', { title: 'Zoo', stalled: true }),
        project('b', { title: 'Attic', status: 'ON_HOLD' }),
        project('c', { title: 'Bike', stalled: true }),
        project('d', { title: 'Car', status: 'COMPLETED' }),
      ],
      [],
    );

    expect(group.projects.map((p) => p.title)).toEqual(['Bike', 'Zoo', 'Attic', 'Car']);
    expect(group.stalled).toBe(2);
  });

  it('returns no groups for no projects', () => {
    expect(groupByArea([], [area('work', 1)])).toEqual([]);
  });
});

describe('project status helpers', () => {
  it('labels statuses and knows finished ones', () => {
    expect(statusLabel('ON_HOLD')).toBe('On hold');
    expect(finished(project('p', { status: 'DROPPED' }))).toBe(true);
    expect(finished(project('p', { status: 'COMPLETED' }))).toBe(true);
    expect(finished(project('p', { status: 'SOMEDAY' }))).toBe(false);
  });

  it('shows an unknown status from a newer server as its raw value', () => {
    expect(statusLabel('ARCHIVED' as ProjectResponse['status'])).toBe('ARCHIVED');
  });
});
