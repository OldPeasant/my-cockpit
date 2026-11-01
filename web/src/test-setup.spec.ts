import { Component, InjectionToken, OnDestroy } from '@angular/core';
import { TestBed } from '@angular/core/testing';

/**
 * Checks the global `beforeEach` of `test-setup.ts`. The tests of this file run in order: each odd
 * test leaves behind what a test that timed out (or failed in its own `afterEach`) can leave behind,
 * from an `onTestFinished` callback, which Vitest runs after every `afterEach` hook, so Angular's own
 * TestBed reset has already happened. The next test checks that it still starts from a clean state.
 */
const GREETING = new InjectionToken<string>('GREETING');

@Component({ selector: 'app-broken-teardown', template: '' })
class BrokenTeardown implements OnDestroy {
  ngOnDestroy(): void {
    throw new Error('Fictitious teardown failure');
  }
}

describe('test setup (runs in order)', () => {
  let warn: ReturnType<typeof vi.spyOn> | undefined;

  afterAll(() => warn?.mockRestore());

  it('leaves an instantiated TestBed and fake timers behind', () => {
    TestBed.configureTestingModule({ providers: [{ provide: GREETING, useValue: 'first' }] });
    expect(TestBed.inject(GREETING)).toBe('first');
    onTestFinished(() => {
      TestBed.configureTestingModule({ providers: [{ provide: GREETING, useValue: 'leftover' }] });
      TestBed.inject(GREETING);
      vi.useFakeTimers();
    });
  });

  it('starts with real timers and a TestBed that can be configured again', () => {
    expect(vi.isFakeTimers()).toBe(false);
    TestBed.configureTestingModule({ providers: [{ provide: GREETING, useValue: 'second' }] });
    expect(TestBed.inject(GREETING)).toBe('second');
  });

  it('leaves a fixture behind whose teardown throws', () => {
    onTestFinished(() => {
      TestBed.createComponent(BrokenTeardown);
      warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    });
  });

  it('reports the failed teardown as a warning and still runs with a fresh TestBed', () => {
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn?.mock.calls[0][0]).toBe(
      'Could not tear down the TestBed left by the previous test:',
    );
    expect(String(warn?.mock.calls[0][1])).toContain('1 fixture threw errors during cleanup');
    warn?.mockRestore();
    TestBed.configureTestingModule({ providers: [{ provide: GREETING, useValue: 'third' }] });
    expect(TestBed.inject(GREETING)).toBe('third');
  });
});
