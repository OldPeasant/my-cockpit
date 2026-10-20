import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests against the real stack (web/e2e/README.md). Run them with `npm run e2e`: it starts
 * deploy/docker-compose.e2e.yml and passes its address in E2E_BASE_URL.
 */
const baseURL = process.env['E2E_BASE_URL'];
if (!baseURL) {
  throw new Error('E2E_BASE_URL is not set: run the e2e tests with `npm run e2e`');
}

export default defineConfig({
  testDir: './e2e',
  outputDir: './test-results',
  forbidOnly: !!process.env['CI'],
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL,
    locale: 'en-US',
    timezoneId: 'Europe/Zurich',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    // The phase demo scripts (SPEC.md §13.2) reset the stack, trigger the global login backoff and move the server
    // clock, so they run on their own, before every other spec (`--no-deps` skips them).
    {
      name: 'phase-demos',
      testMatch: /phase\d+\/.*\.spec\.ts$/,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'chromium',
      testIgnore: /phase\d+\//,
      dependencies: ['phase-demos'],
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
