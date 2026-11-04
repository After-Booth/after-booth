import { chromium, expect, test, type Browser, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { AUTH_ORIGIN, startAuthStack, uniqueEmail, type AuthStack } from './authStack';

const PHOTO_BINS = fileURLToPath(
  new URL('../../spike/samples/bins-orange-stack.jpg', import.meta.url),
);
// Synthetic, test-only values; the accounts live in the disposable local database.
const PASSWORD = 'synthetic-pass-1234';
const AI_TIMEOUT_MS = 90_000;

let stack: AuthStack;
let browser: Browser;

test.beforeAll(async () => {
  stack = await startAuthStack();
  browser = await chromium.launch({
    args: [`--ignore-certificate-errors-spki-list=${stack.spki}`],
  });
});

test.afterAll(async () => {
  await browser?.close();
  await stack?.stop();
});

// Runs inside the page so the request uses the browser's pinned certificate.
async function apiGet<T = unknown>(page: Page, url: string, authorization?: string) {
  return page.evaluate(
    async ([target, header]) => {
      const response = await fetch(target, { headers: header ? { authorization: header } : {} });
      return { status: response.status, body: (await response.json().catch(() => undefined)) as T };
    },
    [url, authorization] as const,
  );
}

// Mutating call inside the page; only the status and parsed body leave the browser.
async function call<T = any>(page: Page, method: string, url: string, body?: unknown) {
  const authorization = tokens.get(page);
  return page.evaluate(
    async ([verb, target, header, payload, key]) => {
      const response = await fetch(target, {
        method: verb,
        headers: {
          authorization: header,
          'Idempotency-Key': key,
          ...(payload ? { 'Content-Type': 'application/json' } : {}),
        },
        body: payload || undefined,
      });
      return { status: response.status, body: (await response.json().catch(() => undefined)) as T };
    },
    [method, url, authorization ?? '', body ? JSON.stringify(body) : '', crypto.randomUUID()] as const,
  );
}

async function signUp(page: Page, email: string) {
  await page.goto('/');
  await page.getByRole('button', { name: 'Create account' }).click();
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  const code = stack.nextCode('SIGNUP_VERIFY');
  await page.getByRole('button', { name: 'Request verification email' }).click();
  await page.getByLabel('Email verification code').fill(await code);
  await page.getByRole('button', { name: 'Complete signup' }).click();
  await expect(page.getByText('Email verified. Please sign in.')).toBeVisible();
}

const tokens = new WeakMap<Page, string>();

async function logIn(page: Page, email: string) {
  page.on('request', (request) => {
    const header = request.headers()['authorization'];
    if (header && request.url().includes('/api/')) tokens.set(page, header);
  });
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible();
}

async function createEvent(page: Page, name: string) {
  await page.getByText('Create a new event').click();
  await page.getByLabel('Event name').fill(name);
  await page.getByLabel('Venue').fill('Synthetic Hall');
  await page.getByLabel('Teardown deadline (Asia/Seoul, KST)').fill('2030-01-01T18:00');
  await page.getByRole('button', { name: 'Create event' }).click();
  await expect(page.getByText('Event created. Add your photo now.')).toBeVisible();
}

test('signup, verification, login, account lookup, own event and photo intake on the real server', async () => {
  const context = await browser.newContext({ baseURL: AUTH_ORIGIN });
  const page = await context.newPage();
  const email = uniqueEmail();

  await signUp(page, email);
  // A fresh login is required after verification: no automatic login.
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toHaveCount(0);
  await logIn(page, email);

  await page.goto('/#/account');
  await expect(page.getByText(`Email: ${email}`)).toBeVisible();
  await expect(page.getByText('Status: ACTIVE')).toBeVisible();
  await expect(page.getByText(/^Organization ID: \d+$/)).toBeVisible();

  // Tokens stay out of browser storage; the refresh token is an HttpOnly, Secure cookie.
  const stored = await page.evaluate(() => [
    Object.keys(localStorage).length,
    Object.keys(sessionStorage).length,
    document.cookie,
  ]);
  expect(stored).toEqual([0, 0, '']);
  const cookies = await context.cookies();
  expect(cookies.some((cookie) => cookie.httpOnly && cookie.secure)).toBe(true);

  await page.goto('/#/');
  await createEvent(page, 'Synthetic Expo');
  await page.locator('input[type="file"][accept="image/jpeg,image/png"]').setInputFiles(PHOTO_BINS);
  await expect(page.getByLabel('Quantity by category').getByText('Storage bin 4 items')).toBeVisible({
    timeout: AI_TIMEOUT_MS,
  });
  await page.getByRole('button', { name: 'Register inventory' }).click();
  await expect(page.getByText('Inventory registered.')).toBeVisible();

  // A reload restores the session through the refresh cookie without a new login.
  await page.reload();
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible();

  await page.goto('/#/account');
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  await context.close();
});

test('a second member cannot see or read the first member’s event', async () => {
  const first = await browser.newContext({ baseURL: AUTH_ORIGIN });
  const second = await browser.newContext({ baseURL: AUTH_ORIGIN });
  const firstPage = await first.newPage();
  const secondPage = await second.newPage();
  const firstEmail = uniqueEmail();
  const secondEmail = uniqueEmail();

  let firstToken = '';
  firstPage.on('request', (request) => {
    const header = request.headers()['authorization'];
    if (header && request.url().includes('/api/events')) firstToken = header;
  });
  await signUp(firstPage, firstEmail);
  await logIn(firstPage, firstEmail);
  await createEvent(firstPage, 'First Member Event');
  const own = (await apiGet<Array<{ id: number }>>(firstPage, '/api/events', firstToken)).body[0];
  expect(own.id).toBeGreaterThan(0);

  await signUp(secondPage, secondEmail);
  await logIn(secondPage, secondEmail);
  await expect(
    secondPage.getByRole('option', { name: 'First Member Event' }),
  ).toHaveCount(0);
  await expect(secondPage.getByRole('option')).toHaveCount(1);

  let secondToken = '';
  secondPage.on('request', (request) => {
    const header = request.headers()['authorization'];
    if (header && request.url().includes('/api/')) secondToken = header;
  });
  await secondPage.reload();
  await expect(secondPage.getByRole('navigation', { name: 'Main navigation' })).toBeVisible();
  await expect.poll(() => secondToken).not.toBe('');
  const denied = await apiGet(secondPage, `/api/events/${own.id}`, secondToken);
  expect([403, 404]).toContain(denied.status);
  const unauthenticated = await apiGet(secondPage, `/api/events/${own.id}`);
  expect(unauthenticated.status).toBe(401);

  await first.close();
  await second.close();
});

test('giver inventory, receiver request, hold, confirmation and withdrawal eligibility', async () => {
  const giverContext = await browser.newContext({ baseURL: AUTH_ORIGIN });
  const receiverContext = await browser.newContext({ baseURL: AUTH_ORIGIN });
  const giver = await giverContext.newPage();
  const receiver = await receiverContext.newPage();
  const giverEmail = uniqueEmail();
  const receiverEmail = uniqueEmail();

  await signUp(giver, giverEmail);
  await logIn(giver, giverEmail);
  await createEvent(giver, 'Giver Expo');
  await giver.locator('input[type="file"][accept="image/jpeg,image/png"]').setInputFiles(PHOTO_BINS);
  await expect(giver.getByLabel('Quantity by category').getByText('Storage bin 4 items')).toBeVisible({
    timeout: AI_TIMEOUT_MS,
  });
  await giver.getByRole('button', { name: 'Register inventory' }).click();
  await expect(giver.getByText('Inventory registered.')).toBeVisible();
  const giverOrg = (await call(giver, 'GET', '/api/members/me')).body.organizationId as number;

  await signUp(receiver, receiverEmail);
  await logIn(receiver, receiverEmail);
  const receiverOrg = (await call(receiver, 'GET', '/api/members/me')).body.organizationId as number;
  expect(receiverOrg).not.toBe(giverOrg);

  const saved = await call(receiver, 'POST', '/api/pickup-requests', {
    organizationId: receiverOrg,
    category: 'STORAGE_BIN',
    quantity: 2,
    vehicleType: 'VAN',
    transcript: 'synthetic request',
  });
  expect(saved.status).toBe(201);
  const requestId = saved.body.id as number;
  // A member cannot file a request for another member's organization.
  const foreign = await call(receiver, 'POST', '/api/pickup-requests', {
    organizationId: giverOrg,
    category: 'STORAGE_BIN',
    quantity: 1,
    vehicleType: 'VAN',
  });
  expect([403, 404]).toContain(foreign.status);

  const items = (await call(receiver, 'GET', '/api/items?availableOnly=true')).body as Array<{
    id: number;
    organizationId: number;
    category: string;
  }>;
  const bins = items.find((item) => item.organizationId === giverOrg && item.category === 'STORAGE_BIN');
  expect(bins, 'the giver bins are available to the receiver').toBeDefined();
  const hold = await call(receiver, 'POST', '/api/reservations', {
    requestId,
    itemId: bins!.id,
    quantity: 2,
  });
  expect(hold.status).toBe(201);
  expect(hold.body.status).toBe('HELD');
  const reservationId = hold.body.id as number;

  // The giver is not the receiver organization's owner and cannot confirm.
  const foreignConfirm = await call(giver, 'POST', `/api/reservations/${reservationId}/confirm`);
  expect([403, 404]).toContain(foreignConfirm.status);

  await receiver.goto(`/#/allocation?requestId=${requestId}`);
  await receiver.getByRole('button', { name: /Confirm reservation$/ }).click();
  await expect
    .poll(async () => (await call(receiver, 'GET', `/api/reservations/${reservationId}`)).body.status)
    .toBe('CONFIRMED');

  await giver.goto('/#/handover');
  await expect(
    giver.getByRole('option', {
      name: new RegExp(`Reservation #${reservationId} `),
    }),
  ).toHaveCount(1);

  // Withdrawal is rejected while a CONFIRMED reservation exists, for either side.
  for (const [page, email] of [
    [receiver, receiverEmail],
    [giver, giverEmail],
  ] as const) {
    await page.goto('/#/account');
    page.once('dialog', (dialog) => void dialog.accept());
    await page.getByLabel('Current password').fill(PASSWORD);
    const answer = page.waitForResponse(
      (r) => r.url().endsWith('/api/members/me') && r.request().method() === 'DELETE',
    );
    await page.getByRole('button', { name: 'Withdraw account' }).click();
    const response = await answer;
    expect(response.status()).toBe(409);
    expect((await response.json()).code).toBe('WITHDRAWAL_BLOCKED');
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByText(`Email: ${email}`)).toBeVisible();
  }

  // After the receiver cancels, both members can withdraw and cannot log in again.
  expect((await call(receiver, 'POST', `/api/reservations/${reservationId}/cancel`)).status).toBe(
    200,
  );
  for (const [page, email] of [
    [receiver, receiverEmail],
    [giver, giverEmail],
  ] as const) {
    await page.goto('/#/account');
    page.once('dialog', (dialog) => void dialog.accept());
    await page.getByLabel('Current password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Withdraw account' }).click();
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    await page.getByLabel('Email', { exact: true }).fill(email);
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Main navigation' })).toHaveCount(0);
  }

  await giverContext.close();
  await receiverContext.close();
});

