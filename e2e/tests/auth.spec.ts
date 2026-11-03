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

async function logIn(page: Page, email: string) {
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
