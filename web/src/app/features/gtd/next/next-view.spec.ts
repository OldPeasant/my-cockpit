import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { MatSelectHarness } from '@angular/material/select/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NEVER, Subject } from 'rxjs';
import {
  Api,
  AreaResponse,
  completeTask,
  ContextResponse,
  listAreas,
  listContexts,
  listProjects,
  listTasks,
  ProjectResponse,
  Settings,
  SettingsPatch,
  TaskResponse,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { SettingsService } from '../../settings/settings.service';
import { fakeSettings } from '../../settings/testing';
import { TASK_PAGE_SIZE } from '../data';
import { FilterPresets, MAX_PRESETS, PRESETS_SETTING } from './filter-presets';
import NextView from './next-view';
import nextRoutes from './next.routes';

const PHONE: ContextResponse = { id: 'c-phone', name: '@phone', position: 1, version: 1 };
const HOME: ContextResponse = { id: 'c-home', name: '@home', position: 2, version: 1 };
const WORK: AreaResponse = { id: 'a-work', title: 'Work', position: 1, version: 1 };
const HOUSE: AreaResponse = { id: 'a-house', title: 'House', position: 2, version: 1 };

function project(
  id: string,
  title: string,
  fields: Partial<ProjectResponse> = {},
): ProjectResponse {
  return {
    id,
    title,
    status: 'ACTIVE',
    stalled: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

const KITCHEN = project('p-kitchen', 'Renovate kitchen', { areaId: HOUSE.id });
const REPORT = project('p-report', 'Quarterly report', { areaId: WORK.id });
const GARDEN = project('p-garden', 'Plant the garden', { areaId: HOUSE.id, status: 'SOMEDAY' });

function task(id: string, title: string, fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id,
    title,
    list: 'NEXT',
    position: 1,
    contextIds: [],
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

const CALL_PLUMBER = task('t-plumber', 'Call the plumber', {
  contextIds: [PHONE.id],
  timeEstimateMin: 15,
  projectId: KITCHEN.id,
});
const WRITE_REPORT = task('t-report', 'Draft the report', { projectId: REPORT.id, position: 2 });
const WATER_PLANTS = task('t-plants', 'Water the plants', { position: 3 });

/** Presets as the setting stores them. */
const stored = (...presets: { name: string; filter: object }[]) =>
  presets.map((preset) => JSON.stringify(preset));

class FakeSettings {
  readonly current = signal<Settings | null>(fakeSettings({ [PRESETS_SETTING]: [] }));
  readonly settings = this.current.asReadonly();
  readonly load = vi.fn(async () => this.current()!);
  readonly update = vi.fn(async (patch: SettingsPatch) => {
    const next = { ...this.current()!, ...patch } as Settings;
    this.current.set(next);
    return next;
  });
}

type Invoke = (fn: unknown, params?: Record<string, unknown>) => Promise<unknown>;

/** Lets pending promise callbacks run. */
const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('next routes', () => {
  it('lazy-loads the Next view at the feature root, titled Next', async () => {
    const [route] = nextRoutes;
    expect(route.path).toBe('');
    expect(route.title).toBe('Next');
    expect(await route.loadComponent!()).toMatchObject({ default: NextView });
  });
});

describe('NextView', () => {
  let taskCalls: Record<string, unknown>[];
  let serverTasks: TaskResponse[];
  let failTasks: boolean;
  let api: { invoke: ReturnType<typeof vi.fn<Invoke>> };
  let settings: FakeSettings;
  let harness: RouterTestingHarness;
  let root: HTMLElement;

  beforeEach(() => {
    taskCalls = [];
    serverTasks = [];
    failTasks = false;
    settings = new FakeSettings();
    api = {
      invoke: vi.fn<Invoke>(async (fn, params = {}) => {
        if (fn === listTasks) {
          taskCalls.push(params);
          if (failTasks) throw new Error('503');
          return { items: serverTasks };
        }
        if (fn === listProjects) return { items: [KITCHEN, REPORT, GARDEN] };
        if (fn === listContexts) return { items: [PHONE, HOME] };
        if (fn === listAreas) return { items: [WORK, HOUSE] };
        if (fn === completeTask) {
          return { ...(params as { id: string }), completedAt: '2026-10-20T10:00:00Z' };
        }
        throw new Error(`unexpected call ${String(fn)}`);
      }),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'next', component: NextView },
          { path: 'tasks/:id', children: [] },
        ]),
        { provide: Api, useValue: api },
        {
          provide: ChangeEventsService,
          useValue: { resync$: new Subject<void>(), events$: () => NEVER },
        },
        { provide: SettingsService, useValue: settings },
      ],
    });
  });

  async function stable(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await settle();
      await harness.fixture.whenStable();
      harness.detectChanges();
    }
  }

  async function open(url: string): Promise<void> {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url, NextView);
    root = harness.routeNativeElement as HTMLElement;
    await stable();
  }

  const url = () => TestBed.inject(Router).url;
  /** The URL query, independent of the order of its keys. */
  const urlQuery = () => {
    const tree = TestBed.inject(Router).parseUrl(url());
    return { ...tree.queryParams };
  };
  const lastQuery = () => taskCalls.at(-1);
  const shownTitles = () =>
    [...root.querySelectorAll('ul.tasks .title')].map((el) => el.textContent!.trim());
  const text = () => root.textContent!.replace(/\s+/g, ' ');

  function option(group: string, label: string): HTMLButtonElement {
    const buttons = root.querySelectorAll<HTMLButtonElement>(
      `[role=group][aria-label="${group}"] button`,
    );
    const found = [...buttons].find((button) => button.textContent!.trim() === label);
    if (!found) throw new Error(`no option ${label} in ${group}`);
    return found;
  }

  function buttonNamed(label: string): HTMLButtonElement {
    const found = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent!.trim() === label,
    );
    if (!found) throw new Error(`no button ${label}`);
    return found;
  }

  async function click(button: HTMLElement): Promise<void> {
    button.click();
    await stable();
  }

  const presetButton = (name: string) =>
    [...root.querySelectorAll<HTMLButtonElement>('.preset-name')].find(
      (button) => button.textContent!.trim() === name,
    );

  async function typeName(name: string): Promise<void> {
    const input = root.querySelector<HTMLInputElement>('form.save-preset input')!;
    input.value = name;
    input.dispatchEvent(new Event('input'));
    await stable();
  }

  async function submitSave(): Promise<void> {
    await click(buttonNamed('Save preset'));
  }

  describe('filters', () => {
    it('loads the open next actions without filters and explains an empty list', async () => {
      await open('/next');

      expect(taskCalls).toEqual([{ list: 'NEXT', cursor: undefined, limit: TASK_PAGE_SIZE }]);
      expect(text()).toContain('No next actions. Process your inbox to decide what to do next.');
      expect(root.querySelector('.clear')).toBeNull();
    });

    it('sends context and max estimate of the URL to the server and marks them chosen', async () => {
      serverTasks = [CALL_PLUMBER];
      await open(`/next?context=${PHONE.id}&maxMinutes=15`);

      expect(lastQuery()).toEqual({
        list: 'NEXT',
        context: PHONE.id,
        maxMinutes: 15,
        cursor: undefined,
        limit: TASK_PAGE_SIZE,
      });
      expect(option('Context', '@phone').getAttribute('aria-pressed')).toBe('true');
      expect(option('Context', '@home').getAttribute('aria-pressed')).toBe('false');
      expect(option('Time available', '≤ 15 min').getAttribute('aria-pressed')).toBe('true');
      expect(shownTitles()).toEqual(['Call the plumber']);
      expect(root.querySelector('.count')?.textContent?.trim()).toBe('1 action');
    });

    it('sends energy and creativity of the URL to the server', async () => {
      await open('/next?energy=LOW&creativity=ROUTINE');

      expect(lastQuery()).toMatchObject({ list: 'NEXT', energy: 'LOW', creativity: 'ROUTINE' });
      expect(option('Energy', 'Low').getAttribute('aria-pressed')).toBe('true');
      expect(option('Creativity', 'Routine').getAttribute('aria-pressed')).toBe('true');
    });

    it('sends project and due window of the URL to the server', async () => {
      await open(`/next?project=${KITCHEN.id}&due=week`);

      expect(lastQuery()).toEqual({
        list: 'NEXT',
        projectId: KITCHEN.id,
        due: 'week',
        cursor: undefined,
        limit: TASK_PAGE_SIZE,
      });
      expect(option('Due', 'Next 7 days').getAttribute('aria-pressed')).toBe('true');
    });

    it('ignores invalid filter values of an old or edited link', async () => {
      await open('/next?energy=HUGE&maxMinutes=abc&due=someday&unknown=1');

      expect(lastQuery()).toEqual({ list: 'NEXT', cursor: undefined, limit: TASK_PAGE_SIZE });
      expect(text()).toContain('No next actions. Process your inbox');
    });

    it('narrows to the area on the client: tasks of its projects only, none without a project', async () => {
      serverTasks = [CALL_PLUMBER, WRITE_REPORT, WATER_PLANTS];
      await open(`/next?area=${HOUSE.id}`);

      // The server has no area filter.
      expect(lastQuery()).toEqual({ list: 'NEXT', cursor: undefined, limit: TASK_PAGE_SIZE });
      expect(shownTitles()).toEqual(['Call the plumber']);
      expect(root.querySelector('.count')?.textContent?.trim()).toBe('1 action');
    });

    it('combines the area with server filters', async () => {
      serverTasks = [CALL_PLUMBER, WRITE_REPORT];
      await open(`/next?area=${WORK.id}&energy=HIGH`);

      expect(lastQuery()).toMatchObject({ energy: 'HIGH' });
      expect(lastQuery()).not.toHaveProperty('area');
      expect(shownTitles()).toEqual(['Draft the report']);
    });

    it('writes a chosen filter into the URL and reloads, keeping the other filters', async () => {
      await open('/next');

      await click(option('Context', '@phone'));
      expect(url()).toBe(`/next?context=${PHONE.id}`);
      expect(lastQuery()).toMatchObject({ context: PHONE.id });

      await click(option('Time available', '≤ 15 min'));
      expect(url()).toBe(`/next?context=${PHONE.id}&maxMinutes=15`);
      expect(lastQuery()).toMatchObject({ context: PHONE.id, maxMinutes: 15 });

      await click(option('Energy', 'High'));
      await click(option('Creativity', 'Creative'));
      await click(option('Due', 'Overdue'));
      expect(urlQuery()).toEqual({
        context: PHONE.id,
        energy: 'HIGH',
        creativity: 'CREATIVE',
        maxMinutes: '15',
        due: 'overdue',
      });
      expect(lastQuery()).toEqual({
        list: 'NEXT',
        context: PHONE.id,
        energy: 'HIGH',
        creativity: 'CREATIVE',
        maxMinutes: 15,
        due: 'overdue',
        cursor: undefined,
        limit: TASK_PAGE_SIZE,
      });
    });

    it('removes a filter from the URL when its chosen option is pressed again', async () => {
      await open(`/next?context=${PHONE.id}&maxMinutes=15`);

      await click(option('Context', '@phone'));

      expect(url()).toBe('/next?maxMinutes=15');
      expect(lastQuery()).toEqual({
        list: 'NEXT',
        maxMinutes: 15,
        cursor: undefined,
        limit: TASK_PAGE_SIZE,
      });
    });

    it('clears every filter', async () => {
      await open(`/next?context=${PHONE.id}&energy=LOW&area=${WORK.id}`);

      await click(root.querySelector<HTMLButtonElement>('button.clear')!);

      expect(url()).toBe('/next');
      expect(lastQuery()).toEqual({ list: 'NEXT', cursor: undefined, limit: TASK_PAGE_SIZE });
    });

    it('offers the active projects of the chosen area and drops a project of another area', async () => {
      await open(`/next?project=${REPORT.id}`);
      const loader = TestbedHarnessEnvironment.loader(harness.fixture);
      const [area, projectSelect] = await loader.getAllHarnesses(MatSelectHarness);

      await projectSelect.open();
      expect(await Promise.all((await projectSelect.getOptions()).map((o) => o.getText()))).toEqual(
        ['Any project', 'Quarterly report', 'Renovate kitchen'],
      );
      await projectSelect.close();

      await area.open();
      await area.clickOptions({ text: 'House' });
      await stable();

      expect(url()).toBe(`/next?area=${HOUSE.id}`);
      await projectSelect.open();
      // Only active projects of the area: not the SOMEDAY garden project.
      expect(await Promise.all((await projectSelect.getOptions()).map((o) => o.getText()))).toEqual(
        ['Any project', 'Renovate kitchen'],
      );
      await projectSelect.clickOptions({ text: 'Renovate kitchen' });
      await stable();

      expect(url()).toBe(`/next?area=${HOUSE.id}&project=${KITCHEN.id}`);
      expect(lastQuery()).toMatchObject({ projectId: KITCHEN.id });
    });

    it('writes an area chosen without a project into the URL and narrows the list', async () => {
      serverTasks = [CALL_PLUMBER, WRITE_REPORT, WATER_PLANTS];
      await open(`/next?energy=LOW`);
      const loader = TestbedHarnessEnvironment.loader(harness.fixture);
      const [area] = await loader.getAllHarnesses(MatSelectHarness);

      await area.open();
      await area.clickOptions({ text: 'Work' });
      await stable();

      expect(urlQuery()).toEqual({ energy: 'LOW', area: WORK.id });
      expect(lastQuery()).not.toHaveProperty('projectId');
      expect(shownTitles()).toEqual(['Draft the report']);
    });

    it('keeps a project of the chosen area and removes the area with "Any area"', async () => {
      await open(`/next?project=${KITCHEN.id}`);
      const loader = TestbedHarnessEnvironment.loader(harness.fixture);
      const [area] = await loader.getAllHarnesses(MatSelectHarness);

      await area.open();
      await area.clickOptions({ text: 'House' });
      await stable();
      expect(urlQuery()).toEqual({ area: HOUSE.id, project: KITCHEN.id });

      await area.open();
      await area.clickOptions({ text: 'Any area' });
      await stable();
      expect(url()).toBe(`/next?project=${KITCHEN.id}`);
    });

    it('removes the project with "Any project", keeping the area', async () => {
      serverTasks = [CALL_PLUMBER, WRITE_REPORT, WATER_PLANTS];
      await open(`/next?area=${HOUSE.id}&project=${KITCHEN.id}`);
      const loader = TestbedHarnessEnvironment.loader(harness.fixture);
      const [, projectSelect] = await loader.getAllHarnesses(MatSelectHarness);

      await projectSelect.open();
      await projectSelect.clickOptions({ text: 'Any project' });
      await stable();

      expect(url()).toBe(`/next?area=${HOUSE.id}`);
      expect(lastQuery()).not.toHaveProperty('projectId');
      expect(shownTitles()).toEqual(['Call the plumber']);
    });

    it('follows the URL when it changes while the view is open (a link, back and forward)', async () => {
      // A simulated browser history: back() and forward() pop states like the browser does.
      TestBed.configureTestingModule({ providers: [provideLocationMocks()] });
      serverTasks = [CALL_PLUMBER, WATER_PLANTS];
      await open('/next');
      const router = TestBed.inject(Router);
      // As at bootstrap: the router follows the history (popstate) from now on.
      router.initialNavigation();
      await stable();

      await router.navigateByUrl(`/next?context=${PHONE.id}&maxMinutes=15`);
      await stable();
      expect(lastQuery()).toMatchObject({ context: PHONE.id, maxMinutes: 15 });
      expect(option('Context', '@phone').getAttribute('aria-pressed')).toBe('true');
      expect(option('Time available', '≤ 15 min').getAttribute('aria-pressed')).toBe('true');

      await click(option('Energy', 'Low'));
      expect(urlQuery()).toEqual({ context: PHONE.id, maxMinutes: '15', energy: 'LOW' });

      TestBed.inject(Location).back();
      await stable();
      expect(urlQuery()).toEqual({ context: PHONE.id, maxMinutes: '15' });
      expect(lastQuery()).toEqual({
        list: 'NEXT',
        context: PHONE.id,
        maxMinutes: 15,
        cursor: undefined,
        limit: TASK_PAGE_SIZE,
      });
      expect(option('Energy', 'Low').getAttribute('aria-pressed')).toBe('false');

      TestBed.inject(Location).back();
      await stable();
      expect(url()).toBe('/next');
      expect(lastQuery()).toEqual({ list: 'NEXT', cursor: undefined, limit: TASK_PAGE_SIZE });
      expect(option('Context', '@phone').getAttribute('aria-pressed')).toBe('false');

      TestBed.inject(Location).forward();
      await stable();
      expect(urlQuery()).toEqual({ context: PHONE.id, maxMinutes: '15' });
      expect(lastQuery()).toMatchObject({ context: PHONE.id, maxMinutes: 15 });
    });

    it('explains an empty filtered list and offers to clear the filters', async () => {
      serverTasks = [WATER_PLANTS];
      await open(`/next?area=${WORK.id}`);

      expect(shownTitles()).toEqual([]);
      expect(text()).toContain('No next actions match these filters.');
      expect(root.querySelector('.count')?.textContent?.trim()).toBe('0 actions');

      await click(root.querySelector<HTMLButtonElement>('li.empty button')!);
      expect(url()).toBe('/next');
      expect(shownTitles()).toEqual(['Water the plants']);
    });

    it('shows an error when the list fails and loads it again on retry', async () => {
      failTasks = true;
      await open('/next');

      expect(root.querySelector('[role=alert]')?.textContent).toContain(
        'Next actions could not be loaded.',
      );
      failTasks = false;
      serverTasks = [WATER_PLANTS];
      await click(buttonNamed('Try again'));

      expect(root.querySelector('[role=alert]')).toBeNull();
      expect(shownTitles()).toEqual(['Water the plants']);
    });

    it('shows a loading note until the list answers', async () => {
      let answer!: (value: unknown) => void;
      api.invoke.mockImplementation(async (fn: unknown) => {
        if (fn === listTasks) return new Promise((resolve) => (answer = resolve));
        return { items: [] };
      });
      await open('/next');
      expect(text()).toContain('Loading next actions…');

      answer({ items: [WATER_PLANTS] });
      await stable();
      expect(shownTitles()).toEqual(['Water the plants']);
    });

    it('shows the answer of the newest filter when an older request answers last', async () => {
      const pending: { params: Record<string, unknown>; resolve: (value: unknown) => void }[] = [];
      const original = api.invoke.getMockImplementation()!;
      api.invoke.mockImplementation((fn: unknown, params?: Record<string, unknown>) => {
        if (fn === listTasks) {
          return new Promise((resolve) => pending.push({ params: params ?? {}, resolve }));
        }
        return original(fn, params);
      });
      await open('/next');
      await click(option('Energy', 'Low'));
      expect(pending.map((call) => call.params['energy'])).toEqual([undefined, 'LOW']);

      // The low-energy answer first, then the stale unfiltered one.
      pending[1].resolve({ items: [CALL_PLUMBER] });
      await stable();
      pending[0].resolve({ items: [CALL_PLUMBER, WRITE_REPORT, WATER_PLANTS] });
      await stable();

      expect(url()).toBe('/next?energy=LOW');
      expect(shownTitles()).toEqual(['Call the plumber']);
      expect(root.querySelector('.count')?.textContent?.trim()).toBe('1 action');
    });

    it('ignores a failure of an older request once the newer filter has answered', async () => {
      const pending: { resolve: (value: unknown) => void; reject: (error: unknown) => void }[] = [];
      const original = api.invoke.getMockImplementation()!;
      api.invoke.mockImplementation((fn: unknown, params?: Record<string, unknown>) => {
        if (fn === listTasks) {
          return new Promise((resolve, reject) => pending.push({ resolve, reject }));
        }
        return original(fn, params);
      });
      await open('/next');
      await click(option('Due', 'Today'));

      pending[1].resolve({ items: [WATER_PLANTS] });
      await stable();
      pending[0].reject(new Error('503'));
      await stable();

      expect(root.querySelector('[role=alert]')).toBeNull();
      expect(shownTitles()).toEqual(['Water the plants']);
    });

    it('combines all seven filters: six go to the server, the area narrows the answer', async () => {
      serverTasks = [CALL_PLUMBER, WRITE_REPORT, WATER_PLANTS];
      await open(
        `/next?context=${PHONE.id}&energy=MEDIUM&creativity=MIXED&maxMinutes=30` +
          `&area=${HOUSE.id}&project=${KITCHEN.id}&due=today`,
      );

      expect(lastQuery()).toEqual({
        list: 'NEXT',
        context: PHONE.id,
        energy: 'MEDIUM',
        creativity: 'MIXED',
        maxMinutes: 30,
        projectId: KITCHEN.id,
        due: 'today',
        cursor: undefined,
        limit: TASK_PAGE_SIZE,
      });
      expect(shownTitles()).toEqual(['Call the plumber']);
      for (const [group, label] of [
        ['Context', '@phone'],
        ['Energy', 'Medium'],
        ['Creativity', 'Mixed'],
        ['Time available', '≤ 30 min'],
        ['Due', 'Today'],
      ]) {
        expect(option(group, label).getAttribute('aria-pressed')).toBe('true');
      }
    });

    it('still filters by the other fields when the areas cannot be loaded', async () => {
      const original = api.invoke.getMockImplementation()!;
      api.invoke.mockImplementation(async (fn: unknown, params?: Record<string, unknown>) => {
        if (fn === listAreas) throw new Error('503');
        return original(fn, params);
      });
      serverTasks = [WATER_PLANTS];
      await open('/next?energy=LOW');

      expect(lastQuery()).toMatchObject({ energy: 'LOW' });
      expect(shownTitles()).toEqual(['Water the plants']);
      const loader = TestbedHarnessEnvironment.loader(harness.fixture);
      const [area] = await loader.getAllHarnesses(MatSelectHarness);
      await area.open();
      expect(await Promise.all((await area.getOptions()).map((o) => o.getText()))).toEqual([
        'Any area',
      ]);
    });

    it('brings a task back when completing it fails', async () => {
      const original = api.invoke.getMockImplementation()!;
      api.invoke.mockImplementation(async (fn: unknown, params?: Record<string, unknown>) => {
        if (fn === completeTask) throw new Error('409');
        return original(fn, params);
      });
      serverTasks = [WATER_PLANTS];
      await open('/next');

      await click(root.querySelector<HTMLInputElement>('ul.tasks input[type=checkbox]')!);

      expect(shownTitles()).toEqual(['Water the plants']);
    });

    it('completes a task from the list', async () => {
      serverTasks = [WATER_PLANTS];
      await open('/next');

      await click(root.querySelector<HTMLInputElement>('ul.tasks input[type=checkbox]')!);
      expect(api.invoke).toHaveBeenCalledWith(
        completeTask,
        expect.objectContaining({ id: WATER_PLANTS.id }),
      );
      expect(shownTitles()).toEqual([]);
    });

    it('opens a task on its title', async () => {
      serverTasks = [CALL_PLUMBER];
      await open('/next');
      await click(root.querySelector<HTMLButtonElement>('ul.tasks .title')!);
      expect(url()).toBe(`/tasks/${CALL_PLUMBER.id}`);
    });
  });

  describe('presets', () => {
    const CALLS = { name: 'Quick calls', filter: { context: PHONE.id, maxMinutes: 15 } };
    const DEEP = { name: 'Deep work', filter: { energy: 'HIGH', creativity: 'CREATIVE' } };

    it('hints at presets while there are none and nothing is filtered', async () => {
      await open('/next');
      expect(text()).toContain('Choose filters below, then save them for one-click access.');
      expect(root.querySelector('.preset-add')).toBeNull();
    });

    it('applies a preset by writing its filter into the URL and marks it pressed', async () => {
      settings.current.set(fakeSettings({ [PRESETS_SETTING]: stored(CALLS, DEEP) }));
      await open('/next');
      expect(presetButton('Quick calls')?.getAttribute('aria-pressed')).toBe('false');

      await click(presetButton('Quick calls')!);

      expect(url()).toBe(`/next?context=${PHONE.id}&maxMinutes=15`);
      expect(lastQuery()).toMatchObject({ context: PHONE.id, maxMinutes: 15 });
      expect(presetButton('Quick calls')?.getAttribute('aria-pressed')).toBe('true');
      expect(presetButton('Deep work')?.getAttribute('aria-pressed')).toBe('false');
      // A filter that is already saved offers no "Save filter…".
      expect(root.querySelector('.preset-add')).toBeNull();
    });

    it('switches from one preset to another, replacing the whole filter', async () => {
      settings.current.set(fakeSettings({ [PRESETS_SETTING]: stored(CALLS, DEEP) }));
      await open(`/next?context=${PHONE.id}&maxMinutes=15`);

      await click(presetButton('Deep work')!);

      expect(url()).toBe('/next?energy=HIGH&creativity=CREATIVE');
      expect(presetButton('Deep work')?.getAttribute('aria-pressed')).toBe('true');
    });

    it('marks the preset matching a URL opened directly, and clears it when pressed again', async () => {
      settings.current.set(fakeSettings({ [PRESETS_SETTING]: stored(CALLS) }));
      await open(`/next?maxMinutes=15&context=${PHONE.id}`);
      expect(presetButton('Quick calls')?.getAttribute('aria-pressed')).toBe('true');

      await click(presetButton('Quick calls')!);

      expect(url()).toBe('/next');
      expect(presetButton('Quick calls')?.getAttribute('aria-pressed')).toBe('false');
    });

    it('saves the current filter under a name', async () => {
      await open(`/next?context=${PHONE.id}&maxMinutes=15`);
      const save = vi.spyOn(TestBed.inject(FilterPresets), 'save');

      await click(root.querySelector<HTMLButtonElement>('.preset-add')!);
      await typeName('  Quick calls ');
      await submitSave();

      expect(save).toHaveBeenCalledWith('Quick calls', { context: PHONE.id, maxMinutes: 15 });
      expect(settings.current()?.[PRESETS_SETTING]).toEqual(stored(CALLS));
      expect(root.querySelector('form.save-preset')).toBeNull();
      expect(presetButton('Quick calls')?.getAttribute('aria-pressed')).toBe('true');
    });

    it('requires a name and does not save without one', async () => {
      await open('/next?energy=LOW');
      const save = vi.spyOn(TestBed.inject(FilterPresets), 'save');

      await click(root.querySelector<HTMLButtonElement>('.preset-add')!);
      await typeName('   ');
      await submitSave();

      expect(root.querySelector('form.save-preset [role=alert]')?.textContent?.trim()).toBe(
        'Enter a name for the preset.',
      );
      expect(save).not.toHaveBeenCalled();
      expect(settings.update).not.toHaveBeenCalled();

      await typeName('Low energy');
      await submitSave();
      expect(save).toHaveBeenCalledWith('Low energy', { energy: 'LOW' });
      expect(root.querySelector('form.save-preset')).toBeNull();
    });

    it('saves a preset of every filter and applies it again from an unfiltered view', async () => {
      const everything =
        `/next?context=${HOME.id}&energy=LOW&creativity=ROUTINE&maxMinutes=5` +
        `&area=${HOUSE.id}&project=${KITCHEN.id}&due=overdue`;
      await open(everything);

      await click(root.querySelector<HTMLButtonElement>('.preset-add')!);
      await typeName('Everything');
      await submitSave();

      const saved = JSON.parse((settings.current()?.[PRESETS_SETTING] as string[])[0]);
      expect(saved).toEqual({
        name: 'Everything',
        filter: {
          context: HOME.id,
          energy: 'LOW',
          creativity: 'ROUTINE',
          maxMinutes: 5,
          area: HOUSE.id,
          project: KITCHEN.id,
          due: 'overdue',
        },
      });

      await click(root.querySelector<HTMLButtonElement>('button.clear')!);
      expect(url()).toBe('/next');
      expect(presetButton('Everything')?.getAttribute('aria-pressed')).toBe('false');

      await click(presetButton('Everything')!);

      expect(urlQuery()).toEqual({
        context: HOME.id,
        energy: 'LOW',
        creativity: 'ROUTINE',
        maxMinutes: '5',
        area: HOUSE.id,
        project: KITCHEN.id,
        due: 'overdue',
      });
      expect(lastQuery()).toMatchObject({ projectId: KITCHEN.id, due: 'overdue', maxMinutes: 5 });
      expect(presetButton('Everything')?.getAttribute('aria-pressed')).toBe('true');
    });

    it('offers "Save filter…" again once the filter differs from the active preset', async () => {
      settings.current.set(fakeSettings({ [PRESETS_SETTING]: stored(CALLS) }));
      await open(`/next?context=${PHONE.id}&maxMinutes=15`);
      expect(root.querySelector('.preset-add')).toBeNull();

      await click(option('Energy', 'Low'));

      expect(presetButton('Quick calls')?.getAttribute('aria-pressed')).toBe('false');
      expect(root.querySelector('.preset-add')).not.toBeNull();
    });

    it('shows presets saved on another device when the settings change', async () => {
      await open('/next');
      expect(presetButton('Deep work')).toBeUndefined();

      settings.current.set(fakeSettings({ [PRESETS_SETTING]: stored(DEEP) }));
      await stable();

      expect(presetButton('Deep work')).toBeDefined();
      expect(text()).not.toContain('Choose filters below');
    });

    it('closes the save form with Escape without saving', async () => {
      await open('/next?energy=LOW');

      await click(root.querySelector<HTMLButtonElement>('.preset-add')!);
      await typeName('Low energy');
      root
        .querySelector<HTMLInputElement>('form.save-preset input')!
        .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      await stable();

      expect(root.querySelector('form.save-preset')).toBeNull();
      expect(settings.update).not.toHaveBeenCalled();
    });

    it('closes the save form on cancel without saving', async () => {
      await open('/next?energy=LOW');

      await click(root.querySelector<HTMLButtonElement>('.preset-add')!);
      await typeName('Low energy');
      await click(buttonNamed('Cancel'));

      expect(root.querySelector('form.save-preset')).toBeNull();
      expect(settings.update).not.toHaveBeenCalled();
      expect(root.querySelector('.preset-add')).not.toBeNull();
    });

    it('shows an error and keeps the form open when saving fails', async () => {
      await open('/next?energy=LOW');
      settings.update.mockRejectedValueOnce(new Error('offline'));

      await click(root.querySelector<HTMLButtonElement>('.preset-add')!);
      await typeName('Low energy');
      await submitSave();

      expect(root.querySelector('form.save-preset [role=alert]')?.textContent?.trim()).toBe(
        'The preset could not be saved. Try again.',
      );
      expect(root.querySelector('form.save-preset')).not.toBeNull();
    });

    it(`refuses a new preset beyond ${MAX_PRESETS} but still replaces an existing one`, async () => {
      const many = Array.from({ length: MAX_PRESETS }, (_, i) => ({
        name: `Preset ${i + 1}`,
        filter: { maxMinutes: 5 },
      }));
      settings.current.set(fakeSettings({ [PRESETS_SETTING]: stored(...many) }));
      await open('/next?energy=LOW');

      await click(root.querySelector<HTMLButtonElement>('.preset-add')!);
      await typeName('One too many');
      await submitSave();
      expect(root.querySelector('form.save-preset [role=alert]')?.textContent?.trim()).toBe(
        `You can save up to ${MAX_PRESETS} presets. Delete one first.`,
      );
      expect(settings.update).not.toHaveBeenCalled();

      await typeName('preset 3');
      await submitSave();
      expect(settings.update).toHaveBeenCalledTimes(1);
      const saved = settings.current()?.[PRESETS_SETTING] as string[];
      expect(saved).toHaveLength(MAX_PRESETS);
      expect(JSON.parse(saved[2])).toEqual({ name: 'preset 3', filter: { energy: 'LOW' } });
    });

    it('deletes a preset', async () => {
      settings.current.set(fakeSettings({ [PRESETS_SETTING]: stored(CALLS, DEEP) }));
      await open('/next');
      const remove = vi.spyOn(TestBed.inject(FilterPresets), 'remove');

      await click(
        root.querySelector<HTMLButtonElement>('[aria-label="Delete preset “Quick calls”"]')!,
      );

      expect(remove).toHaveBeenCalledWith('Quick calls');
      expect(settings.current()?.[PRESETS_SETTING]).toEqual(stored(DEEP));
      expect(presetButton('Quick calls')).toBeUndefined();
      expect(presetButton('Deep work')).toBeDefined();
    });

    it('keeps the view usable when deleting a preset fails', async () => {
      settings.current.set(fakeSettings({ [PRESETS_SETTING]: stored(CALLS) }));
      await open('/next?energy=LOW');
      settings.update.mockRejectedValueOnce(new Error('offline'));

      await click(
        root.querySelector<HTMLButtonElement>('[aria-label="Delete preset “Quick calls”"]')!,
      );

      expect(presetButton('Quick calls')).toBeDefined();
      expect(root.querySelector('[role=alert]')?.textContent?.trim()).toBe(
        'The preset could not be deleted. Try again.',
      );

      // Opening the save form clears the old message.
      await click(root.querySelector<HTMLButtonElement>('.preset-add')!);
      expect(root.querySelector('[role=alert]')).toBeNull();
    });

    it('does not wipe the stored presets when saving after the settings failed to load', async () => {
      settings.current.set(null);
      settings.load.mockRejectedValueOnce(new Error('offline'));
      await open('/next?energy=LOW');
      expect(root.querySelectorAll('.preset')).toHaveLength(0);

      // The server is reachable again and still holds a preset.
      settings.load.mockImplementation(async () => {
        const loaded = fakeSettings({ [PRESETS_SETTING]: stored(CALLS) });
        settings.current.set(loaded);
        return loaded;
      });
      await click(root.querySelector<HTMLButtonElement>('.preset-add')!);
      await typeName('Low energy');
      await submitSave();

      expect(settings.update).toHaveBeenCalledTimes(1);
      const saved = (settings.current()?.[PRESETS_SETTING] as string[]).map((text) =>
        JSON.parse(text),
      );
      expect(saved.map((preset: { name: string }) => preset.name)).toEqual([
        'Quick calls',
        'Low energy',
      ]);
    });

    it('loads the settings when the shell has not loaded them yet', async () => {
      settings.current.set(null);
      settings.load.mockImplementation(async () => {
        const loaded = fakeSettings({ [PRESETS_SETTING]: stored(DEEP) });
        settings.current.set(loaded);
        return loaded;
      });
      await open('/next');

      expect(settings.load).toHaveBeenCalledTimes(1);
      expect(presetButton('Deep work')).toBeDefined();
    });

    it('still shows the next actions when the settings cannot be loaded', async () => {
      settings.current.set(null);
      settings.load.mockRejectedValue(new Error('offline'));
      serverTasks = [WATER_PLANTS];
      await open('/next');

      expect(shownTitles()).toEqual(['Water the plants']);
      expect(root.querySelectorAll('.preset')).toHaveLength(0);
    });
  });
});
