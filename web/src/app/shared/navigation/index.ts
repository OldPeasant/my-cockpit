import { EnvironmentProviders, inject, provideEnvironmentInitializer } from '@angular/core';
import { BackNavigation } from './back-navigation';

export { BACK_LABEL, BackButton } from './back-button';
export { BackNavigation } from './back-navigation';

/**
 * Starts {@link BackNavigation} with the app, so it sees every navigation from the first one on
 * (created lazily by the first detail page, it would miss the pages before it).
 */
export function provideBackNavigation(): EnvironmentProviders {
  return provideEnvironmentInitializer(() => void inject(BackNavigation));
}
