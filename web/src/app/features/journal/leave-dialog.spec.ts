import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { LeaveChoice, LeaveDialog, LeavePrompt } from './leave-dialog';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('LeavePrompt', () => {
  it('opens the leave dialog and resolves the choice', async () => {
    const open = vi.fn(() => ({ afterClosed: () => of<LeaveChoice>('keep-mine') }));
    TestBed.configureTestingModule({ providers: [{ provide: MatDialog, useValue: { open } }] });

    await expect(TestBed.inject(LeavePrompt).ask()).resolves.toBe('keep-mine');
    expect(open).toHaveBeenCalledExactlyOnceWith(LeaveDialog, expect.any(Object));
  });

  it('stays when the dialog closes without a choice', async () => {
    const open = vi.fn(() => ({ afterClosed: () => of(undefined) }));
    TestBed.configureTestingModule({ providers: [{ provide: MatDialog, useValue: { open } }] });

    await expect(TestBed.inject(LeavePrompt).ask()).resolves.toBe('stay');
  });

  describe('with the real dialog', () => {
    afterEach(() => {
      TestBed.inject(MatDialog).closeAll();
      document.querySelectorAll('.cdk-overlay-container').forEach((o) => o.remove());
    });

    /** Asks and renders the dialog; returns the pending answer. */
    async function ask(): Promise<{ answer: Promise<LeaveChoice> }> {
      const answer = TestBed.inject(LeavePrompt).ask();
      TestBed.inject(ApplicationRef).tick();
      await tick();
      return { answer };
    }

    function dialog(): HTMLElement {
      const found = document.querySelector<HTMLElement>('app-journal-leave-dialog');
      if (!found) throw new Error('no leave dialog');
      return found;
    }

    function text(el: Element | null): string {
      return el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
    }

    it('explains the conflict and offers stay, discard and keep mine, in that order', async () => {
      const { answer } = await ask();

      expect(text(dialog().querySelector('h2'))).toBe('Leave without saving?');
      expect(text(dialog().querySelector('mat-dialog-content p'))).toBe(
        'This entry was changed somewhere else since you opened it, and your edits are not saved yet.',
      );
      const buttons = [
        ...dialog().querySelectorAll<HTMLButtonElement>('mat-dialog-actions button'),
      ];
      expect(buttons.map((b) => b.className.match(/\b(stay|discard|keep-mine)\b/)?.[1])).toEqual([
        'stay',
        'discard',
        'keep-mine',
      ]);
      expect(buttons.map((b) => text(b))).toEqual(['Stay', 'Discard my edits', 'Save my version']);
      expect(buttons.every((b) => b.type === 'button')).toBe(true);

      TestBed.inject(MatDialog).closeAll();
      await expect(answer).resolves.toBe('stay');
    });

    for (const choice of ['stay', 'discard', 'keep-mine'] as const) {
      it(`resolves '${choice}' when its button is clicked`, async () => {
        const { answer } = await ask();
        dialog().querySelector<HTMLButtonElement>(`button.${choice}`)!.click();
        TestBed.inject(ApplicationRef).tick();
        await expect(answer).resolves.toBe(choice);
      });
    }

    it('stays when the dialog is dismissed with Escape', async () => {
      const { answer } = await ask();
      const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true });
      Object.defineProperty(escape, 'keyCode', { value: 27 });
      dialog().dispatchEvent(escape);
      TestBed.inject(ApplicationRef).tick();
      await expect(answer).resolves.toBe('stay');
    });

    it('stays when the backdrop is clicked', async () => {
      const { answer } = await ask();
      const backdrop = document.querySelector<HTMLElement>('.cdk-overlay-backdrop');
      expect(backdrop).not.toBeNull();
      backdrop!.click();
      TestBed.inject(ApplicationRef).tick();
      await expect(answer).resolves.toBe('stay');
    });
  });
});
