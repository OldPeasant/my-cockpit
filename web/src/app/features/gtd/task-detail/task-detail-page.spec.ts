import { Location } from '@angular/common';
import { Component, input, output } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  convertToParamMap,
  ParamMap,
  provideRouter,
  Router,
} from '@angular/router';
import { BehaviorSubject, Subject } from 'rxjs';
import { Api, ChangeEvent } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ApiError } from '../../../core/http';
import { TaskDetail } from './task-detail';
import { TaskDetailPage } from './task-detail-page';
import gtdRoutes from '../gtd.routes';
import routes from './task-detail.routes';

@Component({
  selector: 'app-task-detail',
  template: `<span class="id">{{ taskId() }}</span
    ><button (click)="closed.emit()">close</button>`,
})
class FakeTaskDetail {
  readonly taskId = input.required<string>();
  readonly closed = output<void>();
}

describe('TaskDetailPage', () => {
  let params: BehaviorSubject<ParamMap>;
  let state: unknown;
  let back: ReturnType<typeof vi.fn>;
  let navigate: ReturnType<typeof vi.fn>;
  let element: HTMLElement;

  function render(): void {
    const fixture = TestBed.createComponent(TaskDetailPage);
    element = fixture.nativeElement;
    fixture.detectChanges();
  }

  const close = () => element.querySelector('button')!.click();
  const shownTaskId = () => element.querySelector('app-task-detail .id')?.textContent;

  beforeEach(() => {
    params = new BehaviorSubject(convertToParamMap({ id: 'task-42' }));
    state = { navigationId: 3 };
    back = vi.fn();
    navigate = vi.fn().mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        { provide: ActivatedRoute, useValue: { paramMap: params } },
        { provide: Location, useValue: { getState: () => state, back } },
        { provide: Router, useValue: { navigate } },
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
    const fixture = TestBed.createComponent(TaskDetailPage);
    element = fixture.nativeElement;
    fixture.detectChanges();
    params.next(convertToParamMap({ id: 'task-43' }));
    fixture.detectChanges();
    expect(shownTaskId()).toBe('task-43');
  });

  it('shows nothing without an id', () => {
    params.next(convertToParamMap({}));
    render();
    expect(element.querySelector('app-task-detail')).toBeNull();
  });

  it('goes back when the app has a previous page', () => {
    render();
    close();
    expect(back).toHaveBeenCalledTimes(1);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('goes to the Inbox when the editor was the first page opened', () => {
    state = { navigationId: 1 };
    render();
    close();
    expect(back).not.toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/inbox']);
  });

  it('goes to the Inbox when there is no navigation state', () => {
    state = null;
    render();
    close();
    expect(back).not.toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/inbox']);
  });
});

describe('TaskDetailPage with the real editor', () => {
  it('goes back when the editor is closed', async () => {
    const back = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: new BehaviorSubject(convertToParamMap({ id: 'gone' })) },
        },
        { provide: Location, useValue: { getState: () => ({ navigationId: 2 }), back } },
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
    expect(back).toHaveBeenCalledTimes(1);
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
