import { ApplicationRef } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { ScopeDialog, ScopeDialogData, ScopePrompt } from './scope-dialog';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('ScopeDialog', () => {
  let fixture: ComponentFixture<ScopeDialog>;
  let ref: { close: ReturnType<typeof vi.fn> };

  const el = () => fixture.nativeElement as HTMLElement;
  const options = () => [...el().querySelectorAll<HTMLButtonElement>('button.option')];

  async function open(data: ScopeDialogData) {
    ref = { close: vi.fn() };
    TestBed.configureTestingModule({
      imports: [ScopeDialog],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref },
      ],
    });
    fixture = TestBed.createComponent(ScopeDialog);
    fixture.detectChanges();
    await tick();
  }

  it('offers the three scopes of a change', async () => {
    await open({ action: 'change', scopes: ['THIS', 'FOLLOWING', 'ALL'] });
    expect(el().querySelector('h2')!.textContent!.trim()).toBe('Change a repeating event');
    expect(options().map((b) => b.dataset['scope'])).toEqual(['THIS', 'FOLLOWING', 'ALL']);
    expect(options().map((b) => b.textContent!.trim())).toEqual([
      'Only this event',
      'This and following events',
      'All events in the series',
    ]);
    expect(el().querySelector('[role="group"]')!.getAttribute('aria-labelledby')).toBe(
      'calendar-scope-question',
    );
  });

  it('offers only the given scopes, e.g. no single occurrence for a new repeat rule', async () => {
    await open({ action: 'change', scopes: ['FOLLOWING', 'ALL'] });
    expect(options().map((b) => b.dataset['scope'])).toEqual(['FOLLOWING', 'ALL']);
  });

  it('titles a delete as such', async () => {
    await open({ action: 'delete', scopes: ['THIS', 'FOLLOWING', 'ALL'] });
    expect(el().querySelector('h2')!.textContent!.trim()).toBe('Delete a repeating event');
  });

  it('closes with the chosen scope', async () => {
    await open({ action: 'change', scopes: ['THIS', 'FOLLOWING', 'ALL'] });
    options()[1].click();
    expect(ref.close).toHaveBeenCalledExactlyOnceWith('FOLLOWING');
  });

  it('closes without a scope on cancel', async () => {
    await open({ action: 'change', scopes: ['THIS', 'FOLLOWING', 'ALL'] });
    const cancel = [...el().querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent!.trim() === 'Cancel',
    )!;
    cancel.click();
    expect(ref.close).toHaveBeenCalledTimes(1);
    expect(ref.close.mock.calls[0][0]).toBeUndefined();
  });
});

describe('ScopePrompt', () => {
  it('opens the scope dialog with all scopes by default and resolves the choice', async () => {
    const open = vi.fn(() => ({ afterClosed: () => of('ALL') }));
    TestBed.configureTestingModule({ providers: [{ provide: MatDialog, useValue: { open } }] });
    const answer = await TestBed.inject(ScopePrompt).ask('change');
    expect(answer).toBe('ALL');
    expect(open).toHaveBeenCalledWith(
      ScopeDialog,
      expect.objectContaining({ data: { action: 'change', scopes: ['THIS', 'FOLLOWING', 'ALL'] } }),
    );
  });

  it('passes the offered scopes and resolves undefined on cancel', async () => {
    const open = vi.fn(() => ({ afterClosed: () => of(undefined) }));
    TestBed.configureTestingModule({ providers: [{ provide: MatDialog, useValue: { open } }] });
    const answer = await TestBed.inject(ScopePrompt).ask('delete', ['FOLLOWING', 'ALL']);
    expect(answer).toBeUndefined();
    expect(open).toHaveBeenCalledWith(
      ScopeDialog,
      expect.objectContaining({ data: { action: 'delete', scopes: ['FOLLOWING', 'ALL'] } }),
    );
  });

  describe('with the real dialog', () => {
    afterEach(() => {
      TestBed.inject(MatDialog).closeAll();
      document.querySelectorAll('.cdk-overlay-container').forEach((o) => o.remove());
    });

    it('resolves the scope clicked in the opened dialog', async () => {
      const answer = TestBed.inject(ScopePrompt).ask('change', ['FOLLOWING', 'ALL']);
      TestBed.inject(ApplicationRef).tick();
      await tick();
      const buttons = [...document.querySelectorAll<HTMLButtonElement>('button.option')];
      expect(buttons.map((b) => b.dataset['scope'])).toEqual(['FOLLOWING', 'ALL']);
      buttons[0].click();
      TestBed.inject(ApplicationRef).tick();
      await expect(answer).resolves.toBe('FOLLOWING');
    });

    it('resolves undefined when Cancel is clicked', async () => {
      const answer = TestBed.inject(ScopePrompt).ask('change');
      TestBed.inject(ApplicationRef).tick();
      await tick();
      const cancel = [...document.querySelectorAll<HTMLButtonElement>('button')].find(
        (b) => b.textContent!.trim() === 'Cancel',
      )!;
      cancel.click();
      TestBed.inject(ApplicationRef).tick();
      await expect(answer).resolves.toBeUndefined();
    });
  });
});
