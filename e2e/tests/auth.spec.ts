import { chromium, expect, test, type Browser, type Page } from '@playwright/test';
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
  await page.getByRole('button', { name: '회원가입' }).click();
  await page.getByLabel('이메일', { exact: true }).fill(email);
  await page.getByLabel('비밀번호').fill(PASSWORD);
  const code = stack.nextCode('SIGNUP_VERIFY');
  await page.getByRole('button', { name: '인증 메일 요청' }).click();
  await page.getByLabel('이메일 인증번호').fill(await code);
  await page.getByRole('button', { name: '가입 완료' }).click();
  await expect(page.getByText('이메일 인증이 완료되었어요. 로그인해 주세요.')).toBeVisible();
}

const tokens = new WeakMap<Page, string>();

async function logIn(page: Page, email: string) {
  page.on('request', (request) => {
    const header = request.headers()['authorization'];
    if (header && request.url().includes('/api/')) tokens.set(page, header);
  });
  await page.getByLabel('이메일', { exact: true }).fill(email);
  await page.getByLabel('비밀번호').fill(PASSWORD);
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await expect(page.getByRole('navigation', { name: '주요 메뉴' })).toBeVisible();
}

async function createEvent(page: Page, name: string) {
  await page.getByText('새 행사 만들기').click();
  await page.getByLabel('행사 이름').fill(name);
  await page.getByLabel('장소').fill('Synthetic Hall');
  await page.getByLabel('철수 마감 시간').fill('2030-01-01T18:00');
  await page.getByRole('button', { name: '행사 만들기' }).click();
  await expect(page.getByText('행사를 만들었어요. 이제 사진을 등록해 주세요.')).toBeVisible();
}

test('signup, verification, login, account lookup, own event and photo intake on the real server', async () => {
  const context = await browser.newContext({ baseURL: AUTH_ORIGIN });
  const page = await context.newPage();
  const email = uniqueEmail();

  await signUp(page, email);
  // A fresh login is required after verification: no automatic login.
  await expect(page.getByRole('navigation', { name: '주요 메뉴' })).toHaveCount(0);
  await logIn(page, email);

  await page.goto('/#/account');
  await expect(page.getByText(`이메일: ${email}`)).toBeVisible();
  await expect(page.getByText('상태: ACTIVE')).toBeVisible();
  await expect(page.getByText(/^단체 번호: \d+$/)).toBeVisible();

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
  await expect(page.getByLabel('분류별 수량').getByText('수납함 4개')).toBeVisible({
    timeout: AI_TIMEOUT_MS,
  });
  await page.getByRole('button', { name: '재고로 등록' }).click();
  await expect(page.getByText('재고로 등록했어요.')).toBeVisible();

  // A reload restores the session through the refresh cookie without a new login.
  await page.reload();
  await expect(page.getByRole('navigation', { name: '주요 메뉴' })).toBeVisible();

  await page.goto('/#/account');
  await page.getByRole('button', { name: '로그아웃' }).click();
  await expect(page.getByRole('heading', { name: '다시 만나요' })).toBeVisible();
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
  await expect(secondPage.getByRole('navigation', { name: '주요 메뉴' })).toBeVisible();
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
  await expect(giver.getByLabel('분류별 수량').getByText('수납함 4개')).toBeVisible({
    timeout: AI_TIMEOUT_MS,
  });
  await giver.getByRole('button', { name: '재고로 등록' }).click();
  await expect(giver.getByText('재고로 등록했어요.')).toBeVisible();
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
  await receiver.getByRole('button', { name: /예약 확정하기$/ }).click();
  await expect
    .poll(async () => (await call(receiver, 'GET', `/api/reservations/${reservationId}`)).body.status)
    .toBe('CONFIRMED');

  await giver.goto('/#/handover');
  await expect(
    giver.getByRole('option', { name: new RegExp(`예약 #${reservationId} `) }),
  ).toHaveCount(1);

  // Withdrawal is rejected while a CONFIRMED reservation exists, for either side.
  for (const [page, email] of [
    [receiver, receiverEmail],
    [giver, giverEmail],
  ] as const) {
    await page.goto('/#/account');
    page.once('dialog', (dialog) => void dialog.accept());
    await page.getByLabel('현재 비밀번호').fill(PASSWORD);
    const answer = page.waitForResponse(
      (r) => r.url().endsWith('/api/members/me') && r.request().method() === 'DELETE',
    );
    await page.getByRole('button', { name: '회원 탈퇴' }).click();
    const response = await answer;
    expect(response.status()).toBe(409);
    expect((await response.json()).code).toBe('WITHDRAWAL_BLOCKED');
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByText(`이메일: ${email}`)).toBeVisible();
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
    await page.getByLabel('현재 비밀번호').fill(PASSWORD);
    await page.getByRole('button', { name: '회원 탈퇴' }).click();
    await expect(page.getByRole('heading', { name: '다시 만나요' })).toBeVisible();
    await page.getByLabel('이메일', { exact: true }).fill(email);
    await page.getByLabel('비밀번호').fill(PASSWORD);
    await page.getByRole('button', { name: '로그인', exact: true }).click();
    await expect(page.getByRole('heading', { name: '다시 만나요' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: '주요 메뉴' })).toHaveCount(0);
  }

  await giverContext.close();
  await receiverContext.close();
});
