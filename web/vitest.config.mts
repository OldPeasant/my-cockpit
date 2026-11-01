import { defineConfig } from 'vitest/config';

/**
 * Vitest settings merged into the Angular unit-test builder (angular.json `test.options.runnerConfig`).
 *
 * The suite runs on hosts shared with other builds (load averages far above the core count), where
 * a test that takes 0.5 s on an idle machine can take ten times as long. The defaults (5 s per test,
 * 10 s per hook) then fail healthy tests, so both get 20 s. A test that hangs still fails, just later.
 */
export default defineConfig({
  test: {
    testTimeout: 20_000,
    hookTimeout: 20_000,
  },
});
