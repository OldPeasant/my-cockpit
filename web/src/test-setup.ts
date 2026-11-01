/**
 * Global setup of the unit tests (angular.json `test.options.setupFiles`), run before every spec file.
 *
 * Angular resets the TestBed in an `afterEach` hook. When a test fails in its own `afterEach` (for
 * example `HttpTestingController.verify()` after a timeout left a request open), Vitest skips the
 * remaining `afterEach` hooks, so that reset never happens and every following test of the file fails
 * with "Cannot configure the test module when the test module has already been instantiated". A test
 * that timed out also keeps running in the background and may leave fake timers switched on.
 *
 * So each test starts from a clean state here, whatever the previous one left behind: real timers and
 * a fresh TestBed (which drops its HttpTestingController with any pending expectations). The failure
 * itself is still reported once, by the test that caused it.
 */
import { getTestBed } from '@angular/core/testing';

beforeEach(() => {
  vi.useRealTimers();
  try {
    getTestBed().resetTestingModule();
  } catch (error) {
    // Destroying what the previous test left behind failed: that test already failed, this one may run.
    console.warn('Could not tear down the TestBed left by the previous test:', error);
  }
});
