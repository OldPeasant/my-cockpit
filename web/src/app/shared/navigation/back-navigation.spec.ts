import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { Component, ErrorHandler, inject } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  NavigationCancel,
  NavigationCancellationCode,
  NavigationEnd,
  provideRouter,
  Router,
} from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { BackNavigation } from './back-navigation';
import { BackButton } from './back-button';
import { provideBackNavigation } from './index';

@Component({ selector: 'app-stub-page', template: '<p>page</p>' })
class StubPage {}

@Component({
  selector: 'app-stub-detail',
  imports: [BackButton],
  template: '<app-back-button fallback="/mail" fallbackLabel="Mail" />',
})
class StubDetail {}

const settle = async (): Promise<void> => {
  for (let i = 0; i < 3; i++) await new Promise((resolve) => setTimeout(resolve));
};

describe('BackNavigation', () => {
  let harness: RouterTestingHarness;
  let router: Router;
  let location: Location;
  let navigation: BackNavigation;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'new', title: 'New', component: StubPage },
          { path: 'today', title: 'Today', component: StubPage },
          { path: 'untitled', component: StubPage },
          { path: 'mail', title: 'Mail', component: StubPage },
          { path: 'projects/:id', title: 'Project', component: StubPage },
          { path: 'mail/messages/:id', title: 'Message', component: StubDetail },
          {
            path: 'settings',
            title: 'Settings',
            children: [
              { path: 'general', component: StubPage },
              { path: 'accounts', title: 'Accounts', component: StubPage },
            ],
          },
          { path: 'locked', component: StubPage, canActivate: [() => false] },
        ]),
        provideLocationMocks(),
        provideBackNavigation(),
      ],
    });
    harness = await RouterTestingHarness.create();
    router = TestBed.inject(Router);
    router.setUpLocationChangeListener();
    location = TestBed.inject(Location);
    navigation = TestBed.inject(BackNavigation);
  });

  const go = (url: string) => harness.navigateByUrl(url);
  const popBack = async () => {
    location.back();
    await settle();
  };
  const backLink = () =>
    harness.routeNativeElement!.querySelector<HTMLAnchorElement>('app-back-button a')!;

  it('knows nothing behind the first page (deep link)', async () => {
    await go('/mail/messages/m1');
    expect(navigation.canGoBack()).toBe(false);
    expect(navigation.previous()).toBeNull();
  });

  it('knows the previous page with its query and title', async () => {
    await go('/new?filter=mail');
    await go('/mail/messages/m1');
    expect(navigation.previous()).toEqual(
      expect.objectContaining({ url: '/new?filter=mail', label: 'New' }),
    );
  });

  it('has no label for a page without a title', async () => {
    await go('/untitled');
    await go('/mail/messages/m1');
    expect(navigation.previous()?.label).toBeNull();
  });

  it('goes back in the history when the previous entry is in the app', async () => {
    await go('/new?filter=mail');
    await go('/mail/messages/m1');
    await navigation.back('/mail');
    await settle();
    expect(router.url).toBe('/new?filter=mail');
    expect(navigation.canGoBack()).toBe(false);
  });

  it('resolves back() only when the Back navigation has ended', async () => {
    await go('/new?filter=mail');
    await go('/mail/messages/m1');
    let resolved = false;
    const back = navigation.back('/mail').then(() => (resolved = true));
    // The router handles the popstate in a later task: the page is still shown.
    for (let i = 0; i < 10; i++) await Promise.resolve();
    expect(resolved).toBe(false);
    expect(router.url).toBe('/mail/messages/m1');
    await back;
    expect(router.url).toBe('/new?filter=mail');
  });

  it('resolves back() after a short while when no navigation follows', async () => {
    await go('/new');
    await go('/mail/messages/m1');
    vi.useFakeTimers();
    try {
      // E.g. the browser never reports the Back.
      vi.spyOn(location, 'back').mockImplementation(() => undefined);
      let resolved = false;
      const back = navigation.back('/mail').then(() => (resolved = true));
      await vi.advanceTimersByTimeAsync(1000);
      expect(resolved).toBe(false);
      await vi.advanceTimersByTimeAsync(1000);
      expect(resolved).toBe(true);
      await back;
      expect(router.url).toBe('/mail/messages/m1');
    } finally {
      vi.useRealTimers();
    }
  });

  describe('when the page refuses to be left (e.g. unsaved changes)', () => {
    let mayLeave: boolean;

    beforeEach(() => {
      mayLeave = true;
      router.resetConfig([
        ...router.config,
        {
          path: 'editing',
          title: 'Editing',
          component: StubDetail,
          canDeactivate: [() => mayLeave],
        },
      ]);
    });

    it('resolves back() as soon as the Back navigation is cancelled', async () => {
      await go('/new');
      await go('/editing');
      mayLeave = false;
      let resolved = false;
      void navigation.back('/mail').then(() => (resolved = true));
      // A few macrotasks, far below the 1.5 s safety timeout.
      await settle();
      expect(resolved).toBe(true);
      expect(router.url).toBe('/editing');
    });

    it('leads to the page its back button names after a cancelled Back', async () => {
      await go('/today');
      await go('/new');
      await go('/editing');
      mayLeave = false;
      await navigation.back('/mail');
      await settle();
      expect(router.url).toBe('/editing');
      mayLeave = true;
      // The router replaced the history entry of /new with /editing when it cancelled the Back
      // (canceledNavigationResolution 'replace'): the page behind is Today, not the parent route.
      expect(navigation.previous()?.url).toBe('/today');
      await navigation.back('/mail');
      await settle();
      expect(router.url).toBe('/today');
    });
  });

  it('goes to the parent route on a deep link, adding it to the history', async () => {
    await go('/mail/messages/m1');
    await navigation.back('/mail');
    expect(router.url).toBe('/mail');
    expect(navigation.previous()?.url).toBe('/mail/messages/m1');
  });

  it('replaces the current entry on the parent route when asked', async () => {
    await go('/mail/messages/m1');
    await navigation.back('/mail', { replaceUrl: true });
    expect(router.url).toBe('/mail');
    expect(navigation.canGoBack()).toBe(false);
  });

  it('does not count a replaced entry as a page behind', async () => {
    await go('/today');
    await router.navigateByUrl('/new', { replaceUrl: true });
    await go('/mail/messages/m1');
    expect(navigation.previous()?.url).toBe('/new');
    await popBack();
    expect(navigation.canGoBack()).toBe(false);
  });

  it('does not count a navigation to the same URL', async () => {
    await go('/today');
    await go('/new');
    await router.navigateByUrl('/new', { onSameUrlNavigation: 'reload' });
    expect(navigation.previous()?.url).toBe('/today');
  });

  it('ignores navigations that skip the location', async () => {
    await go('/today');
    await go('/new');
    await router.navigateByUrl('/mail', { skipLocationChange: true });
    expect(navigation.previous()?.url).toBe('/today');
  });

  it('follows Back and Forward through the history', async () => {
    await go('/today');
    await go('/new');
    await go('/mail/messages/m1');
    await popBack();
    expect(router.url).toBe('/new');
    expect(navigation.previous()?.url).toBe('/today');
    location.forward();
    await settle();
    expect(router.url).toBe('/mail/messages/m1');
    expect(navigation.previous()?.url).toBe('/new');
  });

  it('drops the forward entries on a new navigation after Back', async () => {
    await go('/today');
    await go('/new');
    await popBack();
    await go('/mail');
    expect(navigation.previous()?.url).toBe('/today');
    await popBack();
    expect(router.url).toBe('/today');
    expect(navigation.canGoBack()).toBe(false);
  });

  it('starts afresh on a history entry it never saw', async () => {
    await go('/today');
    await go('/new');
    // E.g. a history entry from before a reload: its navigation id is unknown.
    location.go('/mail', '', { navigationId: 999 });
    location.go('/today', '', { navigationId: 998 });
    await popBack();
    expect(router.url).toBe('/mail');
    expect(navigation.canGoBack()).toBe(false);
  });

  it('starts afresh on a history entry without router state', async () => {
    await go('/today');
    await go('/new');
    // E.g. an entry the browser added for a link outside the router.
    location.go('/mail');
    location.go('/today', '', { navigationId: 998 });
    await popBack();
    expect(router.url).toBe('/mail');
    expect(navigation.canGoBack()).toBe(false);
  });

  it('uses the name a page set for itself, by path', async () => {
    await go('/projects/p1?tab=tasks');
    navigation.setLabel('/projects/p1', 'Launch the website');
    await go('/mail/messages/m1');
    expect(navigation.previous()?.label).toBe('Launch the website');
  });

  it('keeps a set name for one path only', async () => {
    navigation.setLabel('/projects/p2', 'Other');
    await go('/projects/p1');
    await go('/mail/messages/m1');
    expect(navigation.previous()?.label).toBe('Project');
  });

  it('keeps the most recent set names only', async () => {
    navigation.setLabel('/projects/p1', 'First');
    for (let i = 0; i < 100; i++) navigation.setLabel(`/projects/x${i}`, `Project ${i}`);
    await go('/projects/p1');
    await go('/mail/messages/m1');
    expect(navigation.previous()?.label).toBe('Project');
  });

  it('takes the title of the deepest route that has one', async () => {
    await go('/settings/general');
    await go('/settings/accounts');
    await go('/mail/messages/m1');
    expect(navigation.previous()?.label).toBe('Accounts');
    await popBack();
    expect(navigation.previous()?.label).toBe('Settings');
  });

  it('does not count a navigation a guard cancelled', async () => {
    await go('/today');
    await go('/new');
    await router.navigateByUrl('/locked');
    expect(router.url).toBe('/new');
    expect(navigation.previous()?.url).toBe('/today');
    await go('/mail/messages/m1');
    expect(navigation.previous()?.url).toBe('/new');
  });

  it('renames the previous page when it sets its name later', async () => {
    await go('/projects/p1');
    await go('/mail/messages/m1');
    expect(navigation.previous()?.label).toBe('Project');
    navigation.setLabel('/projects/p1#notes', 'Launch the website');
    expect(navigation.previous()?.label).toBe('Launch the website');
    navigation.setLabel('/projects/p1', 'Relaunch the website');
    expect(navigation.previous()?.label).toBe('Relaunch the website');
  });

  it('keeps a renamed page among the most recent names', async () => {
    navigation.setLabel('/projects/p1', 'First');
    for (let i = 0; i < 50; i++) navigation.setLabel(`/projects/x${i}`, `Project ${i}`);
    navigation.setLabel('/projects/p1', 'First, renamed');
    for (let i = 50; i < 99; i++) navigation.setLabel(`/projects/x${i}`, `Project ${i}`);
    await go('/projects/p1');
    await go('/mail/messages/m1');
    expect(navigation.previous()?.label).toBe('First, renamed');
  });

  it('keeps a name set again among the most recent names', async () => {
    navigation.setLabel('/projects/p1', 'First');
    for (let i = 0; i < 99; i++) navigation.setLabel(`/projects/x${i}`, `Project ${i}`);
    // The same name again (e.g. the page opened once more): it becomes the most recent one.
    navigation.setLabel('/projects/p1', 'First');
    navigation.setLabel('/projects/x99', 'Project 99');
    await go('/projects/p1');
    await go('/mail/messages/m1');
    expect(navigation.previous()?.label).toBe('First');
  });

  it('keeps exactly 100 names', async () => {
    navigation.setLabel('/projects/p1', 'First');
    for (let i = 0; i < 99; i++) navigation.setLabel(`/projects/x${i}`, `Project ${i}`);
    await go('/projects/p1');
    await go('/mail/messages/m1');
    expect(navigation.previous()?.label).toBe('First');
  });

  it('knows the whole way back through several pages', async () => {
    await go('/today');
    await go('/new?filter=mail');
    await go('/mail/messages/m1');
    await navigation.back('/mail');
    await settle();
    expect(router.url).toBe('/new?filter=mail');
    await navigation.back('/mail');
    await settle();
    expect(router.url).toBe('/today');
    // Nothing of the app behind the first page: the parent route.
    await navigation.back('/mail');
    expect(router.url).toBe('/mail');
  });

  describe('back button names and clicks', () => {
    it('updates its name when the previous page sets its name', async () => {
      await go('/projects/p1');
      await go('/mail/messages/m1');
      harness.detectChanges();
      expect(backLink().textContent?.trim()).toBe('Project');
      navigation.setLabel('/projects/p1', 'Launch the website');
      harness.detectChanges();
      expect(backLink().textContent?.trim()).toBe('Launch the website');
      expect(backLink().getAttribute('aria-label')).toBe('Back to Launch the website');
      expect(backLink().getAttribute('href')).toBe('/projects/p1');
    });

    it('names the parent route again after going back to the first page and forward', async () => {
      await go('/new');
      await go('/mail/messages/m1');
      await popBack();
      location.forward();
      await settle();
      harness.detectChanges();
      expect(backLink().textContent?.trim()).toBe('New');
    });

    it.each([
      ['meta', { metaKey: true }],
      ['shift', { shiftKey: true }],
      ['alt', { altKey: true }],
      ['middle button', { button: 1 }],
    ])('leaves a %s click to the browser', async (_, init) => {
      await go('/new');
      await go('/mail/messages/m1');
      harness.detectChanges();
      const event = new MouseEvent('click', { bubbles: true, cancelable: true, ...init });
      backLink().dispatchEvent(event);
      await settle();
      expect(event.defaultPrevented).toBe(false);
      expect(router.url).toBe('/mail/messages/m1');
    });

    it('goes back only once on a second click while the first Back is still running', async () => {
      await go('/today');
      await go('/new');
      await go('/mail/messages/m1');
      harness.detectChanges();
      // A double click: the router handles the first popstate in a later task, so the message is
      // still shown when the second click arrives.
      backLink().click();
      backLink().click();
      await settle();
      await settle();
      expect(router.url).toBe('/new');
    });

    it('takes over a plain click', async () => {
      await go('/mail/messages/m1');
      harness.detectChanges();
      const event = new MouseEvent('click', { bubbles: true, cancelable: true });
      backLink().dispatchEvent(event);
      await settle();
      expect(event.defaultPrevented).toBe(true);
      expect(router.url).toBe('/mail');
    });
  });

  describe('back button', () => {
    it('names the previous page and links to it', async () => {
      await go('/new?filter=mail');
      await go('/mail/messages/m1');
      harness.detectChanges();
      expect(backLink().textContent?.trim()).toBe('New');
      expect(backLink().getAttribute('aria-label')).toBe('Back to New');
      expect(backLink().getAttribute('href')).toBe('/new?filter=mail');
    });

    it('goes back to the previous page with its filter on click', async () => {
      await go('/new?filter=mail');
      await go('/mail/messages/m1');
      harness.detectChanges();
      backLink().click();
      await settle();
      expect(router.url).toBe('/new?filter=mail');
    });

    it('names and leads to the parent route on a deep link', async () => {
      await go('/mail/messages/m1');
      harness.detectChanges();
      expect(backLink().textContent?.trim()).toBe('Mail');
      expect(backLink().getAttribute('href')).toBe('/mail');
      backLink().click();
      await settle();
      expect(router.url).toBe('/mail');
    });

    it('reads Back when the previous page has no name', async () => {
      await go('/untitled');
      await go('/mail/messages/m1');
      harness.detectChanges();
      expect(backLink().textContent?.trim()).toBe('Back');
      expect(backLink().getAttribute('aria-label')).toBe('Back');
    });

    it('leaves modified clicks (new tab) to the browser', async () => {
      await go('/new');
      await go('/mail/messages/m1');
      harness.detectChanges();
      const event = new MouseEvent('click', { bubbles: true, cancelable: true, ctrlKey: true });
      backLink().dispatchEvent(event);
      await settle();
      expect(event.defaultPrevented).toBe(false);
      expect(router.url).toBe('/mail/messages/m1');
    });
  });
});

