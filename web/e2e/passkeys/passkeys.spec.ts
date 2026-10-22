import { BrowserContext, CDPSession, Page } from '@playwright/test';
import { loginViaApi, totp } from '../support/api';
import { expect, test } from '../support/fixtures';

/**
 * Passkeys in the web app (SPEC.md §4.1): added in Settings → Security with a current TOTP code, used to sign in on the
 * login page, deleted again. Chromium's virtual authenticator (CDP `WebAuthn` domain) plays the platform authenticator
 * and answers every prompt with user verification.
 *
 * Browsers reject an IP address as relying party ID, so this spec runs on `localhost` instead of the `127.0.0.1` of
 * E2E_BASE_URL (the server derives RP ID and origin from `COCKPIT_DOMAIN=localhost` and the request's port). Cookies are
 * per host, so it logs in there itself instead of using the `loggedIn` fixture.
 */
test.describe.configure({ mode: 'serial' });

const PASSKEY = 'Work laptop';

test.describe('passkeys', () => {
  let context: BrowserContext;
  let page: Page;
  let cdp: CDPSession;
  let authenticatorId: string;

  test.beforeAll(async ({ browser, account }, testInfo) => {
    const baseURL = new URL(testInfo.project.use.baseURL!);
    baseURL.hostname = 'localhost';
    context = await browser.newContext({ baseURL: baseURL.origin });
    page = await context.newPage();
    cdp = await context.newCDPSession(page);
    await cdp.send('WebAuthn.enable');
    ({ authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
      options: {
        protocol: 'ctap2',
        transport: 'internal',
        hasResidentKey: true,
        hasUserVerification: true,
        isUserVerified: true,
        automaticPresenceSimulation: true,
      },
    }));
    await loginViaApi(context.request, account);
  });

  test.afterAll(async () => {
    await context?.close();
  });

  test('a passkey is added in the security settings', async ({ account }) => {
    await page.goto('/settings/security');
    await expect(page.getByText('No passkeys yet.')).toBeVisible();

    await page.getByRole('textbox', { name: 'Name' }).fill(PASSKEY);
    const form = page.locator('form.add-passkey');
    await form
      .getByRole('textbox', { name: 'Code from authenticator app' })
      .fill(totp(account.totpSecret));
    await page.getByRole('button', { name: 'Add passkey' }).click();

    await expect(page.getByText(`Passkey ${PASSKEY} added.`)).toBeVisible();
    const list = page.getByRole('list', { name: 'Passkeys' });
    await expect(list.getByRole('listitem')).toHaveCount(1);
    await expect(list).toContainText(PASSKEY);
    await expect(list).toContainText('not used yet');

    const { credentials } = await cdp.send('WebAuthn.getCredentials', { authenticatorId });
    expect(credentials).toHaveLength(1);
    expect(credentials[0]!.isResidentCredential).toBe(true);
  });

  test('the passkey signs in on the login page, without password and code', async () => {
    await context.clearCookies();
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();

    await page.getByRole('button', { name: 'Sign in with a passkey' }).click();

    await expect(page).not.toHaveURL(/\/login/);
    await page.goto('/settings/security');
    const list = page.getByRole('list', { name: 'Passkeys' });
    await expect(list).toContainText('Last used');
  });

  test('a deleted passkey is gone and no longer signs in', async () => {
    await page.goto('/settings/security');
    await page.getByRole('button', { name: `Delete ${PASSKEY}` }).click();
    const dialog = page.getByRole('dialog', { name: `Delete ${PASSKEY}?` });
    await dialog.getByRole('button', { name: 'Delete passkey' }).click();
    await expect(page.getByText('No passkeys yet.')).toBeVisible();

    // The authenticator still holds the credential; the server no longer knows it.
    await context.clearCookies();
    await page.goto('/login');
    await page.getByRole('button', { name: 'Sign in with a passkey' }).click();
    await expect(page.getByRole('alert')).toContainText('This passkey was not accepted');
    await expect(page).toHaveURL(/\/login/);
  });
});
