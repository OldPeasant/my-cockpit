import { totp } from './api';
import { expect, test } from './fixtures';

// Smoke test of the harness itself: the stack is up, the account fixture ran the setup, and the SPA works.
test.describe('smoke', () => {
  test('signing in with password and TOTP shows the app shell', async ({ page, account }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);

    await page.getByLabel('Username').fill(account.username);
    await page.getByLabel('Password', { exact: true }).fill(account.password);
    await page.getByLabel('Code from your authenticator app').fill(totp(account.totpSecret));
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
    await expect(page).not.toHaveURL(/\/login/);
  });

  test('the loggedIn fixture opens the shell without signing in', async ({ loggedIn: page }) => {
    await page.goto('/inbox');

    await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
    await expect(page).toHaveURL(/\/inbox$/);
  });
});