// Real HTTPS server; Fake delivery only. No credential/code is used in a test title or output.
test('password change and email reset revoke sessions and require fresh login', async () => {
  const context = await browser.newContext({ baseURL: AUTH_ORIGIN });
  const page = await context.newPage();
  const email = uniqueEmail();
  const changed = 'synthetic-changed-pass-1234';
  const reset = 'synthetic-reset-pass-1234';
  await signUp(page, email);
  await logIn(page, email);
  await page.goto('/#/account');
  await page.getByLabel('Current password', { exact: true }).fill('synthetic-incorrect-pass');
  await page.getByLabel('New password', { exact: true }).fill(changed);
  const rejected = page.waitForResponse((response) =>
    response.url().endsWith('/api/members/me/password'),
  );
  await page.getByRole('button', { name: 'Change password', exact: true }).click();
  expect((await rejected).status()).toBe(403);
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByLabel('Current password', { exact: true }).fill(PASSWORD);
  const changedResponse = page.waitForResponse((response) =>
    response.url().endsWith('/api/members/me/password'),
  );
  await page.getByRole('button', { name: 'Change password', exact: true }).click();
  expect((await changedResponse).status()).toBe(204);
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toHaveCount(0);
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(changed);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible();
  await page.goto('/#/account');
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await page.getByRole('button', { name: 'Forgot password?' }).click();
  await page.getByLabel('Email', { exact: true }).fill(email);
  const nextCode = stack.nextCode('PASSWORD_RESET');
  await page.getByRole('button', { name: 'Request reset email', exact: true }).click();
  await page.getByLabel('Email verification code', { exact: true }).fill(await nextCode);
  await page.getByLabel('New password', { exact: true }).fill(reset);
  const resetResponse = page.waitForResponse((response) =>
    response.url().endsWith('/api/auth/password-resets/confirm'),
  );
  await page.getByRole('button', { name: 'Change password', exact: true }).click();
  expect((await resetResponse).status()).toBe(204);
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toHaveCount(0);
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(reset);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible();
  await context.close();
});

