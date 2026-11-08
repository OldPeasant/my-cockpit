import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { Component, input, output, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  convertToParamMap,
  ParamMap,
  provideRouter,
  Router,
} from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { BehaviorSubject, Subject } from 'rxjs';
import { Api, ChangeEvent } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ApiError } from '../../../core/http';
import { BackNavigation, provideBackNavigation } from '../../../shared/navigation';
import { TaskDetail } from './task-detail';
import { TaskDetailPage } from './task-detail-page';
import gtdRoutes from '../gtd.routes';
import routes from './task-detail.routes';

@Component({
  selector: 'app-task-detail',
  template: `<span class="id">{{ taskId() }}</span
    ><span class="label">{{ backLabel() }}</span
    ><button (click)="closed.emit()">close</button>`,
})
class FakeTaskDetail {
  readonly taskId = input.required<string>();
  readonly backLabel = input<string | null>(null);
  readonly closed = output<void>();
}

/** Stands in for the app's history: the previous page, if any, and the back calls. */
class FakeBackNavigation {
  readonly previous = signal<{ id: number; url: string; label: string | null } | null>(null);
  readonly back = vi.fn().mockResolvedValue(undefined);
}

describe('TaskDetailPage', () => {
  let params: BehaviorSubject<ParamMap>;
  let navigation: FakeBackNavigation;
  let fixture: ComponentFixture<TaskDetailPage>;
  let element: HTMLElement;

  function render(): void {
    fixture = TestBed.createComponent(TaskDetailPage);
    element = fixture.nativeElement;
    fixture.detectChanges();
  }

  const close = () => element.querySelector('button')!.click();
  const shownTaskId = () => element.querySelector('app-task-detail .id')?.textContent;
  const shownLabel = () => element.querySelector('app-task-detail .label')?.textContent;

  beforeEach(() => {
    params = new BehaviorSubject(convertToParamMap({ id: 'task-42' }));
    navigation = new FakeBackNavigation();
    TestBed.configureTestingModule({
      providers: [
        { provide: ActivatedRoute, useValue: { paramMap: params } },
        { provide: BackNavigation, useValue: navigation },
      ],
    });
    TestBed.overrideComponent(TaskDetailPage, {
      remove: { imports: [TaskDetail] },
      add: { imports: [FakeTaskDetail] },
    });
  });

  it('shows the editor for the task in the route', () => {
    render();
    expect(shownTaskId()).toBe('task-42');
  });

  it('follows a change of the route id', () => {
    render();
    params.next(convertToParamMap({ id: 'task-43' }));
    fixture.detectChanges();
    expect(shownTaskId()).toBe('task-43');
  });

  it('shows nothing without an id', () => {
    params.next(convertToParamMap({}));
    render();
    expect(element.querySelector('app-task-detail')).toBeNull();
  });

  it('goes back with the Inbox as the parent route', () => {
    render();
    close();
    expect(navigation.back).toHaveBeenCalledExactlyOnceWith('/inbox');
  });

  it('names the page the task was opened from', () => {
    navigation.previous.set({ id: 3, url: '/today', label: 'Today' });
    render();
    expect(shownLabel()).toBe('Today');
  });

  it('reads Back when the previous page has no name', () => {
    navigation.previous.set({ id: 3, url: '/somewhere', label: null });
    render();
    expect(shownLabel()).toBe('Back');
  });

  it('names the Inbox when the task was the first page opened', () => {
    render();
    expect(shownLabel()).toBe('Inbox');
  });
});

describe('TaskDetailPage in the app', () => {
  @Component({ selector: 'app-stub-page', template: '<p class="stub">page</p>' })
  class StubPage {}

  async function openFrom(...from: string[]): Promise<RouterTestingHarness> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'inbox', title: 'Inbox', component: StubPage },
          { path: 'today', title: 'Today', component: StubPage },
          { path: 'tasks/:id', title: 'Task', component: TaskDetailPage },
        ]),
        provideLocationMocks(),
        provideBackNavigation(),
      ],
    });
    TestBed.overrideComponent(TaskDetailPage, {
      remove: { imports: [TaskDetail] },
      add: { imports: [FakeTaskDetail] },
    });
    const harness = await RouterTestingHarness.create();
    TestBed.inject(Router).setUpLocationChangeListener();
    for (const url of from) await harness.navigateByUrl(url);
    await harness.navigateByUrl('/tasks/task-42');
    harness.detectChanges();
    return harness;
  }

  async function close(harness: RouterTestingHarness): Promise<string> {
    harness.routeNativeElement!.querySelector('button')!.click();
    for (let i = 0; i < 3; i++) await new Promise((resolve) => setTimeout(resolve));
    await harness.fixture.whenStable();
    return TestBed.inject(Router).url;
  }

  it('goes back to Today when opened from Today', async () => {
    const harness = await openFrom('/inbox', '/today');
    expect(harness.routeNativeElement!.querySelector('.label')?.textContent).toBe('Today');
    expect(await close(harness)).toBe('/today');
    TestBed.inject(Location).back();
    for (let i = 0; i < 3; i++) await new Promise((resolve) => setTimeout(resolve));
    expect(TestBed.inject(Router).url).toBe('/inbox');
  });

  it('goes to the Inbox when the task was opened by a deep link', async () => {
    const harness = await openFrom();
    expect(await close(harness)).toBe('/inbox');
  });
});

describe('TaskDetailPage with the real editor', () => {
  it('goes back when the editor is closed', async () => {
    const navigation = new FakeBackNavigation();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: new BehaviorSubject(convertToParamMap({ id: 'gone' })) },
        },
        { provide: BackNavigation, useValue: navigation },
        {
          provide: Api,
          useValue: { invoke: () => Promise.reject(new ApiError(404, 'Not Found')) },
        },
        {
          provide: ChangeEventsService,
          useValue: { resync$: new Subject<void>(), events$: () => new Subject<ChangeEvent>() },
        },
      ],
    });
    const fixture = TestBed.createComponent(TaskDetailPage);
    fixture.detectChanges();
    for (let i = 0; i < 3; i++) {
      await new Promise((resolve) => setTimeout(resolve));
      TestBed.tick();
    }
    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain('This task does not exist or is in the Trash.');
    [...element.querySelectorAll('button')].find((b) => b.textContent?.includes('Back'))!.click();
    expect(navigation.back).toHaveBeenCalledExactlyOnceWith('/inbox');
  });
});

describe('task detail routes', () => {
  it('route :id shows the page', () => {
    expect(routes).toHaveLength(1);
    expect(routes[0].path).toBe(':id');
    expect(routes[0].component).toBe(TaskDetailPage);
    expect(routes[0].title).toBe('Task');
  });

  it('GTD route /tasks lazily loads the task detail routes', async () => {
    const tasks = gtdRoutes.find((route) => route.path === 'tasks');
    expect(tasks?.loadChildren).toBeDefined();
    const loaded = (await tasks!.loadChildren!()) as { default?: unknown };
    expect(loaded.default).toBe(routes);
  });
});
