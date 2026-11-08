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
