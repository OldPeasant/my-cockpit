import { Route, UrlSegment } from '@angular/router';
import JournalPage from './journal-page';
import routes, { journalDay } from './journal.routes';

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
});
