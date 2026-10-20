import { Page } from '@playwright/test';
import { resetStack, setClock, totp } from '../support/api';
import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §13.2, Phase 0 demo script, steps 1–5 (step 6 is the owner's deployment). Runs as its own project
 * (playwright.config.ts) before every other spec: it resets the stack, drives the global login backoff and moves the
 * server clock, which would break specs running in parallel. It leaves an empty stack behind.
 */
test.describe.configure({ mode: 'serial' });

const OWNER = { username: 'alex.example', password: 'phase0 demo passphrase' } as const;

/** What the setup screen showed; filled in step 2 and used by the later steps. */
const enrolled = { totpSecret: '', recoveryCodes: [] as string[] };

/** The server clock, moved forward in step 3 to skip the backoff wait; TOTP codes are computed for it. */
let clockOffsetMs = 0;
const serverNow = () => new Date(Date.now() + clockOffsetMs);

test.describe('Phase 0 demo', () => {
  let page: Page;

  test.beforeAll(async ({ browser, request }) => {
    await resetStack(request);
    page = await browser.newPage();
  });

  test.afterAll(async ({ request }) => {
    await page?.close();
    // The next specs set up their own account on an empty stack with the real clock and no lockout.
    await resetStack(request);
  });

  test('1. the stack is healthy', async ({ request }) => {
    const health = await request.get('/actuator/health');
    expect(health.ok()).toBe(true);
    expect(((await health.json()) as { status: string }).status).toBe('UP');
  });

  test('2. the setup flow creates the account and enrolls TOTP', async () => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/setup(\?|$)/);

    await page.getByLabel('Username').fill(OWNER.username);
    await page.getByLabel('Password', { exact: true }).fill(OWNER.password);
    await page.getByLabel('Repeat password').fill(OWNER.password);
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(
      page.getByRole('heading', { name: 'Connect your authenticator app' }),
    ).toBeVisible();
    await expect(
      page.getByRole('img', { name: 'QR code for your authenticator app' }),
    ).toBeVisible();
    // "Scanning" the QR code: the key shown below it is the same secret.
    enrolled.totpSecret = ((await page.locator('code.secret').textContent()) ?? '').trim();
    expect(enrolled.totpSecret).toMatch(/^[A-Z2-7]{16,}$/);
    enrolled.recoveryCodes = (await page.locator('ol.codes code').allTextContents()).map((c) =>
      c.trim(),
    );
    expect(enrolled.recoveryCodes.length).toBeGreaterThanOrEqual(8);
    expect(new Set(enrolled.recoveryCodes).size).toBe(enrolled.recoveryCodes.length);

    await page.getByRole('checkbox', { name: 'I saved my recovery codes' }).check();
    await page.getByLabel('Code from your authenticator app').fill(totp(enrolled.totpSecret));
    await page.getByRole('button', { name: 'Finish setup' }).click();

    await expect(page).toHaveURL(/\/login(\?|$)/);
  });

  test('3. login with password + TOTP shows the shell; a wrong code is rejected, 5 failures trigger the backoff', async ({
    request,
  }) => {
    await signIn(page, totp(enrolled.totpSecret));
    await expectShell(page);
    await signOut(page);

    await signIn(page, wrongCode());
    await expect(page.getByRole('alert')).toHaveText(/Wrong username, password or code/);
    await expect(page).toHaveURL(/\/login/);
    await signIn(page, totp(enrolled.totpSecret));
    await expectShell(page);
    await signOut(page);

    for (let attempt = 1; attempt <= 5; attempt++) {
      await signIn(page, wrongCode());
      await expect(page.getByRole('alert')).toHaveText(/Wrong username, password or code/);
    }
    // The 5th failure started the backoff: the next attempt is refused without checking the (correct) code.
    await signIn(page, totp(enrolled.totpSecret));
    await expect(page.getByRole('alert')).toHaveText('Too many failed attempts.');
    await expect(page.locator('.backoff .wait')).toHaveText(/Try again in \d+ s\./);
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeDisabled();
    await expect(page).toHaveURL(/\/login/);

    // Skip the 30 s wait: move the server clock past it, then reload to clear the form's countdown.
    clockOffsetMs = 2 * 60_000;
    await setClock(request, serverNow());
    await page.reload();
    await signIn(page, totp(enrolled.totpSecret, serverNow()));
    await expectShell(page);
  });

  test('4. a recovery code signs in once, then it is rejected', async () => {
    const code = enrolled.recoveryCodes[0];
    await signOut(page);

    await signInWithRecoveryCode(page, code);
    await expectShell(page);
    await signOut(page);

    await signInWithRecoveryCode(page, code);
    await expect(page.getByRole('alert')).toHaveText(/Wrong username, password or code/);
    await expect(page).toHaveURL(/\/login/);

    // Another unused code still works.
    await page.reload();
    await signInWithRecoveryCode(page, enrolled.recoveryCodes[1]);
    await expectShell(page);
  });

  test('5. Ctrl+K opens the command palette; the dark theme persists across a reload', async () => {
    await page.goto('/');
    await expectShell(page);

    await page.keyboard.press('Control+K');
    const palette = page.getByRole('combobox', { name: 'Search or type a command' });
    await expect(palette).toBeVisible();
    await expect(palette).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(palette).toBeHidden();

    for (const mode of ['Dark', 'Light'] as const) {
      await page.getByRole('button', { name: 'Theme' }).click();
      await page.getByRole('menuitemradio', { name: mode }).click();
      await expect(page.locator('html')).toHaveAttribute('data-theme', mode.toLowerCase());

      await page.reload();
      await expectShell(page);
      await expect(page.locator('html')).toHaveAttribute('data-theme', mode.toLowerCase());
      await page.getByRole('button', { name: 'Theme' }).click();
      await expect(page.getByRole('menuitemradio', { name: mode })).toHaveAttribute(
        'aria-checked',
        'true',
      );
      await page.keyboard.press('Escape');
    }
  });
});

/** A well-formed code that is not valid now: the code of an hour later lies outside any accepted window. */
function wrongCode(): string {
  return totp(enrolled.totpSecret, new Date(serverNow().getTime() + 3_600_000));
}

async function fillPassword(page: Page): Promise<void> {
  await expect(page).toHaveURL(/\/login/);
  await page.getByLabel('Username').fill(OWNER.username);
  await page.getByLabel('Password', { exact: true }).fill(OWNER.password);
}

async function signIn(page: Page, code: string): Promise<void> {
  await fillPassword(page);
  await page.getByLabel('Code from your authenticator app').fill(code);
  await submitLogin(page);
}

async function signInWithRecoveryCode(page: Page, code: string): Promise<void> {
  await fillPassword(page);
  const field = page.getByLabel('Recovery code');
  if (!(await field.isVisible()))
    await page.getByRole('button', { name: 'Use a recovery code' }).click();
  await field.fill(code);
  await submitLogin(page);
}

/** Submits the login form and waits for the server's answer, so a stale message of the last attempt never counts. */
async function submitLogin(page: Page): Promise<void> {
  const answered = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/auth/login') && r.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Sign in' }).click();
  await answered;
}

async function expectShell(page: Page): Promise<void> {
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
  await expect(page).not.toHaveURL(/\/login|\/setup/);
}

async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Account' }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login/);
}
