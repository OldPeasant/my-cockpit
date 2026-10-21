import { CdkDropList } from '@angular/cdk/drag-drop';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { AreaResponse, ContextResponse, TaskResponse } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import GtdSettings from './gtd-settings';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const HOME: ContextResponse = {
  id: 'c-home',
  name: '@home',
  color: '#3366ff',
  position: 0,
  version: 1,
};
const PHONE: ContextResponse = { id: 'c-phone', name: '@phone', position: 1, version: 1 };
const ERRANDS: ContextResponse = { id: 'c-errands', name: '@errands', position: 2, version: 1 };
const WORK: AreaResponse = { id: 'a-work', title: 'Work', position: 0, version: 1 };
const FAMILY: AreaResponse = { id: 'a-family', title: 'Family', position: 1, version: 1 };

function task(id: string, contextIds: string[]): TaskResponse {
  return { id, title: `Task ${id}`, contextIds, version: 1 } as TaskResponse;
}

describe('GtdSettings', () => {
  let fixture: ComponentFixture<GtdSettings>;
  let root: HTMLElement;
  let http: HttpTestingController;

  async function setup(
    contexts: ContextResponse[] = [HOME, PHONE, ERRANDS],
    areas: AreaResponse[] = [WORK, FAMILY],
  ): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GtdSettings);
    root = fixture.nativeElement as HTMLElement;
    await settle();
    http.expectOne({ method: 'GET', url: '/api/v1/contexts' }).flush({ items: contexts });
    http.expectOne({ method: 'GET', url: '/api/v1/areas' }).flush({ items: areas });
    await settle();
  }

  async function settle(): Promise<void> {
    await tick();
    await fixture.whenStable();
  }

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
    http.verify();
  });

  function names(list: 'contexts' | 'areas'): string[] {
    return [...root.querySelectorAll<HTMLInputElement>(`.${list} input.name`)].map((i) => i.value);
  }

  function button(label: string): HTMLButtonElement {
    const found = root.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`);
    if (!found) throw new Error(`No button ${label}`);
    return found;
  }

  function dialogButton(text: string): HTMLButtonElement {
    return [...document.querySelectorAll<HTMLButtonElement>('mat-dialog-container button')].find(
      (b) => b.textContent?.trim() === text,
    )!;
  }

  /** The request the dialog's close (after its animation) starts. */
  async function closedThen(
    match: Parameters<HttpTestingController['match']>[0],
  ): Promise<TestRequest> {
    let request: TestRequest | undefined;
    await vi.waitFor(() => {
      [request] = http.match(match);
      if (!request) throw new Error('no request yet');
    });
    return request as TestRequest;
  }

  function dialogText(): string {
    return document.querySelector('mat-dialog-container')?.textContent ?? '';
  }

  it('shows contexts with their colors and areas in order', async () => {
    await setup();
    expect(names('contexts')).toEqual(['@home', '@phone', '@errands']);
    expect(names('areas')).toEqual(['Work', 'Family']);
    expect(root.querySelector<HTMLInputElement>('input[aria-label="Color of @home"]')?.value).toBe(
      '#3366ff',
    );
    expect(button('Move @home up').disabled).toBe(true);
    expect(button('Move @errands down').disabled).toBe(true);
  });

  describe('reorder', () => {
    it('moves a context down at once and saves the whole order', async () => {
      await setup();
      button('Move @home down').click();
      await settle();

      expect(names('contexts')).toEqual(['@phone', '@home', '@errands']);
      const put = http.expectOne({ method: 'PUT', url: '/api/v1/contexts/order' });
      expect(put.request.body).toEqual({ ids: [PHONE.id, HOME.id, ERRANDS.id] });
      // Controls wait while the order is saved.
      expect(button('Move @phone down').disabled).toBe(true);
      put.flush({ items: [PHONE, HOME, ERRANDS] });
      await settle();

      expect(names('contexts')).toEqual(['@phone', '@home', '@errands']);
      expect(button('Move @phone down').disabled).toBe(false);
    });

    it('moves an area up', async () => {
      await setup();
      button('Move Family up').click();
      await settle();

      const put = http.expectOne({ method: 'PUT', url: '/api/v1/areas/order' });
      expect(put.request.body).toEqual({ ids: [FAMILY.id, WORK.id] });
      put.flush({ items: [FAMILY, WORK] });
      await settle();
      expect(names('areas')).toEqual(['Family', 'Work']);
    });

    it('saves the order of a drag and drop', async () => {
      await setup();
      const lists = fixture.debugElement.queryAll(By.directive(CdkDropList));
      lists[0].triggerEventHandler('cdkDropListDropped', { previousIndex: 2, currentIndex: 0 });
      await settle();

      expect(names('contexts')).toEqual(['@errands', '@home', '@phone']);
      http
        .expectOne({ method: 'PUT', url: '/api/v1/contexts/order' })
        .flush({ items: [ERRANDS, HOME, PHONE] });
      await settle();
    });

    it('ignores a drop at the same place', async () => {
      await setup();
      const lists = fixture.debugElement.queryAll(By.directive(CdkDropList));
      lists[1].triggerEventHandler('cdkDropListDropped', { previousIndex: 1, currentIndex: 1 });
      await settle();
      http.expectNone('/api/v1/areas/order');
    });

    it('restores the old order and shows the error when saving fails', async () => {
      await setup();
      button('Move Work down').click();
      await settle();
      expect(names('areas')).toEqual(['Family', 'Work']);

      http
        .expectOne({ method: 'PUT', url: '/api/v1/areas/order' })
        .flush(
          { status: 400, title: 'Bad Request', detail: 'Every id exactly once' },
          { status: 400, statusText: 'Bad Request' },
        );
      await settle();

      expect(names('areas')).toEqual(['Work', 'Family']);
      expect(root.querySelector('.error')?.textContent).toContain('Every id exactly once');
    });
  });

  describe('delete', () => {
    it('asks only for confirmation when no task uses the context', async () => {
      await setup();
      button('Delete @errands').click();
      await settle();
      http
        .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'false')
        .flush({ items: [] });
      await settle();
      http
        .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'true')
        .flush({ items: [] });
      await settle();

      expect(dialogText()).toContain('No task uses this context.');
      expect(document.querySelector('mat-radio-group')).toBeNull();
      dialogButton('Delete').click();

      (await closedThen({ method: 'DELETE', url: `/api/v1/contexts/${ERRANDS.id}` })).flush(null, {
        status: 204,
        statusText: 'No Content',
      });
      await settle();
      expect(names('contexts')).toEqual(['@home', '@phone']);
    });

    it('keeps the context when the dialog is cancelled', async () => {
      await setup();
      button('Delete @home').click();
      await settle();
      http
        .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'false')
        .flush({ items: [task('t1', [HOME.id])] });
      await settle();
      http
        .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'true')
        .flush({ items: [] });
      await settle();

      expect(dialogText()).toContain('1 task uses this context');
      dialogButton('Cancel').click();
      await settle();
      http.expectNone({ method: 'DELETE' });
      expect(names('contexts')).toEqual(['@home', '@phone', '@errands']);
    });

    it('gives the tasks another context when chosen, then deletes', async () => {
      await setup();
      button('Delete @home').click();
      await settle();
      const tasks = [task('t1', [HOME.id]), task('t2', [HOME.id, PHONE.id])];
      http
        .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'false')
        .flush({ items: tasks });
      await settle();
      http
        .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'true')
        .flush({ items: [] });
      await settle();

      expect(dialogText()).toContain('2 tasks use this context');
      document.querySelector<HTMLInputElement>('mat-radio-button[value="move"] input')!.click();
      await settle();
      const select = document.querySelector<HTMLSelectElement>('select.target')!;
      expect([...select.options].map((o) => o.textContent)).toEqual(['@phone', '@errands']);
      select.value = ERRANDS.id;
      select.dispatchEvent(new Event('change'));
      await settle();
      dialogButton('Delete').click();

      // The service collects the tasks again, so it works on their current versions.
      (
        await closedThen((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'false')
      ).flush({ items: tasks });
      await settle();
      http
        .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'true')
        .flush({ items: [] });
      await settle();
      const p1 = http.expectOne({ method: 'PATCH', url: '/api/v1/tasks/t1' });
      expect(p1.request.body).toEqual({ contextIds: [ERRANDS.id] });
      p1.flush(task('t1', [ERRANDS.id]));
      await settle();
      const p2 = http.expectOne({ method: 'PATCH', url: '/api/v1/tasks/t2' });
      expect(p2.request.body).toEqual({ contextIds: [ERRANDS.id, PHONE.id] });
      p2.flush(task('t2', [ERRANDS.id, PHONE.id]));
      await settle();
      http
        .expectOne({ method: 'DELETE', url: `/api/v1/contexts/${HOME.id}` })
        .flush(null, { status: 204, statusText: 'No Content' });
      await settle();

      expect(names('contexts')).toEqual(['@phone', '@errands']);
    });

    it('leaves the projects without an area by default', async () => {
      await setup();
      button('Delete Work').click();
      await settle();
      http
        .expectOne((r) => r.url === '/api/v1/projects')
        .flush({
          items: [{ id: 'p1', title: 'Fictitious launch', areaId: WORK.id, version: 1 }],
        });
      await settle();

      expect(dialogText()).toContain('1 project is in this area');
      expect(dialogText()).toContain('Leave them without an area');
      dialogButton('Delete').click();

      (await closedThen({ method: 'DELETE', url: `/api/v1/areas/${WORK.id}` })).flush(null, {
        status: 204,
        statusText: 'No Content',
      });
      await settle();
      http.expectNone({ method: 'PATCH' });
      expect(names('areas')).toEqual(['Family']);
    });

    it('reloads the list and shows the error when the area changed elsewhere', async () => {
      await setup();
      button('Delete Family').click();
      await settle();
      http.expectOne((r) => r.url === '/api/v1/projects').flush({ items: [] });
      await settle();
      dialogButton('Delete').click();

      (await closedThen({ method: 'DELETE', url: `/api/v1/areas/${FAMILY.id}` })).flush(
        { status: 412, title: 'Precondition Failed' },
        { status: 412, statusText: 'Failed' },
      );
      await settle();
      http
        .expectOne({ method: 'GET', url: '/api/v1/areas' })
        .flush({ items: [WORK, { ...FAMILY, title: 'Family & friends', version: 2 }] });
      await settle();

      expect(names('areas')).toEqual(['Work', 'Family & friends']);
      expect(root.querySelector('.error')?.textContent).toContain('changed elsewhere');
    });
  });

  it('creates a context with a color and renames an area', async () => {
    await setup();
    const name = root.querySelector<HTMLInputElement>('form.add input[formcontrolname="name"]')!;
    name.value = '  @computer ';
    name.dispatchEvent(new Event('input'));
    const color = root.querySelector<HTMLInputElement>(
      'input[aria-label="Color of the new context"]',
    )!;
    color.value = '#00aa00';
    color.dispatchEvent(new Event('change'));
    root.querySelector<HTMLButtonElement>('form.add button[type="submit"]')!.click();
    await settle();

    const post = http.expectOne({ method: 'POST', url: '/api/v1/contexts' });
    expect(post.request.body).toEqual({ name: '@computer', color: '#00aa00' });
    post.flush({ id: 'c-pc', name: '@computer', color: '#00aa00', position: 3, version: 0 });
    await settle();
    expect(names('contexts')).toEqual(['@home', '@phone', '@errands', '@computer']);
    expect(name.value).toBe('');

    const title = root.querySelector<HTMLInputElement>('input[aria-label="Name of Work"]')!;
    title.value = 'Job';
    title.dispatchEvent(new Event('change'));
    await settle();
    const patch = http.expectOne({ method: 'PATCH', url: `/api/v1/areas/${WORK.id}` });
    expect(patch.request.body).toEqual({ title: 'Job' });
    patch.flush({ ...WORK, title: 'Job', version: 2 });
    await settle();
    expect(names('areas')).toEqual(['Job', 'Family']);
  });
});
