import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ContextResponse, ProjectResponse, TaskResponse } from '../../../api/generated';
import { TaskRow } from './task-row';

const TODAY = '2026-10-20';

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

const HOME = { id: 'c1', name: '@home', color: '#ff0000', position: 1 } as ContextResponse;
const PHONE = { id: 'c2', name: '@phone', position: 2 } as ContextResponse;
const PROJECT = { id: 'p1', title: 'Renovate kitchen' } as ProjectResponse;

describe('TaskRow', () => {
  let fixture: ComponentFixture<TaskRow>;
  let element: HTMLElement;

  function render(value: TaskResponse, inputs: Record<string, unknown> = {}): void {
    fixture = TestBed.createComponent(TaskRow);
    fixture.componentRef.setInput('task', value);
    fixture.componentRef.setInput('today', TODAY);
    fixture.componentRef.setInput('contexts', new Map([HOME, PHONE].map((c) => [c.id, c])));
    fixture.componentRef.setInput('projects', new Map([[PROJECT.id, PROJECT]]));
    for (const [name, input] of Object.entries(inputs)) fixture.componentRef.setInput(name, input);
    fixture.detectChanges();
    element = fixture.nativeElement;
  }

  const chips = () =>
    [...element.querySelectorAll('.chip')].map((chip) => chip.textContent!.trim());

  it('shows the title, an unchecked checkbox and no chips for a bare task', () => {
    render(task());

    expect(element.querySelector('.title')?.textContent?.trim()).toBe('Call the plumber');
    const check = element.querySelector<HTMLInputElement>('input[type=checkbox]')!;
    expect(check.checked).toBe(false);
    expect(check.getAttribute('aria-label')).toBe('Complete “Call the plumber”');
    expect(chips()).toEqual([]);
  });

  it('shows chips for contexts, energy, estimate, due date and project', () => {
    render(
      task({
        contextIds: ['c2', 'c1', 'unknown'],
        energy: 'HIGH',
        timeEstimateMin: 90,
        dueDate: '2026-10-25',
        projectId: 'p1',
      }),
    );

    expect(chips()).toEqual([
      '@phone',
      '@home',
      'High energy',
      '1 h 30 min',
      'Due Oct 25',
      'Renovate kitchen',
    ]);
    expect(element.querySelector<HTMLElement>('.chip.context .dot')?.style.background).toBe('');
  });

  it('marks overdue and today due dates', () => {
    render(task({ dueDate: '2026-10-19' }));
    expect(element.querySelector('.chip.due.overdue')?.textContent?.trim()).toBe(
      'Overdue · Oct 19',
    );

    render(task({ dueDate: TODAY }));
    expect(element.querySelector('.chip.due.today')?.textContent?.trim()).toBe('Due today');
  });

  it('shows a done task checked, struck through and never overdue', () => {
    render(task({ completedAt: '2026-10-20T09:00:00Z', dueDate: '2026-10-01' }));

    const check = element.querySelector<HTMLInputElement>('input[type=checkbox]')!;
    expect(check.checked).toBe(true);
    expect(check.getAttribute('aria-label')).toBe('Reopen “Call the plumber”');
    expect(element.querySelector('.row.done')).not.toBeNull();
    expect(element.querySelector('.chip.due.overdue')).toBeNull();
  });

  it('hides the project chip when showProject is off', () => {
    render(task({ projectId: 'p1' }), { showProject: false });
    expect(chips()).toEqual([]);
  });

  it('emits toggle from the checkbox without changing it, and open from the title', () => {
    const value = task();
    render(value);
    const toggled: TaskResponse[] = [];
    const opened: TaskResponse[] = [];
    fixture.componentInstance.toggleDone.subscribe((t) => toggled.push(t));
    fixture.componentInstance.open.subscribe((t) => opened.push(t));

    const check = element.querySelector<HTMLInputElement>('input[type=checkbox]')!;
    check.click();
    element.querySelector<HTMLButtonElement>('.title')!.click();

    expect(toggled).toEqual([value]);
    expect(opened).toEqual([value]);
    expect(check.checked).toBe(false);
  });
});
