import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
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
