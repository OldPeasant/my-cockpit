import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { DeleteDialog, DeleteDialogData } from './delete-dialog';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const OTHERS = [
  { id: 'c-phone', name: '@phone' },
  { id: 'c-errands', name: '@errands' },
];

describe('DeleteDialog', () => {
  let fixture: ComponentFixture<DeleteDialog>;
  let root: HTMLElement;
  let ref: { close: ReturnType<typeof vi.fn> };

  async function open(data: DeleteDialogData): Promise<void> {
    ref = { close: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref },
      ],
    });
    fixture = TestBed.createComponent(DeleteDialog);
    root = fixture.nativeElement as HTMLElement;
    await settle();
  }

  async function settle(): Promise<void> {
    await tick();
    await fixture.whenStable();
  }

  function buttonWith(text: string): HTMLButtonElement {
    return [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === text,
    )!;
  }

  async function chooseMove(): Promise<void> {
    root.querySelector<HTMLInputElement>('mat-radio-button[value="move"] input')!.click();
    await settle();
  }

  it('only confirms a context no task uses, with no choices', async () => {
    await open({ kind: 'context', name: '@errands', count: 0, others: OTHERS });
    expect(root.textContent).toContain('Delete @errands?');
    expect(root.textContent).toContain('No task uses this context.');
    expect(root.querySelector('mat-radio-group')).toBeNull();

    buttonWith('Delete').click();
    expect(ref.close).toHaveBeenCalledWith({ moveTo: null });
  });

  it('says when no project is in an area', async () => {
    await open({ kind: 'area', name: 'Work', count: 0, others: [] });
    expect(root.textContent).toContain('No project is in this area.');
  });

  it('asks about the assigned tasks and removes the context from them by default', async () => {
    await open({ kind: 'context', name: '@home', count: 3, others: OTHERS });
    expect(root.textContent).toContain('3 tasks use this context');
    expect(root.textContent).toContain('Remove @home from them');
    expect(root.textContent).toContain('Give them another context instead');
    expect(root.querySelector('select.target')).toBeNull();

    buttonWith('Delete').click();
    expect(ref.close).toHaveBeenCalledWith({ moveTo: null });
  });

  it('uses the singular for one assigned item', async () => {
    await open({ kind: 'area', name: 'Work', count: 1, others: [{ id: 'a-f', name: 'Family' }] });
    expect(root.textContent).toContain('1 project is in this area');
    expect(root.textContent).toContain('Leave them without an area');
    expect(root.textContent).toContain('Move them to another area');
  });

  it('moves to the first other one unless another is picked', async () => {
    await open({ kind: 'context', name: '@home', count: 2, others: OTHERS });
    await chooseMove();
    const select = root.querySelector<HTMLSelectElement>('select.target')!;
    expect([...select.options].map((o) => o.textContent)).toEqual(['@phone', '@errands']);

    buttonWith('Delete').click();
    expect(ref.close).toHaveBeenLastCalledWith({ moveTo: 'c-phone' });

    select.value = 'c-errands';
    select.dispatchEvent(new Event('change'));
    await settle();
    buttonWith('Delete').click();
    expect(ref.close).toHaveBeenLastCalledWith({ moveTo: 'c-errands' });
  });

  it('goes back to clearing when the clear choice is picked again', async () => {
    await open({ kind: 'context', name: '@home', count: 2, others: OTHERS });
    await chooseMove();
    root.querySelector<HTMLInputElement>('mat-radio-button[value="clear"] input')!.click();
    await settle();
    expect(root.querySelector('select.target')).toBeNull();

    buttonWith('Delete').click();
    expect(ref.close).toHaveBeenCalledWith({ moveTo: null });
  });

  it('offers no move without another context or area', async () => {
    await open({ kind: 'area', name: 'Work', count: 2, others: [] });
    expect(root.querySelector('mat-radio-button[value="clear"]')).not.toBeNull();
    expect(root.querySelector('mat-radio-button[value="move"]')).toBeNull();

    buttonWith('Delete').click();
    expect(ref.close).toHaveBeenCalledWith({ moveTo: null });
  });

  it('closes without a result on cancel', async () => {
    await open({ kind: 'context', name: '@home', count: 2, others: OTHERS });
    buttonWith('Cancel').click();
    expect(ref.close).toHaveBeenCalledTimes(1);
    // A bare `mat-dialog-close` closes with '' (no result object): the caller treats it as cancel.
    expect(ref.close.mock.calls[0][0]).toBeFalsy();
  });
});
