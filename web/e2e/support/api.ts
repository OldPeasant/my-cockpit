import { APIRequestContext, expect } from '@playwright/test';
import { generateSync } from 'otplib';

/** The owner account of the e2e stack (fictitious). */
export interface Account {
  username: string;
  password: string;
  /** Base32 TOTP secret, as shown during setup. */
  totpSecret: string;
  recoveryCodes: string[];
}

export const OWNER = { username: 'jane.doe', password: 'correct horse battery staple' } as const;

/** The current TOTP code of [secret] (HMAC-SHA1, 6 digits, 30 s), at [at] (default now) like an authenticator app. */
export function totp(secret: string, at: Date = new Date()): string {
  return generateSync({ secret, epoch: Math.floor(at.getTime() / 1000) });
}

/**
 * Runs the first-run setup through the API (SPEC.md §4.1): creates the owner and confirms the TOTP enrollment.
 * The stack must be fresh (setup required); use [resetStack] first otherwise.
 */
export async function setupAccount(
  request: APIRequestContext,
  credentials: { username: string; password: string } = OWNER,
): Promise<Account> {
  const start = await request.post('/api/v1/setup', { data: credentials });
  expect(start.status(), 'setup must start on a fresh stack').toBe(201);
  const enrollment = (await start.json()) as { totpSecret: string; recoveryCodes: string[] };
  const confirm = await request.post('/api/v1/setup/confirm-totp', {
    data: { code: totp(enrollment.totpSecret) },
  });
  expect(confirm.status(), 'TOTP confirmation').toBe(204);
  return {
    ...credentials,
    totpSecret: enrollment.totpSecret,
    recoveryCodes: enrollment.recoveryCodes,
  };
}

export async function isSetupRequired(request: APIRequestContext): Promise<boolean> {
  const response = await request.get('/api/v1/setup/status');
  expect(response.ok()).toBe(true);
  return ((await response.json()) as { setupRequired: boolean }).setupRequired;
}

/**
 * Logs [request] in through the API with password + TOTP; its cookies then hold the session (persistent, remember
 * device), e.g. for `request.storageState()`. Login needs the CSRF cookie of an earlier response.
 */
export async function loginViaApi(request: APIRequestContext, account: Account): Promise<void> {
  await request.get('/api/v1/setup/status');
  const xsrf = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
  expect(xsrf, 'XSRF-TOKEN cookie').toBeDefined();
  const response = await request.post('/api/v1/auth/login', {
    headers: { 'X-XSRF-TOKEN': xsrf!.value },
    data: {
      username: account.username,
      password: account.password,
      totp: totp(account.totpSecret),
      rememberDevice: true,
    },
  });
  expect(response.status(), 'login').toBe(200);
}

/**
 * Empties the database, resets the server clock and the login lockout (profile e2e only). Every account and
 * session is gone afterwards, including the shared one of the `account`/`loggedIn` fixtures: a spec that resets
 * must run serially and set up its own account.
 */
export async function resetStack(request: APIRequestContext): Promise<void> {
  const response = await request.post('/api/test/reset');
  expect(response.status(), 'test reset').toBe(204);
}

/**
 * Sets the server clock to [now]; it runs on from there (profile e2e only). TOTP codes must then be computed for the
 * server's time: `totp(secret, serverNow)`.
 */
export async function setClock(request: APIRequestContext, now: Date): Promise<void> {
  const response = await request.post('/api/test/clock', { data: { now: now.toISOString() } });
  expect(response.status(), 'test clock').toBe(200);
}