test('failed sign out retains the session until retry revokes the real server session', async () => {
  const context = await browser.newContext({ baseURL: AUTH_ORIGIN });
  const page = await context.newPage();
  await signUp(page, uniqueEmail());
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible();
  // Synthetic refresh outage; a later logout retry reaches the real HTTPS server.
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'AUTH_UNAVAILABLE',
        message: 'Authentication is temporarily unavailable.',
      }),
    }),
  );
  await page.route('**/api/events', (route) =>
    route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'UNAUTHENTICATED' }),
    }),
  );
  await page.goto('/#/account');
  await page.goto('/#/');
  await expect(
    page.getByRole('alert')
      .filter({ hasText: 'Unable to refresh your session' }),
  ).toBeVisible();
  for (const width of [360, 390, 1280]) {
    await page.setViewportSize({ width, height: 900 });
    const navigationFits = await page.getByRole('navigation', { name: 'Main navigation' })
      .evaluateAll((nodes) => {
        if (!nodes.length) return true;
        const links = [...nodes[0].querySelectorAll('a')].map((link) =>
          link.getBoundingClientRect(),
        );
        return links.every(
          (rect, index) =>
            index === 0 || rect.left >= links[index - 1].right - 1,
        );
      });
    expect(navigationFits).toBe(true);
    if (process.env.E2E_VISUAL_DIR) {
      fs.mkdirSync(process.env.E2E_VISUAL_DIR, { recursive: true });
      await page.screenshot({
        path: path.join(process.env.E2E_VISUAL_DIR, `refresh-${width}.png`),
      });
    }
  }
  // A transport failure must not claim logout while the HttpOnly session cookie survives.
  await page.route('**/api/auth/logout', (route) => route.abort('connectionfailed'));
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page.getByRole('alert').filter({
    hasText: 'Sign out failed. Your session may still be active. Try signing out again.',
  })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeEnabled();
  expect(
    (await context.cookies()).some((cookie) => cookie.name.includes('refresh')),
  ).toBe(true);

  await page.unroute('**/api/auth/logout');
  await page.unroute('**/api/events');
  const logout = page.waitForResponse((response) =>
    response.url().endsWith('/api/auth/logout'),
  );
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  expect((await logout).status()).toBe(204);
  await page.unroute('**/api/auth/refresh');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toHaveCount(0);
  expect(
    (await context.cookies()).some((cookie) => cookie.name.includes('refresh')),
  ).toBe(false);
  await context.close();
});

