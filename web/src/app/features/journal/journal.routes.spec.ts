import {
  ActivatedRouteSnapshot,
  convertToParamMap,
  Route,
  RouterStateSnapshot,
  UrlSegment,
} from '@angular/router';
import JournalPage from './journal-page';
import routes, { journalDay, leaveDay } from './journal.routes';

describe('journal routes', () => {
  it('opens the journal page for today and for a date in one route, titled Journal', async () => {
    expect(routes).toHaveLength(1);
    const [route] = routes as Route[];
    expect(route.title).toBe('Journal');
    const loaded = await (route.loadComponent as () => Promise<{ default: unknown }>)();
    expect(loaded.default).toBe(JournalPage);
  });

  it('matches /journal without and /journal/:date with a date param, nothing deeper', () => {
    const segment = (path: string): UrlSegment => new UrlSegment(path, {});
    const match = (paths: string[]) => journalDay(paths.map(segment));

    expect(match([])).toEqual({ consumed: [] });
    const day = match(['2026-10-24']);
    expect(day?.consumed.map((s) => s.path)).toEqual(['2026-10-24']);
    expect(day?.posParams?.['date'].path).toBe('2026-10-24');
    expect(match(['2026-10-24', 'more'])).toBeNull();
  });

  it('asks the page before leaving a day', () => {
    const [route] = routes as Route[];
    expect(route.canDeactivate).toEqual([leaveDay]);
  });

  describe('leaveDay', () => {
    /** A route snapshot tree: the root, a parent per level, the given leaf at the bottom. */
    function next(leaf: Partial<ActivatedRouteSnapshot>, depth = 2): RouterStateSnapshot {
      let node = { firstChild: null, ...leaf } as unknown as ActivatedRouteSnapshot;
      for (let i = 0; i < depth; i++) {
        node = { firstChild: node, routeConfig: { path: `level-${i}` } } as ActivatedRouteSnapshot;
      }
      return { root: node } as RouterStateSnapshot;
    }

    function leave(target: RouterStateSnapshot) {
      const canLeave = vi.fn<(next: string | null | undefined) => Promise<boolean>>();
      canLeave.mockResolvedValue(false);
      const page = { canLeave } as unknown as JournalPage;
      const result = leaveDay(
        page,
        {} as ActivatedRouteSnapshot,
        {} as RouterStateSnapshot,
        target,
      );
      return { canLeave, result };
    }

    it('passes the date of another journal day, and returns the answer of the page', async () => {
      const { canLeave, result } = leave(
        next({
          routeConfig: { matcher: journalDay },
          paramMap: convertToParamMap({ date: '2026-10-22' }),
        }),
      );
      expect(canLeave).toHaveBeenCalledExactlyOnceWith('2026-10-22');
      await expect(result).resolves.toBe(false);
    });

    it('passes null for today (/journal)', () => {
      const { canLeave } = leave(
        next({ routeConfig: { matcher: journalDay }, paramMap: convertToParamMap({}) }),
      );
      expect(canLeave).toHaveBeenCalledExactlyOnceWith(null);
    });

    it('passes undefined for another page, even with a date param of its own', () => {
      const { canLeave } = leave(
        next({
          routeConfig: { path: 'elsewhere/:date' },
          paramMap: convertToParamMap({ date: '2026-10-22' }),
        }),
      );
      expect(canLeave).toHaveBeenCalledExactlyOnceWith(undefined);
    });

    it('looks at the deepest route only, and passes undefined for a root without a config', () => {
      const leafOnly = leave(next({ routeConfig: null }, 0));
      expect(leafOnly.canLeave).toHaveBeenCalledExactlyOnceWith(undefined);

      const deep = leave(
        next(
          {
            routeConfig: { matcher: journalDay },
            paramMap: convertToParamMap({ date: '2026-10-20' }),
          },
          4,
        ),
      );
      expect(deep.canLeave).toHaveBeenCalledExactlyOnceWith('2026-10-20');
    });
  });
});
