import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Api, listMailMessages, listTasks } from '../../api/generated';
import { routes } from '../../app.routes';
import { provideFakeSession } from '../../core/auth/testing';
import { provideSilentEventSource } from '../../core/events/testing';
import { NAV_GROUPS, NAV_SECTIONS } from '../../shell/nav-sections';

describe('New view navigation', () => {
  it('puts "New" right after Dashboard and right before Inbox', () => {
    const ids = NAV_SECTIONS.map((section) => section.id);
    const at = ids.indexOf('new');
    expect(ids.slice(at - 1, at + 2)).toEqual(['dashboard', 'new', 'inbox']);
    expect(NAV_SECTIONS[at]).toMatchObject({ path: '/new', label: 'New' });
    // Recorded assumption: in the Home group, no group of its own.
    expect(NAV_GROUPS.find((group) => group.id === 'home')!.sections.map((s) => s.id)).toEqual([
      'dashboard',
      'new',
    ]);
  });

  it('lazy-loads the New page at /new and marks its navigation entry active', async () => {
    const api = {
      invoke: async (fn: unknown) => {
        if (fn === listTasks) return { items: [] };
        if (fn === listMailMessages) return { items: [] };
        throw new Error('unexpected call');
      },
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideFakeSession(),
        provideSilentEventSource(),
        { provide: Api, useValue: api },
      ],
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/new?source=mail');
    await vi.waitFor(() => {
      harness.detectChanges();
      const root = harness.routeNativeElement as HTMLElement;
      expect(root.querySelector('app-new-page h1')?.textContent?.trim()).toBe('New');
    });
    const root = harness.routeNativeElement as HTMLElement;
    expect(root.querySelector('app-coming-soon')).toBeNull();
    expect(document.title).toBe('New');
    const active = root.querySelector('a.link.active');
    expect(active?.textContent?.trim()).toBe('New');
    expect(active?.getAttribute('aria-current')).toBe('page');
  });
});
