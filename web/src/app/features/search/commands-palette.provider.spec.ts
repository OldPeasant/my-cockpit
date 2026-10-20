import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { CAPTURE_DIALOG, PaletteItem } from '../../shell/shell-tokens';
import { CommandsPaletteProvider } from './commands-palette.provider';

describe('CommandsPaletteProvider', () => {
  function setup(withCapture: boolean): {
    provider: CommandsPaletteProvider;
    open: ReturnType<typeof vi.fn>;
  } {
    const open = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        CommandsPaletteProvider,
        ...(withCapture ? [{ provide: CAPTURE_DIALOG, useValue: { open } }] : []),
      ],
    });
    return { provider: TestBed.inject(CommandsPaletteProvider), open };
  }

  function rows(provider: CommandsPaletteProvider, query: string): Promise<readonly PaletteItem[]> {
    return firstValueFrom(provider.items(query));
  }

  it('offers "New task" in the Commands group with the Q hint for an empty query', async () => {
    const { provider } = setup(true);
    const items = await rows(provider, '');
    expect(items.map((i) => [i.id, i.group, i.label, i.hint])).toEqual([
      ['command-new-task', 'Commands', 'New task', 'Q'],
    ]);
  });

  it('opens the quick capture dialog when "New task" runs', async () => {
    const { provider, open } = setup(true);
    const [newTask] = await rows(provider, 'new');
    expect(open).not.toHaveBeenCalled();
    newTask.run();
    expect(open).toHaveBeenCalledTimes(1);
  });

  it.each(['new', 'NEW TASK', '  Task  ', 'w t', 'k'])(
    'matches the query %j case-insensitively after trimming',
    async (query) => {
      const { provider } = setup(true);
      expect((await rows(provider, query)).map((i) => i.label)).toEqual(['New task']);
    },
  );

  it.each(['passport', 'new tasks', 'task new'])(
    'offers nothing for the non-matching query %j',
    async (query) => {
      const { provider } = setup(true);
      expect(await rows(provider, query)).toEqual([]);
    },
  );

  it('offers no command when no quick capture dialog is provided', async () => {
    const { provider } = setup(false);
    expect(await rows(provider, '')).toEqual([]);
    expect(await rows(provider, 'new')).toEqual([]);
  });
});
