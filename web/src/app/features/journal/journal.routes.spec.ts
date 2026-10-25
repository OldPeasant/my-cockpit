import { Route } from '@angular/router';
import JournalPage from './journal-page';
import routes from './journal.routes';

describe('journal routes', () => {
  it('opens the journal page for today and for a date, titled Journal', async () => {
    expect(routes.map((route) => route.path)).toEqual(['', ':date']);
    for (const route of routes as Route[]) {
      expect(route.title).toBe('Journal');
      const loaded = await (route.loadComponent as () => Promise<{ default: unknown }>)();
      expect(loaded.default).toBe(JournalPage);
    }
  });
});