test('unverified login can request a verification code and complete signup', async () => {
  const context = await browser.newContext({ baseURL: AUTH_ORIGIN });
  const page = await context.newPage();
  const email = uniqueEmail();
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  // Register without requesting delivery, so resend is the first purpose-scoped issuance.
  const status = await page.evaluate(
    async ({ email, password }) => {
      const csrf = await (await fetch('/api/auth/csrf')).json();
      return (
        await fetch('/api/auth/registrations', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRF-Token': csrf.csrfToken,
          },
          body: JSON.stringify({ email, password }),
        })
      ).status;
    },
    { email, password: PASSWORD },
  );
  if (status !== 202) throw new Error(`Registration status ${status}`);
  expect(status).toBe(202);
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  const denied = page.waitForResponse((response) =>
    response.url().endsWith('/api/auth/login'),
  );
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  expect((await denied).status()).toBe(403);
  const code = stack.nextCode('SIGNUP_VERIFY');
  await page.getByRole('button', { name: 'Resend verification code', exact: true }).click();
  await page.getByLabel('Email verification code', { exact: true }).fill(await code);
  await page.getByRole('button', { name: 'Complete signup', exact: true }).click();
  await expect(page.getByText('Email verified. Please sign in.')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toHaveCount(0);
  // Empty login form layout: credentials and member identity are not in this image buffer.
  await page.getByLabel('Email', { exact: true }).fill('');
  for (const width of [360, 390, 1280]) {
    await page.setViewportSize({ width, height: 900 });
    const navigationFits = await page.getByRole('navigation', { name: 'Main navigation' })
      .evaluateAll((nodes) => {
        if (!nodes.length) return true;
        const links = [...nodes[0].querySelectorAll('a')].map((link) =>
          link.getBoundingClientRect(),
        );
        return links.every(
          (rect, index) =>
            index === 0 || rect.left >= links[index - 1].right - 1,
        );
      });
    expect(navigationFits).toBe(true);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    // Only blank, credential-free login is retained outside the repository for visual review.
    if (process.env.E2E_VISUAL_DIR) {
      fs.mkdirSync(process.env.E2E_VISUAL_DIR, { recursive: true });
      await page.screenshot({
        path: path.join(process.env.E2E_VISUAL_DIR, `login-${width}.png`),
      });
    }
  }
  await context.close();
});
