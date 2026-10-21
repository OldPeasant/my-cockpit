import { TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { ApiError } from '../../../core/http';
import { MAX_NAME_LENGTH, NameDialog, NameDialogData, nameError } from './name-dialog';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('nameError', () => {
  it('accepts ordinary names, also with dots inside', () => {
    expect(nameError('Taxes 2026')).toBeNull();
    expect(nameError('.hidden')).toBeNull();
    expect(nameError('a..b')).toBeNull();
  });

  it('requires a name that is not blank', () => {
    expect(nameError('')).toEqual({ required: true });
    expect(nameError(' \t ')).toEqual({ required: true });
  });

  it('rejects slashes, backslashes, . and .. (after trimming)', () => {
    expect(nameError('a/b')).toEqual({ invalidName: true });
    expect(nameError('a\\b')).toEqual({ invalidName: true });
    expect(nameError(' . ')).toEqual({ invalidName: true });
    expect(nameError('..')).toEqual({ invalidName: true });
  });
});

describe('NameDialog', () => {
  let ref: MatDialogRef<NameDialog, boolean>;
  let el: HTMLElement;
  let save: ReturnType<typeof vi.fn<(name: string) => Promise<void>>>;

  afterEach(() => TestBed.inject(MatDialog).closeAll());

  async function open(name?: string): Promise<void> {
    save = vi.fn<(name: string) => Promise<void>>().mockResolvedValue(undefined);
    const data: NameDialogData = {
      title: 'Rename',
      label: 'New name',
      confirm: 'Rename',
      name,
      save,
    };
    ref = TestBed.inject(MatDialog).open<NameDialog, NameDialogData, boolean>(NameDialog, {
      data,
    });
    await settle();
    el = document.querySelector('app-files-name-dialog') as HTMLElement;
  }

  async function settle(): Promise<void> {
    await tick();
    TestBed.tick();
    await tick();
    TestBed.tick();
  }

  async function submit(value?: string): Promise<void> {
    const input = el.querySelector('input') as HTMLInputElement;
    if (value !== undefined) {
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await settle();
  }

  it('limits the input to the longest name the server accepts', async () => {
    await open();
    expect(el.querySelector('input')?.getAttribute('maxlength')).toBe(String(MAX_NAME_LENGTH));
  });

  it('saves the trimmed name and closes with true', async () => {
    await open('Report.pdf');
    const result = firstValueFrom(ref.afterClosed());

    await submit('  Report 2026.pdf ');

    expect(save).toHaveBeenCalledWith('Report 2026.pdf');
    expect(await result).toBe(true);
  });

  it('closes with false without saving when the name is unchanged (after trimming)', async () => {
    await open('Report.pdf');
    const result = firstValueFrom(ref.afterClosed());

    await submit('  Report.pdf  ');

    expect(save).not.toHaveBeenCalled();
    expect(await result).toBe(false);
  });

  it('shows the first field error of a validation problem and stays open', async () => {
    await open();
    save.mockRejectedValueOnce(
      new ApiError(400, 'Bad Request', 'Validation failed', undefined, undefined, [
        { field: 'name', message: 'Name must not end with a space' },
        { field: 'name', message: 'second message' },
      ]),
    );

    await submit('Taxes');

    expect(el.querySelector('.error[role="alert"]')?.textContent?.trim()).toBe(
      'Name must not end with a space',
    );
    expect(ref.getState()).toBe(0); // MatDialogState.OPEN
    expect((el.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(false);
  });

  it('shows the problem detail without field errors, and a generic text for other errors', async () => {
    await open();
    save.mockRejectedValueOnce(new ApiError(409, 'Conflict', 'Name taken'));
    await submit('Taxes');
    expect(el.querySelector('.error')?.textContent?.trim()).toBe('Name taken');

    save.mockRejectedValueOnce(new TypeError('boom'));
    await submit('Taxes');
    expect(el.querySelector('.error')?.textContent?.trim()).toBe('Something went wrong.');
  });

  it('clears the previous error and saves on a successful retry', async () => {
    await open();
    save.mockRejectedValueOnce(new ApiError(409, 'Conflict', 'Name taken'));
    await submit('Taxes');
    const result = firstValueFrom(ref.afterClosed());

    await submit('Taxes 2');

    expect(save).toHaveBeenLastCalledWith('Taxes 2');
    expect(await result).toBe(true);
  });

  it('disables submit while saving and ignores a second submit', async () => {
    await open();
    let release!: () => void;
    save.mockImplementationOnce(() => new Promise<void>((resolve) => (release = resolve)));

    await submit('Taxes');
    const button = el.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    // A form submit (e.g. Enter in the field) while saving does not save again.
    el.querySelector('form')?.dispatchEvent(new Event('submit'));
    await settle();
    expect(save).toHaveBeenCalledTimes(1);

    const result = firstValueFrom(ref.afterClosed());
    release();
    expect(await result).toBe(true);
  });

  it('cannot be closed while saving, and can again after a failed save', async () => {
    await open();
    let fail!: (error: Error) => void;
    save.mockImplementationOnce(() => new Promise<void>((_, reject) => (fail = reject)));
    const escape = () =>
      el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true }));
    const cancel = () =>
      [...el.querySelectorAll<HTMLButtonElement>('button')].find(
        (b) => b.textContent?.trim() === 'Cancel',
      ) as HTMLButtonElement;

    await submit('Taxes');
    expect(cancel().disabled).toBe(true);
    escape();
    await settle();
    expect(ref.getState()).toBe(0); // MatDialogState.OPEN

    fail(new ApiError(409, 'Conflict', 'Name taken'));
    await settle();
    expect(cancel().disabled).toBe(false);
    const result = firstValueFrom(ref.afterClosed());
    escape();
    expect(await result).toBeUndefined();
    expect(save).toHaveBeenCalledTimes(1);
  });

  it('does not save an invalid name', async () => {
    await open();
    await submit('..');
    expect(save).not.toHaveBeenCalled();
    expect(el.textContent).toContain('cannot contain');
  });
});
