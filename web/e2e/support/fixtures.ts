import { test as base, Page } from '@playwright/test';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { Account, isSetupRequired, loginViaApi, resetStack, setupAccount } from './api';

interface WorkerFixtures {
  /** The owner account of the stack. The first worker that needs it runs the setup; the others reuse it. */
  account: Account;
  /** A logged-in session of this worker (Playwright storage state file), created once per worker. */
  sessionState: string;
}

interface TestFixtures {
  /** A page whose browser context is logged in as the owner (the worker's session). */
  loggedIn: Page;
}

/**
 * Test fixtures for the e2e suites. Import `test` and `expect` from here instead of `@playwright/test`:
 *
 * ```ts
 * test('shows the inbox', async ({ loggedIn: page }) => { await page.goto('/inbox'); ... });
 * ```
 */
export const test = base.extend<TestFixtures, WorkerFixtures>({
  account: [
    async ({ playwright }, use, workerInfo) => {
      const dir = join(workerInfo.project.outputDir, '.e2e-state');
      const request = await playwright.request.newContext({
        baseURL: workerInfo.project.use.baseURL,
      });
      try {
        await use(
          await withLock(join(dir, 'account.lock'), () =>
            sharedAccount(request, join(dir, 'account.json')),
          ),
        );
      } finally {
        await request.dispose();
      }
    },
    { scope: 'worker' },
  ],

  sessionState: [
    async ({ playwright, account }, use, workerInfo) => {
      const request = await playwright.request.newContext({
        baseURL: workerInfo.project.use.baseURL,
      });
      const file = join(
        workerInfo.project.outputDir,
        '.e2e-state',
        `session-${workerInfo.parallelIndex}.json`,
      );
      try {
        await loginViaApi(request, account);
        await request.storageState({ path: file });
      } finally {
        await request.dispose();
      }
      await use(file);
    },
    { scope: 'worker' },
  ],

  loggedIn: async ({ browser, sessionState }, use) => {
    const context = await browser.newContext({ storageState: sessionState });
    const page = await context.newPage();
    await use(page);
    await context.close();
  },
});

export { expect } from '@playwright/test';

/**
 * The account stored by an earlier worker of this run, or a new one. A stack that is not fresh but has no stored
 * account (e.g. a reused E2E_BASE_URL stack from an earlier run) is reset first, since its password is unknown.
 */
async function sharedAccount(
  request: Parameters<typeof setupAccount>[0],
  file: string,
): Promise<Account> {
  const stored = await readFile(file, 'utf8').then(
    (json) => JSON.parse(json) as Account,
    () => null,
  );
  if (stored && !(await isSetupRequired(request))) return stored;
  if (!(await isSetupRequired(request))) await resetStack(request);
  const account = await setupAccount(request);
  await writeFile(file, JSON.stringify(account));
  return account;
}

/** Runs [action] while holding a lock directory shared by all workers (mkdir is atomic). */
async function withLock<T>(lock: string, action: () => Promise<T>): Promise<T> {
  await mkdir(join(lock, '..'), { recursive: true });
  const deadline = Date.now() + 60_000;
  for (;;) {
    try {
      await mkdir(lock);
      break;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST' || Date.now() > deadline) throw error;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
  }
  try {
    return await action();
  } finally {
    await rm(lock, { recursive: true, force: true });
  }
}