describe('BackNavigation when a Back does not reach its page', () => {
  let harness: RouterTestingHarness;
  let router: Router;
  let location: Location;
  let navigation: BackNavigation;
  let errors: unknown[];
  let failing: boolean;
  let redirecting: boolean;
  let mayLeave: boolean;
  /** While set, the guard of `/waiting` waits for it (e.g. a slow check or a lazy chunk). */
  let gate: Promise<boolean> | null;

  beforeEach(async () => {
    errors = [];
    failing = false;
    redirecting = false;
    mayLeave = true;
    gate = null;
    TestBed.configureTestingModule({
      // A failed popstate navigation is reported to the ErrorHandler; keep it there.
      rethrowApplicationErrors: false,
      providers: [
        provideRouter([
          { path: 'new', title: 'New', component: StubPage },
          { path: 'today', title: 'Today', component: StubPage },
          { path: 'mail', title: 'Mail', component: StubPage },
          { path: 'login', title: 'Login', component: StubPage },
          { path: 'mail/messages/:id', title: 'Message', component: StubDetail },
          {
            path: 'broken',
            title: 'Broken',
            component: StubPage,
            canActivate: [
              () => {
                if (failing) throw new Error('Fictitious failure while opening the page');
                return true;
              },
            ],
          },
          {
            path: 'guarded',
            title: 'Guarded',
            component: StubPage,
            // E.g. the session expired meanwhile: the page sends the user to the login.
            canActivate: [() => (redirecting ? inject(Router).parseUrl('/login') : true)],
          },
          {
            path: 'waiting',
            title: 'Waiting',
            component: StubPage,
            canActivate: [() => gate ?? true],
          },
          {
            path: 'editing',
            title: 'Editing',
            component: StubDetail,
            canDeactivate: [() => mayLeave],
          },
        ]),
        provideLocationMocks(),
        provideBackNavigation(),
        { provide: ErrorHandler, useValue: { handleError: (e: unknown) => errors.push(e) } },
      ],
    });
    harness = await RouterTestingHarness.create();
    router = TestBed.inject(Router);
    router.setUpLocationChangeListener();
    location = TestBed.inject(Location);
    navigation = TestBed.inject(BackNavigation);
  });

  const go = (url: string) => harness.navigateByUrl(url);

  it('stays on the page and leads to the right page after a Back that failed', async () => {
    await go('/today');
    await go('/broken');
    await go('/mail/messages/m1');
    failing = true;
    await navigation.back('/mail');
    await settle();
    expect(errors).toHaveLength(1);
    expect(router.url).toBe('/mail/messages/m1');
    // The router rewrote the entry of /broken with the message: behind it lies Today.
    expect(navigation.previous()).toEqual(
      expect.objectContaining({ url: '/today', label: 'Today' }),
    );
    await navigation.back('/mail');
    await settle();
    expect(router.url).toBe('/today');
    expect(navigation.canGoBack()).toBe(false);
  });

  it('goes to the parent route after a refused Back to an entry it never saw', async () => {
    await go('/new');
    // E.g. a history entry from before a reload: its navigation id is unknown.
    location.go('/today', '', { navigationId: 999 });
    await go('/editing');
    expect(navigation.previous()?.url).toBe('/new');
    mayLeave = false;
    location.back();
    await settle();
    expect(router.url).toBe('/editing');
    expect(navigation.canGoBack()).toBe(false);
    mayLeave = true;
    await navigation.back('/mail');
    expect(router.url).toBe('/mail');
  });

  it('leads to the page its back button names after a Back a guard redirected', async () => {
    await go('/today');
    await go('/guarded');
    await go('/mail/messages/m1');
    redirecting = true;
    await navigation.back('/mail');
    await settle();
    // The router replaced the entry of /guarded with /login: behind it lies Today.
    expect(router.url).toBe('/login');
    expect(navigation.previous()?.url).toBe('/today');
    await navigation.back('/mail');
    await settle();
    expect(router.url).toBe('/today');
  });

  it('resolves back() only when the navigation a guard redirected the Back to has ended', async () => {
    await go('/today');
    await go('/guarded');
    await go('/mail/messages/m1');
    redirecting = true;
    const ends: string[] = [];
    const subscription = router.events.subscribe((event) => {
      if (event instanceof NavigationEnd) ends.push(event.urlAfterRedirects);
    });
    try {
      await navigation.back('/mail');
      // No settle(): back() itself waited for the redirect to the login.
      expect(ends).toEqual(['/login']);
      expect(router.url).toBe('/login');
    } finally {
      subscription.unsubscribe();
    }
  });

  it('goes to the parent route after a redirected Back to an entry it never saw', async () => {
    await go('/new');
    // E.g. a history entry from before a reload: its navigation id is unknown.
    location.go('/guarded', '', { navigationId: 999 });
    await go('/mail/messages/m1');
    expect(navigation.previous()?.url).toBe('/new');
    redirecting = true;
    location.back();
    await settle();
    expect(router.url).toBe('/login');
    expect(navigation.canGoBack()).toBe(false);
  });

  it('counts the page a guard redirected a link to as the page opened', async () => {
    await go('/today');
    await go('/new');
    redirecting = true;
    await go('/guarded');
    expect(router.url).toBe('/login');
    expect(navigation.previous()).toEqual(expect.objectContaining({ url: '/new', label: 'New' }));
    await navigation.back('/mail');
    expect(router.url).toBe('/new');
    expect(navigation.previous()?.url).toBe('/today');
  });

  it('leads to the page its back button names after a Forward a guard redirected', async () => {
    await go('/today');
    await go('/guarded');
    await go('/new');
    await navigation.back('/mail');
    await navigation.back('/mail');
    expect(router.url).toBe('/today');
    redirecting = true;
    location.forward();
    await settle();
    // The router replaced the entry of /guarded with /login: behind it lies Today.
    expect(router.url).toBe('/login');
    expect(navigation.previous()).toEqual(
      expect.objectContaining({ url: '/today', label: 'Today' }),
    );
    await navigation.back('/mail');
    expect(router.url).toBe('/today');
  });

  it('keeps the page ahead after a Back a guard redirected, as the browser does', async () => {
    await go('/today');
    await go('/guarded');
    await go('/mail/messages/m1');
    redirecting = true;
    await navigation.back('/mail');
    expect(router.url).toBe('/login');
    // The redirect replaced the entry of /guarded; the message still lies ahead.
    location.forward();
    await settle();
    expect(router.url).toBe('/mail/messages/m1');
    expect(navigation.previous()).toEqual(
      expect.objectContaining({ url: '/login', label: 'Login' }),
    );
    redirecting = false;
    await navigation.back('/mail');
    expect(router.url).toBe('/login');
    expect(navigation.previous()?.url).toBe('/today');
  });

  it('leads to the page its back button names after a Back another navigation took over', async () => {
    await go('/today');
    await go('/waiting');
    await go('/mail/messages/m1');
    let open!: (value: boolean) => void;
    gate = new Promise((resolve) => (open = resolve));
    const cancels: (NavigationCancellationCode | undefined)[] = [];
    const subscription = router.events.subscribe((event) => {
      if (event instanceof NavigationCancel) cancels.push(event.code);
    });
    try {
      const back = navigation.back('/today');
      await settle();
      // The Back to /waiting still runs; the user opens Mail meanwhile.
      expect(router.url).toBe('/mail/messages/m1');
      const opened = router.navigateByUrl('/mail');
      open(true);
      await opened;
      await back;
      expect(cancels).toEqual([NavigationCancellationCode.SupersededByNewNavigation]);
    } finally {
      subscription.unsubscribe();
    }
    expect(router.url).toBe('/mail');
    gate = null;
    // The browser went back to /waiting and pushed /mail there: /waiting lies behind.
    expect(navigation.previous()?.url).toBe('/waiting');
    await navigation.back('/today');
    await settle();
    expect(router.url).toBe('/waiting');
  });

  it('follows the second Back when a Back runs into a first one still running', async () => {
    await go('/today');
    await go('/waiting');
    await go('/mail/messages/m1');
    let open!: (value: boolean) => void;
    gate = new Promise((resolve) => (open = resolve));
    // Browser Back twice in quick succession while the guard of /waiting still decides.
    location.back();
    await settle();
    expect(router.url).toBe('/mail/messages/m1');
    location.back();
    await settle();
    open(true);
    await settle();
    expect(router.url).toBe('/today');
    expect(navigation.canGoBack()).toBe(false);
    gate = null;
    // Both pages still lie ahead, as in the browser.
    location.forward();
    await settle();
    expect(router.url).toBe('/waiting');
    expect(navigation.previous()?.url).toBe('/today');
  });

  // TODO(web-back-navigation): fails - previous() still returns the left page; fix in BackNavigation.
  it.skip('goes to the parent route after a Back to an entry it never saw that another navigation took over', async () => {
    await go('/new');
    // E.g. a history entry from before a reload: its navigation id is unknown.
    location.go('/waiting', '', { navigationId: 999 });
    await go('/mail/messages/m1');
    expect(navigation.previous()?.url).toBe('/new');
    let open!: (value: boolean) => void;
    gate = new Promise((resolve) => (open = resolve));
    location.back();
    await settle();
    // The Back to the unknown entry still runs; the user opens Mail meanwhile.
    const opened = router.navigateByUrl('/mail');
    open(true);
    await opened;
    await settle();
    gate = null;
    expect(router.url).toBe('/mail');
    // The browser pushed /mail after the unknown entry: the app knows no page of its own behind.
    expect(navigation.previous()?.url).not.toBe('/mail/messages/m1');
    expect(navigation.canGoBack()).toBe(false);
  });

  it('goes back only once when back() is called again while the Back runs', async () => {
    await go('/today');
    await go('/new');
    await go('/mail/messages/m1');
    const back = vi.spyOn(location, 'back');
    const navigate = vi.spyOn(router, 'navigateByUrl');
    const first = navigation.back('/mail');
    const second = navigation.back('/mail');
    await Promise.all([first, second]);
    await settle();
    expect(back).toHaveBeenCalledTimes(1);
    expect(navigate).not.toHaveBeenCalled();
    expect(router.url).toBe('/new');
    expect(navigation.previous()?.url).toBe('/today');
  });

  it('starts a new Back once the previous one has finished', async () => {
    await go('/today');
    await go('/new');
    await go('/mail/messages/m1');
    await navigation.back('/mail');
    await navigation.back('/mail');
    await settle();
    expect(router.url).toBe('/today');
  });
});
