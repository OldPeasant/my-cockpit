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
import { GtdSettingsService, ReassignError } from './gtd-settings.service';

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

  /** Creates the component; its two list requests are left open. */
  async function create(): Promise<void> {
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
  }

  async function setup(
    contexts: ContextResponse[] = [HOME, PHONE, ERRANDS],
    areas: AreaResponse[] = [WORK, FAMILY],
  ): Promise<void> {
    await create();
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

  function nameInput(label: string): HTMLInputElement {
    const found = root.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`);
    if (!found) throw new Error(`No input ${label}`);
    return found;
  }

  function change(input: HTMLInputElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event('change'));
  }

  function problem(
    status: number,
    detail: string,
  ): [object, { status: number; statusText: string }] {
    return [
      { status, title: `HTTP ${status}`, detail },
      { status, statusText: `HTTP ${status}` },
    ];
  }

  function errorText(): string {
    return [...root.querySelectorAll('.error')].map((e) => e.textContent ?? '').join(' | ');
  }

  /** Answers the two task lists the delete of a context asks for (open: `open`, done: none). */
  async function flushAssignedTasks(open: TaskResponse[]): Promise<void> {
    http
      .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'false')
      .flush({ items: open });
    await settle();
    http
      .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'true')
      .flush({ items: [] });
    await settle();
  }

  describe('load', () => {
    it('shows an empty note for each list without items', async () => {
      await setup([], []);
      expect(root.textContent).toContain('No contexts yet. Add your first one below.');
      expect(root.textContent).toContain('No areas yet. Add your first one below.');
      expect(root.querySelector('ul.items')).toBeNull();
    });

    it('shows a loading note until the lists arrive', async () => {
      await create();
      expect(root.textContent).toContain('Loading contexts…');
      expect(root.textContent).toContain('Loading areas…');
      http.expectOne('/api/v1/contexts').flush({ items: [] });
      http.expectOne('/api/v1/areas').flush({ items: [] });
      await settle();
      expect(root.textContent).not.toContain('Loading');
    });

    it('shows the load error of one list and loads it again on retry', async () => {
      await create();
      http
        .expectOne({ method: 'GET', url: '/api/v1/contexts' })
        .flush(...problem(500, 'Database unavailable'));
      http.expectOne({ method: 'GET', url: '/api/v1/areas' }).flush({ items: [WORK, FAMILY] });
      await settle();

      const alert = root.querySelector('.load-error');
      expect(alert?.textContent).toContain('Contexts could not be loaded: Database unavailable');
      // The other list is not affected.
      expect(names('areas')).toEqual(['Work', 'Family']);
      expect(root.querySelector('form.add input[formcontrolname="name"]')).toBeNull();

      alert!.querySelector<HTMLButtonElement>('button')!.click();
      await settle();
      http.expectOne({ method: 'GET', url: '/api/v1/contexts' }).flush({ items: [HOME, PHONE] });
      await settle();

      expect(root.querySelector('.load-error')).toBeNull();
      expect(names('contexts')).toEqual(['@home', '@phone']);
    });

    it('shows the area load error with its own retry', async () => {
      await create();
      http.expectOne('/api/v1/contexts').flush({ items: [HOME] });
      http.expectOne('/api/v1/areas').flush(null, { status: 0, statusText: 'Unknown Error' });
      await settle();

      const alert = root.querySelector('.load-error');
      expect(alert?.textContent).toContain('Areas could not be loaded');
      alert!.querySelector<HTMLButtonElement>('button')!.click();
      await settle();
      http.expectOne('/api/v1/areas').flush({ items: [WORK] });
      await settle();
      expect(names('areas')).toEqual(['Work']);
    });
  });

  describe('create', () => {
    it('does not send a blank context name and shows the field error', async () => {
      await setup();
      const name = root.querySelector<HTMLInputElement>('form.add input[formcontrolname="name"]')!;
      name.value = '   ';
      name.dispatchEvent(new Event('input'));
      root.querySelector<HTMLButtonElement>('form.add button[type="submit"]')!.click();
      await settle();

      http.expectNone({ method: 'POST' });
      expect(root.textContent).toContain('Enter a name.');
    });

    it('creates a context without a color', async () => {
      await setup();
      const name = root.querySelector<HTMLInputElement>('form.add input[formcontrolname="name"]')!;
      name.value = '@calls';
      name.dispatchEvent(new Event('input'));
      root.querySelector<HTMLButtonElement>('form.add button[type="submit"]')!.click();
      await settle();

      const post = http.expectOne({ method: 'POST', url: '/api/v1/contexts' });
      expect(post.request.body.name).toBe('@calls');
      expect(post.request.body.color).toBeUndefined();
      post.flush({ id: 'c-calls', name: '@calls', position: 3, version: 0 });
      await settle();
      expect(names('contexts')).toEqual(['@home', '@phone', '@errands', '@calls']);
    });

    it('keeps the input and shows the error when the name already exists', async () => {
      await setup();
      const name = root.querySelector<HTMLInputElement>('form.add input[formcontrolname="name"]')!;
      name.value = '@home';
      name.dispatchEvent(new Event('input'));
      const submit = root.querySelector<HTMLButtonElement>('form.add button[type="submit"]')!;
      submit.click();
      await settle();
      expect(submit.disabled).toBe(true);

      http
        .expectOne({ method: 'POST', url: '/api/v1/contexts' })
        .flush(...problem(409, 'A context named @home already exists'));
      await settle();

      expect(errorText()).toContain('A context named @home already exists');
      expect(name.value).toBe('@home');
      expect(names('contexts')).toEqual(['@home', '@phone', '@errands']);
      expect(submit.disabled).toBe(false);
      // 409 is not a stale-state error: no reload.
      http.expectNone({ method: 'GET', url: '/api/v1/contexts' });
    });

    it('creates an area with a trimmed title and rejects a blank one', async () => {
      await setup();
      const forms = root.querySelectorAll<HTMLFormElement>('form.add');
      const areaForm = forms[1];
      const title = areaForm.querySelector<HTMLInputElement>('input[formcontrolname="title"]')!;
      areaForm.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
      await settle();
      http.expectNone({ method: 'POST' });

      title.value = ' Health  ';
      title.dispatchEvent(new Event('input'));
      areaForm.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
      await settle();

      const post = http.expectOne({ method: 'POST', url: '/api/v1/areas' });
      expect(post.request.body).toEqual({ title: 'Health' });
      post.flush({ id: 'a-health', title: 'Health', position: 2, version: 0 });
      await settle();
      expect(names('areas')).toEqual(['Work', 'Family', 'Health']);
      expect(title.value).toBe('');
    });

    it('keeps the area title and shows the error when creating fails', async () => {
      await setup();
      const areaForm = root.querySelectorAll<HTMLFormElement>('form.add')[1];
      const title = areaForm.querySelector<HTMLInputElement>('input[formcontrolname="title"]')!;
      title.value = 'Work';
      title.dispatchEvent(new Event('input'));
      areaForm.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
      await settle();

      http
        .expectOne({ method: 'POST', url: '/api/v1/areas' })
        .flush(...problem(409, 'An area titled Work already exists'));
      await settle();

      expect(errorText()).toContain('An area titled Work already exists');
      expect(title.value).toBe('Work');
      expect(names('areas')).toEqual(['Work', 'Family']);
    });

    it('shows a generic message for an error that is not an API error', async () => {
      await setup();
      vi.spyOn(TestBed.inject(GtdSettingsService), 'createArea').mockRejectedValue(
        new TypeError('fictitious failure'),
      );
      const areaForm = root.querySelectorAll<HTMLFormElement>('form.add')[1];
      const title = areaForm.querySelector<HTMLInputElement>('input[formcontrolname="title"]')!;
      title.value = 'Health';
      title.dispatchEvent(new Event('input'));
      areaForm.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
      await settle();

      expect(errorText()).toContain('Something went wrong.');
      expect(errorText()).not.toContain('fictitious failure');
    });
  });

  describe('rename', () => {
    it('finishes editing a name with Enter', async () => {
      await setup();
      for (const label of ['Name of @home', 'Name of Work']) {
        const input = nameInput(label);
        input.focus();
        expect(document.activeElement).toBe(input);
        input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
        expect(document.activeElement).not.toBe(input);
      }
    });

    it('puts the old area title back and shows the error when saving fails', async () => {
      await setup();
      const input = nameInput('Name of Family');
      change(input, 'Friends');
      await settle();
      http
        .expectOne({ method: 'PATCH', url: `/api/v1/areas/${FAMILY.id}` })
        .flush(...problem(400, 'Title too long'));
      await settle();

      expect(input.value).toBe('Family');
      expect(errorText()).toContain('Title too long');
    });

    it('saves a new context name', async () => {
      await setup();
      change(nameInput('Name of @phone'), ' @mobile ');
      await settle();

      const patch = http.expectOne({ method: 'PATCH', url: `/api/v1/contexts/${PHONE.id}` });
      expect(patch.request.headers.get('If-Match')).toBe('"1"');
      expect(patch.request.body).toEqual({ name: '@mobile' });
      patch.flush({ ...PHONE, name: '@mobile', version: 2 });
      await settle();
      expect(names('contexts')).toEqual(['@home', '@mobile', '@errands']);
    });

    it('puts the old name back for a blank or unchanged name without saving', async () => {
      await setup();
      const input = nameInput('Name of @home');
      change(input, '   ');
      await settle();
      expect(input.value).toBe('@home');

      change(input, ' @home ');
      await settle();
      expect(input.value).toBe('@home');

      const area = nameInput('Name of Work');
      change(area, '');
      await settle();
      expect(area.value).toBe('Work');
      http.expectNone({ method: 'PATCH' });
    });

    it('puts the old name back on Escape', async () => {
      await setup();
      for (const [label, old] of [
        ['Name of Family', 'Family'],
        ['Name of @phone', '@phone'],
      ]) {
        const input = nameInput(label);
        input.value = 'Edited';
        input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
        await settle();
        expect(input.value).toBe(old);
      }
      http.expectNone({ method: 'PATCH' });
    });

    it('puts the old name back and shows the error when saving fails', async () => {
      await setup();
      const input = nameInput('Name of @errands');
      change(input, '@shopping');
      await settle();
      http
        .expectOne({ method: 'PATCH', url: `/api/v1/contexts/${ERRANDS.id}` })
        .flush(...problem(400, 'Name too long'));
      await settle();

      expect(input.value).toBe('@errands');
      expect(errorText()).toContain('Name too long');
    });

    it('reloads the list when the context was deleted elsewhere', async () => {
      await setup();
      change(nameInput('Name of @phone'), '@cell');
      await settle();
      http
        .expectOne({ method: 'PATCH', url: `/api/v1/contexts/${PHONE.id}` })
        .flush(...problem(404, 'Not found'));
      await settle();
      http.expectOne({ method: 'GET', url: '/api/v1/contexts' }).flush({ items: [HOME, ERRANDS] });
      await settle();

      expect(names('contexts')).toEqual(['@home', '@errands']);
      expect(errorText()).toContain('changed elsewhere');
    });
  });

  describe('color', () => {
    it('sets a color picked for a context', async () => {
      await setup();
      expect(root.querySelector('button[aria-label="Remove the color of @phone"]')).toBeNull();
      change(nameInput('Color of @phone'), '#ff8800');
      await settle();

      const patch = http.expectOne({ method: 'PATCH', url: `/api/v1/contexts/${PHONE.id}` });
      expect(patch.request.body).toEqual({ color: '#ff8800' });
      patch.flush({ ...PHONE, color: '#ff8800', version: 2 });
      await settle();
      expect(root.querySelector('button[aria-label="Remove the color of @phone"]')).not.toBeNull();
    });

    it('clears the color with null', async () => {
      await setup();
      button('Remove the color of @home').click();
      await settle();

      const patch = http.expectOne({ method: 'PATCH', url: `/api/v1/contexts/${HOME.id}` });
      expect(patch.request.body).toEqual({ color: null });
      patch.flush({ ...HOME, color: undefined, version: 2 });
      await settle();
      expect(root.querySelector('button[aria-label="Remove the color of @home"]')).toBeNull();
      expect(nameInput('Color of @home').value).toBe('#8a8f98');
    });

    it('does not save the color a context already has', async () => {
      await setup();
      change(nameInput('Color of @home'), '#3366ff');
      await settle();
      http.expectNone({ method: 'PATCH' });
    });

    it('shows a short #rgb color in the picker as #rrggbb', async () => {
      await setup([{ ...PHONE, color: '#a1c' }], []);
      expect(nameInput('Color of @phone').value).toBe('#aa11cc');
    });

    it('keeps the old color and version and shows the error when saving a color fails', async () => {
      await setup();
      change(nameInput('Color of @home'), '#00aa00');
      await settle();
      http
        .expectOne({ method: 'PATCH', url: `/api/v1/contexts/${HOME.id}` })
        .flush(...problem(400, 'Invalid color'));
      await settle();

      expect(errorText()).toContain('Invalid color');
      const swatch = nameInput('Color of @home').closest<HTMLElement>('label.swatch')!;
      expect(swatch.style.getPropertyValue('--swatch')).toBe('#3366ff');
      http.expectNone({ method: 'GET', url: '/api/v1/contexts' });

      // The next change still sends the version the context had, and clears the error.
      button('Remove the color of @home').click();
      await settle();
      const patch = http.expectOne({ method: 'PATCH', url: `/api/v1/contexts/${HOME.id}` });
      expect(patch.request.headers.get('If-Match')).toBe('"1"');
      patch.flush({ ...HOME, color: undefined, version: 2 });
      await settle();
      expect(errorText()).toBe('');
    });

    it('reloads the list when the color change finds the context changed elsewhere', async () => {
      await setup();
      change(nameInput('Color of @phone'), '#ff8800');
      await settle();
      http
        .expectOne({ method: 'PATCH', url: `/api/v1/contexts/${PHONE.id}` })
        .flush(...problem(412, 'Precondition failed'));
      await settle();
      http
        .expectOne({ method: 'GET', url: '/api/v1/contexts' })
        .flush({ items: [HOME, { ...PHONE, name: '@mobile', color: '#123456', version: 3 }] });
      await settle();

      expect(names('contexts')).toEqual(['@home', '@mobile']);
      expect(nameInput('Color of @mobile').value).toBe('#123456');
      expect(errorText()).toContain('changed elsewhere');
    });
  });

  describe('reorder edge cases', () => {
    it('restores the old context order when saving fails', async () => {
      await setup();
      button('Move @errands up').click();
      await settle();
      expect(names('contexts')).toEqual(['@home', '@errands', '@phone']);

      http
        .expectOne({ method: 'PUT', url: '/api/v1/contexts/order' })
        .flush(...problem(500, 'Server down'));
      await settle();

      expect(names('contexts')).toEqual(['@home', '@phone', '@errands']);
      expect(errorText()).toContain('Server down');
      expect(button('Move @errands up').disabled).toBe(false);
    });

    it('ignores a second drop while the first order is being saved', async () => {
      await setup();
      const lists = fixture.debugElement.queryAll(By.directive(CdkDropList));
      lists[0].triggerEventHandler('cdkDropListDropped', { previousIndex: 0, currentIndex: 2 });
      await settle();
      lists[0].triggerEventHandler('cdkDropListDropped', { previousIndex: 0, currentIndex: 1 });
      await settle();

      const put = http.expectOne({ method: 'PUT', url: '/api/v1/contexts/order' });
      expect(names('contexts')).toEqual(['@phone', '@errands', '@home']);
      put.flush({ items: [PHONE, ERRANDS, HOME] });
      await settle();
    });

    it('keeps a rename saved while a failing reorder was pending', async () => {
      await setup();
      button('Move @home down').click();
      await settle();
      const put = http.expectOne({ method: 'PUT', url: '/api/v1/contexts/order' });

      change(nameInput('Name of @phone'), '@mobile');
      await settle();
      http
        .expectOne({ method: 'PATCH', url: `/api/v1/contexts/${PHONE.id}` })
        .flush({ ...PHONE, name: '@mobile', version: 2 });
      await settle();

      put.flush(...problem(500, 'Server down'));
      await settle();

      // The old order comes back, but the saved rename (and its new version) must stay.
      expect(names('contexts')).toEqual(['@home', '@mobile', '@errands']);
    });

    it('keeps a color change and its new version when a reorder fails meanwhile', async () => {
      await setup();
      button('Move @errands up').click();
      await settle();
      const put = http.expectOne({ method: 'PUT', url: '/api/v1/contexts/order' });

      change(nameInput('Color of @home'), '#00aa00');
      await settle();
      http
        .expectOne({ method: 'PATCH', url: `/api/v1/contexts/${HOME.id}` })
        .flush({ ...HOME, color: '#00aa00', version: 2 });
      await settle();

      put.flush(...problem(500, 'Server down'));
      await settle();

      expect(names('contexts')).toEqual(['@home', '@phone', '@errands']);
      expect(nameInput('Color of @home').value).toBe('#00aa00');
      // The next change of @home sends the new version.
      change(nameInput('Name of @home'), '@house');
      await settle();
      const patch = http.expectOne({ method: 'PATCH', url: `/api/v1/contexts/${HOME.id}` });
      expect(patch.request.headers.get('If-Match')).toBe('"2"');
      patch.flush({ ...HOME, name: '@house', color: '#00aa00', version: 3 });
      await settle();
    });

    it('keeps an area title and its new version when an area reorder fails meanwhile', async () => {
      await setup();
      button('Move Family up').click();
      await settle();
      expect(names('areas')).toEqual(['Family', 'Work']);
      const put = http.expectOne({ method: 'PUT', url: '/api/v1/areas/order' });

      change(nameInput('Name of Work'), 'Job');
      await settle();
      http
        .expectOne({ method: 'PATCH', url: `/api/v1/areas/${WORK.id}` })
        .flush({ ...WORK, title: 'Job', version: 2 });
      await settle();

      put.flush(...problem(500, 'Server down'));
      await settle();

      expect(names('areas')).toEqual(['Job', 'Family']);
      expect(errorText()).toContain('Server down');
      change(nameInput('Name of Job'), 'Career');
      await settle();
      const patch = http.expectOne({ method: 'PATCH', url: `/api/v1/areas/${WORK.id}` });
      expect(patch.request.headers.get('If-Match')).toBe('"2"');
      patch.flush({ ...WORK, title: 'Career', version: 3 });
      await settle();
    });

    it('keeps a list reloaded during a failing reorder, in the old order with new items last', async () => {
      await setup();
      button('Move @home down').click();
      await settle();
      const put = http.expectOne({ method: 'PUT', url: '/api/v1/contexts/order' });

      // A rename finds @phone changed elsewhere, so the list reloads while the order is saving.
      change(nameInput('Name of @phone'), '@mobile');
      await settle();
      http
        .expectOne({ method: 'PATCH', url: `/api/v1/contexts/${PHONE.id}` })
        .flush(...problem(412, 'Version mismatch'));
      await settle();
      const added: ContextResponse = { id: 'c-office', name: '@office', position: 0, version: 1 };
      http
        .expectOne({ method: 'GET', url: '/api/v1/contexts' })
        .flush({ items: [added, HOME, { ...PHONE, name: '@cell', version: 2 }] });
      await settle();

      put.flush(...problem(500, 'Server down'));
      await settle();

      // @errands was deleted elsewhere and stays gone; @office (not in the old order) goes last.
      expect(names('contexts')).toEqual(['@home', '@cell', '@office']);
      change(nameInput('Name of @cell'), '@mobile');
      await settle();
      const patch = http.expectOne({ method: 'PATCH', url: `/api/v1/contexts/${PHONE.id}` });
      expect(patch.request.headers.get('If-Match')).toBe('"2"');
      patch.flush({ ...PHONE, name: '@mobile', version: 3 });
      await settle();
    });
  });

  describe('delete edge cases', () => {
    it('moves the projects to another area when chosen, then deletes', async () => {
      await setup();
      button('Delete Work').click();
      await settle();
      const projects = [
        { id: 'p1', title: 'Fictitious launch', areaId: WORK.id, version: 3 },
        { id: 'p2', title: 'Sample audit', areaId: WORK.id, version: 1 },
      ];
      http.expectOne((r) => r.url === '/api/v1/projects').flush({ items: projects });
      await settle();

      expect(dialogText()).toContain('2 projects are in this area');
      document.querySelector<HTMLInputElement>('mat-radio-button[value="move"] input')!.click();
      await settle();
      const select = document.querySelector<HTMLSelectElement>('select.target')!;
      expect([...select.options].map((o) => o.textContent)).toEqual(['Family']);
      dialogButton('Delete').click();

      (await closedThen((r) => r.url === '/api/v1/projects')).flush({ items: projects });
      await settle();
      const p1 = http.expectOne({ method: 'PATCH', url: '/api/v1/projects/p1' });
      expect(p1.request.headers.get('If-Match')).toBe('"3"');
      expect(p1.request.body).toEqual({ areaId: FAMILY.id });
      p1.flush({ ...projects[0], areaId: FAMILY.id, version: 4 });
      await settle();
      const p2 = http.expectOne({ method: 'PATCH', url: '/api/v1/projects/p2' });
      expect(p2.request.body).toEqual({ areaId: FAMILY.id });
      p2.flush({ ...projects[1], areaId: FAMILY.id, version: 2 });
      await settle();
      http
        .expectOne({ method: 'DELETE', url: `/api/v1/areas/${WORK.id}` })
        .flush(null, { status: 204, statusText: 'No Content' });
      await settle();

      expect(names('areas')).toEqual(['Family']);
    });

    it('offers no move when no other area exists', async () => {
      await setup([HOME], [WORK]);
      button('Delete Work').click();
      await settle();
      http
        .expectOne((r) => r.url === '/api/v1/projects')
        .flush({ items: [{ id: 'p1', title: 'Fictitious launch', areaId: WORK.id, version: 1 }] });
      await settle();

      expect(document.querySelector('mat-radio-button[value="clear"]')).not.toBeNull();
      expect(document.querySelector('mat-radio-button[value="move"]')).toBeNull();
    });

    it('keeps the context, does not delete it and blames the tasks when reassigning one fails', async () => {
      await setup();
      button('Delete @home').click();
      await settle();
      const tasks = [task('t1', [HOME.id])];
      await flushAssignedTasks(tasks);
      document.querySelector<HTMLInputElement>('mat-radio-button[value="move"] input')!.click();
      await settle();
      dialogButton('Delete').click();

      (
        await closedThen((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'false')
      ).flush({ items: tasks });
      await settle();
      http
        .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'true')
        .flush({ items: [] });
      await settle();
      const patch = http.expectOne({ method: 'PATCH', url: '/api/v1/tasks/t1' });
      // The first other context is preselected.
      expect(patch.request.body).toEqual({ contextIds: [PHONE.id] });
      patch.flush(...problem(412, 'Version mismatch'));
      await settle();

      http.expectNone({ method: 'DELETE' });
      // The context list did not change, so it is not reloaded and not blamed.
      http.expectNone({ method: 'GET', url: '/api/v1/contexts' });
      expect(names('contexts')).toEqual(['@home', '@phone', '@errands']);
      expect(errorText()).toContain(
        'Some tasks were changed elsewhere in the meantime, so nothing was deleted. Please try again.',
      );
      expect(errorText()).not.toContain('the list now shows the current state');
      expect(button('Delete @home').disabled).toBe(false);
    });

    it('blames the projects when moving one fails because it was deleted elsewhere', async () => {
      await setup();
      button('Delete Work').click();
      await settle();
      const projects = [{ id: 'p1', title: 'Fictitious launch', areaId: WORK.id, version: 1 }];
      http.expectOne((r) => r.url === '/api/v1/projects').flush({ items: projects });
      await settle();
      document.querySelector<HTMLInputElement>('mat-radio-button[value="move"] input')!.click();
      await settle();
      dialogButton('Delete').click();

      (await closedThen((r) => r.url === '/api/v1/projects')).flush({ items: projects });
      await settle();
      http
        .expectOne({ method: 'PATCH', url: '/api/v1/projects/p1' })
        .flush(...problem(404, 'No such project'));
      await settle();

      http.expectNone({ method: 'DELETE' });
      http.expectNone({ method: 'GET', url: '/api/v1/areas' });
      expect(names('areas')).toEqual(['Work', 'Family']);
      expect(errorText()).toContain(
        'Some projects were changed elsewhere in the meantime, so nothing was deleted. Please try again.',
      );
    });

    it('shows the error of the move step itself when it is not a conflict', async () => {
      await setup();
      button('Delete @home').click();
      await settle();
      const tasks = [task('t1', [HOME.id])];
      await flushAssignedTasks(tasks);
      document.querySelector<HTMLInputElement>('mat-radio-button[value="move"] input')!.click();
      await settle();
      dialogButton('Delete').click();

      (
        await closedThen((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'false')
      ).flush({ items: tasks });
      await settle();
      http
        .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'true')
        .flush({ items: [] });
      await settle();
      http
        .expectOne({ method: 'PATCH', url: '/api/v1/tasks/t1' })
        .flush(...problem(500, 'Server down'));
      await settle();

      http.expectNone({ method: 'DELETE' });
      http.expectNone({ method: 'GET', url: '/api/v1/contexts' });
      expect(errorText()).toContain('Server down');
    });

    it('reloads the list when the delete itself finds the context changed elsewhere', async () => {
      await setup();
      button('Delete @phone').click();
      await settle();
      await flushAssignedTasks([]);
      dialogButton('Delete').click();

      (await closedThen({ method: 'DELETE', url: `/api/v1/contexts/${PHONE.id}` })).flush(
        ...problem(412, 'Version mismatch'),
      );
      await settle();
      http
        .expectOne({ method: 'GET', url: '/api/v1/contexts' })
        .flush({ items: [HOME, { ...PHONE, version: 2 }, ERRANDS] });
      await settle();

      expect(names('contexts')).toEqual(['@home', '@phone', '@errands']);
      expect(errorText()).toContain('the list now shows the current state');
    });

    it('keeps the area and shows the error when the delete itself fails', async () => {
      await setup();
      button('Delete Family').click();
      await settle();
      http.expectOne((r) => r.url === '/api/v1/projects').flush({ items: [] });
      await settle();
      dialogButton('Delete').click();

      (await closedThen({ method: 'DELETE', url: `/api/v1/areas/${FAMILY.id}` })).flush(
        ...problem(500, 'Server down'),
      );
      await settle();

      expect(names('areas')).toEqual(['Work', 'Family']);
      expect(errorText()).toContain('Server down');
      http.expectNone({ method: 'GET', url: '/api/v1/areas' });
    });

    it('shows the error and opens no dialog when the assigned tasks cannot be listed', async () => {
      await setup();
      button('Delete @phone').click();
      await settle();
      http
        .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'false')
        .flush(...problem(503, 'Try later'));
      await settle();

      expect(document.querySelector('mat-dialog-container')).toBeNull();
      expect(errorText()).toContain('Try later');
      expect(button('Delete @phone').disabled).toBe(false);
    });

    it('waits with the delete buttons of a list while one of its deletes is running', async () => {
      await setup();
      button('Delete @phone').click();
      await settle();
      expect(button('Delete @home').disabled).toBe(true);
      // The other list stays usable.
      expect(button('Delete Work').disabled).toBe(false);
      await flushAssignedTasks([]);
      dialogButton('Cancel').click();
      await settle();
      expect(button('Delete @home').disabled).toBe(false);
    });

    it('starts one delete only for a double click before the buttons are disabled', async () => {
      await setup();
      const remove = button('Delete @phone');
      // Both clicks land before change detection can disable the button.
      remove.click();
      remove.click();
      await settle();

      // One pair of task list requests and one dialog.
      await flushAssignedTasks([]);
      expect(document.querySelectorAll('mat-dialog-container')).toHaveLength(1);
      dialogButton('Cancel').click();
      await settle();
      http.expectNone({ method: 'DELETE' });
    });

    it('shows the generic message when moving fails with an error that is not an API error', async () => {
      await setup();
      const service = TestBed.inject(GtdSettingsService);
      vi.spyOn(service, 'deleteArea').mockRejectedValue(
        new ReassignError(new TypeError('fictitious failure')),
      );
      button('Delete Work').click();
      await settle();
      const projects = [{ id: 'p1', title: 'Fictitious launch', areaId: WORK.id, version: 1 }];
      http.expectOne((r) => r.url === '/api/v1/projects').flush({ items: projects });
      await settle();
      document.querySelector<HTMLInputElement>('mat-radio-button[value="move"] input')!.click();
      await settle();
      dialogButton('Delete').click();
      await vi.waitFor(() => expect(service.deleteArea).toHaveBeenCalledWith(WORK, FAMILY.id));
      await settle();

      expect(names('areas')).toEqual(['Work', 'Family']);
      expect(errorText()).toContain('Something went wrong.');
      expect(errorText()).not.toContain('fictitious failure');
      expect(errorText()).not.toContain('changed elsewhere');
      http.expectNone({ method: 'GET', url: '/api/v1/areas' });
      expect(button('Delete Work').disabled).toBe(false);
    });
  });
});
