import { chromium, expect, test, type Browser, type BrowserContext, type Page,} from '@playwright/test';
import { performance } from 'node:perf_hooks';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { AUTH_ORIGIN, startAuthStack, uniqueEmail, type AuthStack,} from './authStack';
import {captureAllocations, intervalsOverlap, waitForAllocation, REJECTION_CODES,
  type AllocationResult, type CapturedAllocation, type Item, type Reservation, type ReuseStats,} from './helpers';

const PHOTO_BINS = fileURLToPath(new URL('../../spike/samples/bins-orange-stack.jpg', import.meta.url));
const PHOTO_RACK = fileURLToPath(new URL('../../spike/samples/rack-gondola-single.jpg', import.meta.url));
// This original recording is Korean audio. Only the generated purpose/explanation must be English.
const AUDIO_SEDAN = fileURLToPath(new URL('../../spike/samples/audio-receiver-racks-sedan.wav', import.meta.url));
// Exact proposed input for the future approved recording; no existing recording is translated.
const RACE_TEXT = 'We need one display rack. We will collect it in a van.';
const PASSWORD = `Synthetic-${randomUUID()}`;
const AI_TIMEOUT_MS = 90_000;
// Remove this gate only with reviewed, approved recordings for this exact owned-data scenario.
const ENGLISH_RECORDINGS_READY = false;
let stack: AuthStack;
let browser: Browser;
const tokens = new WeakMap<Page, string>();

test.beforeAll(async () => {
  if (!ENGLISH_RECORDINGS_READY) {
    process.stdout.write(
      '[demo readiness] BLOCKED: approved English recordings are missing; no live calls made.\n',
    );
    throw new Error('Full authenticated English demo is BLOCKED: approved recordings for the owned rack intake, English condition/allocation requests and handover are missing. No live call is authorized.');
  }
  stack = await startAuthStack({ ownedDemo: true });
  browser = await chromium.launch({
    args: [`--ignore-certificate-errors-spki-list=${stack.spki}`],
  });
});
test.afterAll(async () => {
  await browser?.close();
  await stack?.stop();
});

async function apiGet<T>(page: Page, url: string): Promise<T> {
  const result = await page.evaluate(
    async ({ url, authorization }) => {
      const response = await fetch(url, { headers: { authorization } });
      return { status: response.status, body: await response.json() };
    },
    { url, authorization: tokens.get(page) ?? '' },
  );
  expect(result.status).toBe(200);
  return result.body as T;
}

async function verifiedMember(contexts: BrowserContext[]) {
  const context = await browser.newContext({ baseURL: AUTH_ORIGIN });
  contexts.push(context);
  const page = await context.newPage();
  page.on('request', (request) => {
    const header = request.headers()['authorization'];
    if (header && request.url().includes('/api/')) tokens.set(page, header);
  });
  const email = uniqueEmail();
  await page.goto('/');
  await page
    .getByRole('button', { name: 'Create account', exact: true })
    .click();
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  const code = stack.nextCode('SIGNUP_VERIFY');
  await page
    .getByRole('button', { name: 'Request verification email', exact: true })
    .click();
  await page
    .getByLabel('Email verification code', { exact: true })
    .fill(await code);
  await page
    .getByRole('button', { name: 'Complete signup', exact: true })
    .click();
  await expect(page.getByText('Email verified. Please sign in.')).toBeVisible();
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(
    page.getByRole('navigation', { name: 'Main navigation' }),
  ).toBeVisible();
  await expect.poll(() => tokens.has(page)).toBe(true);
  const member = await apiGet<{ organizationId: number }>(
    page,
    '/api/members/me',
  );
  return { page, organizationId: member.organizationId };
}

async function createOwnedEvent(page: Page) {
  await page.getByText('Create a new event', { exact: true }).click();
  await page
    .getByLabel('Event name', { exact: true })
    .fill('Synthetic Reuse Expo');
  await page.getByLabel('Venue', { exact: true }).fill('Synthetic Hall');
  await page
    .getByLabel('Teardown deadline (Asia/Seoul, KST)', { exact: true })
    .fill('2030-01-01T18:00');
  await page.getByRole('button', { name: 'Create event', exact: true }).click();
  await expect(page.getByText('Event created. Add your photo now.')).toBeVisible();
  const events = await apiGet<Array<{ id: number; name: string }>>(
    page,
    '/api/events',
  );
  expect(events).toHaveLength(1);
  return events[0];
}

async function intake(
  page: Page,
  eventId: number,
  photo: string,
  summary: string,
) {
  await page.goto('/#/account');
  await page.goto('/#/');
  await page
    .getByLabel('Event for registration', { exact: true })
    .selectOption(String(eventId));
  const start = performance.now();
  await page.locator('input[type="file"][accept="image/jpeg,image/png"]')
    .setInputFiles(photo);
  await expect(
    page.getByLabel('Quantity by category').getByText(summary, { exact: true }),
  ).toBeVisible({
    timeout: AI_TIMEOUT_MS,
  });
  const latency = performance.now() - start;
  await page
    .getByRole('button', { name: 'Register inventory', exact: true })
    .click();
  await expect(page.getByText('Inventory registered.')).toBeVisible();
  return latency;
}

async function saveRequest(page: Page) {
  await page
    .getByRole('button', { name: 'Save pickup request', exact: true })
    .click();
  const label = page.getByText(/Request ID #\d+/);
  await expect(label).toBeVisible();
  return Number((await label.textContent())?.match(/Request ID #(\d+)/)?.[1]);
}

async function prepareRequest(page: Page, voice: boolean) {
  await page.goto('/#/account');
  await page.goto('/#/request');
  if (voice) {
    await page.locator('input[type="file"][accept="audio/wav,.wav"]')
      .setInputFiles(AUDIO_SEDAN);
  } else {
    await page.locator('#request-text').fill(RACE_TEXT);
    await page
      .getByRole('button', { name: 'Interpret request', exact: true })
      .click();
  }
  await expect(page.getByText('3. Review your requirements')).toBeVisible({
    timeout: AI_TIMEOUT_MS,
  });
  await expect(page.locator('#category')).toHaveValue('DISPLAY_RACK');
  await expect(page.locator('#quantity')).toHaveValue(voice ? '3' : '1');
  await expect(page.locator('#vehicleType')).toHaveValue(
    voice ? 'SEDAN' : 'VAN',
  );
  if (voice) {
    expect(await page.locator('#purpose').inputValue()).toMatch(/[A-Za-z]/);
    expect(await page.locator('#purpose').inputValue()).not.toMatch(/[가-힣]/);
  } else {
    await expect(page.locator('#purpose')).toHaveValue('');
  }
  return saveRequest(page);
}

function watchAllocationIntervals(
  page: Page,
  starts: Map<number, number>,
  ends: Map<number, number>,
): void {
  const requestIdFromUrl = (url: string): number | undefined => {
    const match = new URL(url).pathname.match(/^\/api\/pickup-requests\/(\d+)\/allocation$/);
    return match ? Number(match[1]) : undefined;
  };
  page.on('request', (request) => {
    if (request.method() !== 'POST') return;
    const id = requestIdFromUrl(request.url());
    if (id !== undefined) starts.set(id, performance.now());
  });
  const recordEnd = (request: { method(): string; url(): string }): void => {
    if (request.method() !== 'POST') return;
    const id = requestIdFromUrl(request.url());
    if (id !== undefined) ends.set(id, performance.now());
  };
  page.on('requestfinished', recordEnd);
  page.on('requestfailed', recordEnd);
}

function raceBranch(loser: AllocationResult): string {
  const holdCall = loser.toolCalls.find((call) => call.name === 'holdQuantity');
  if (holdCall?.outcome === 'REJECTED' && holdCall.code === 'QTY_UNAVAILABLE') {
    return 'simultaneous-reject';
  }
  if (!holdCall && loser.rejections.some((rejection) => rejection.code === 'QTY_UNAVAILABLE')) {
    return 'stock-0-after-commit';
  }
  throw new Error(
    `Unrecognized loser branch: tools=${JSON.stringify(loser.toolCalls)} ` +
      `rejections=${JSON.stringify(loser.rejections)}`,
  );
}

test('authenticated owned inventory, allocation race, giver handover and reuse stats', async () => {
  const contexts: BrowserContext[] = [];
  const scenarioStart = performance.now();
  try {
    const giver = await verifiedMember(contexts);
    const receiverA = await verifiedMember(contexts);
    const receiverB = await verifiedMember(contexts);
    expect(
      new Set([
        giver.organizationId,
        receiverA.organizationId,
        receiverB.organizationId,
      ]).size,
    ).toBe(3);
    expect([giver.organizationId, receiverA.organizationId, receiverB.organizationId])
      .toEqual([1, 2, 3]);
    const health = await apiGet<{ aiMode: string }>(giver.page, '/api/health');
    expect(health.aiMode).toBe('replay');
    const event = await createOwnedEvent(giver.page);
    expect(event.id).toBe(1);
    const binsMs = await intake(
      giver.page,
      event.id,
      PHOTO_BINS,
      'Storage bin 4 items',
    );
    expect(
      binsMs,
      'photo to automatic inventory result within first 15 seconds',
    ).toBeLessThan(15_000);
    // A new owned-rack intake recording is required; no seeded rack is selected.
    await intake(giver.page, event.id, PHOTO_RACK, 'Display rack 1 items');
    const items = await apiGet<Item[]>(
      receiverA.page,
      '/api/items?availableOnly=true',
    );
    expect(
      items.every(
        (item) =>
          item.organizationId === giver.organizationId &&
          item.eventId === event.id,
      ),
    ).toBe(true);
    const racks = items.filter((item) => item.category === 'DISPLAY_RACK');
    expect(racks).toHaveLength(1);
    expect(racks[0].availableQty).toBe(1);
    expect(racks[0].id).toBe(2);
    expect(
      items.filter((item) => item.category === 'STORAGE_BIN')
        .reduce((sum, item) => sum + item.totalQty, 0),
    ).toBe(4);
    const rack = await apiGet<
      Item & { sizeCm: { width: number; depth: number; height: number } }
    >(receiverA.page, `/api/items/${racks[0].id}`);
    const baseline = await apiGet<ReuseStats>(
      giver.page,
      `/api/stats/reuse?eventId=${event.id}`,
    );

    const allocationsA: CapturedAllocation[] = [];
    const allocationsB: CapturedAllocation[] = [];
    captureAllocations(receiverA.page, allocationsA);
    captureAllocations(receiverB.page, allocationsB);
    const sedanId = await prepareRequest(receiverA.page, true);
    expect(sedanId).toBe(1);
    await receiverA.page
      .getByRole('button', { name: 'Get allocation proposal', exact: true })
      .click();
    const sedan = await waitForAllocation(allocationsA, sedanId);
    expect(sedan.status).toBe(200);
    expect(sedan.body.status).toBe('NO_MATCH');
    expect(sedan.body.reservations).toHaveLength(0);
    expect(
      sedan.body.rejections.some(
        (entry) =>
          entry.code === 'VEHICLE_TOO_SMALL' && entry.itemId === rack.id,
      ),
    ).toBe(true);
    expect(sedan.body.alternatives.length).toBeGreaterThan(0);
    expect(sedan.body.explanation).toMatch(/[A-Za-z]/);
    expect(sedan.body.explanation).not.toMatch(/[가-힣]/);
    await expect(
      receiverA.page.getByText('No matching items could be held.', {
        exact: true,
      }),
    ).toBeVisible();

    const requestA = await prepareRequest(receiverA.page, false);
    const requestB = await prepareRequest(receiverB.page, false);
    expect([requestA, requestB]).toEqual([2, 3]);
    for (const [actor, requestId] of [
      [receiverA, requestA],
      [receiverB, requestB],
    ] as const) {
      expect(
        await apiGet<{ organizationId: number }>(
          actor.page,
          `/api/pickup-requests/${requestId}`,
        ),
      ).toMatchObject({ organizationId: actor.organizationId });
    }
    const startsA = new Map<number, number>(),
      endsA = new Map<number, number>();
    const startsB = new Map<number, number>(),
      endsB = new Map<number, number>();
    watchAllocationIntervals(receiverA.page, startsA, endsA);
    watchAllocationIntervals(receiverB.page, startsB, endsB);
    const raceStart = performance.now();
    await Promise.all([
      receiverA.page
        .getByRole('button', { name: 'Get allocation proposal', exact: true })
        .click(),
      receiverB.page
        .getByRole('button', { name: 'Get allocation proposal', exact: true })
        .click(),
    ]);
    const [resultA, resultB] = await Promise.all([
      waitForAllocation(allocationsA, requestA),
      waitForAllocation(allocationsB, requestB),
    ]);
    const raceMs = performance.now() - raceStart;
    expect(resultA.status).toBe(200);
    expect(resultB.status).toBe(200);
    await expect
      .poll(() => endsA.has(requestA) && endsB.has(requestB))
      .toBe(true);
    expect(
      intervalsOverlap(
        startsA.get(requestA)!,
        endsA.get(requestA)!,
        startsB.get(requestB)!,
        endsB.get(requestB)!,
      ),
    ).toBe(true);
    expect(
      [resultA.body, resultB.body].filter((result) => result.status === 'HELD'),
    ).toHaveLength(1);
    const winner =
      resultA.body.status === 'HELD'
        ? { ...receiverA, result: resultA.body, requestId: requestA }
        : { ...receiverB, result: resultB.body, requestId: requestB };
    const loser =
      resultA.body.status === 'HELD'
        ? { ...receiverB, result: resultB.body, requestId: requestB }
        : { ...receiverA, result: resultA.body, requestId: requestA };
    expect(loser.result.status).toBe('NO_MATCH');
    expect(['simultaneous-reject', 'stock-0-after-commit']).toContain(
      raceBranch(loser.result),
    );
    expect(loser.result.reservations).toHaveLength(0);
    expect(
      loser.result.rejections.some(
        (entry) => entry.itemId === rack.id && entry.code === 'QTY_UNAVAILABLE',
      ),
    ).toBe(true);
    for (const rejection of loser.result.rejections)
      expect(REJECTION_CODES.has(rejection.code)).toBe(true);
    expect(loser.result.explanation).toMatch(/[A-Za-z]/);
    expect(loser.result.explanation).not.toMatch(/[가-힣]/);
    expect(loser.result.alternatives.length).toBeGreaterThan(0);
    const alternative = loser.result.alternatives[0];
    expect(alternative.quantity).toBeGreaterThan(0);
    expect(alternative.item.id).not.toBe(rack.id);
    const card = loser.page
      .locator('article', { hasText: alternative.reason })
      .first();
    await expect(
      card.getByText(alternative.reason, { exact: true }),
    ).toBeVisible();
    await expect(
      card.getByRole('button', {
        name: `${alternative.item.label} Choose alternative`,
        exact: true,
      }),
    ).toBeEnabled();
    const winnerHolds = await apiGet<Reservation[]>(
      winner.page,
      `/api/reservations?requestId=${winner.requestId}`,
    );
    expect(winnerHolds).toHaveLength(1);
    expect(winnerHolds[0]).toMatchObject({
      itemId: rack.id,
      quantity: 1,
      status: 'HELD',
    });
    expect(
      await apiGet<Reservation[]>(
        loser.page,
        `/api/reservations?requestId=${loser.requestId}`,
      ),
    ).toHaveLength(0);
    const reservationId = winnerHolds[0].id;
    await winner.page.getByRole('button', {
        name: `${winnerHolds[0].itemLabel} Confirm reservation`,
        exact: true,
      })
      .click();
    await expect
      .poll(
        async () =>
          (
            await apiGet<Reservation>(
              winner.page,
              `/api/reservations/${reservationId}`,
            )
          ).status,
      )
      .toBe('CONFIRMED');
    const handoverStart = performance.now();
    await giver.page.goto('/#/handover');
    await giver.page
      .getByLabel('Confirmed reservations', { exact: true })
      .selectOption(String(reservationId));
    await giver.page
      .locator('input[type="file"][accept="image/jpeg,image/png"]')
      .setInputFiles(PHOTO_RACK);
    await expect(
      giver.page.getByText('Handover complete', { exact: true }),
    ).toBeVisible({
      timeout: AI_TIMEOUT_MS,
    });
    const handoverMs = performance.now() - handoverStart;
    const completed = await apiGet<Reservation>(
      giver.page,
      `/api/reservations/${reservationId}`,
    );
    expect(completed).toMatchObject({
      status: 'HANDED_OVER',
      handedOverQty: 1,
    });
    await giver.page
      .getByLabel('Filter by event', { exact: true })
      .selectOption(String(event.id));
    const stats = await apiGet<ReuseStats>(
      giver.page,
      `/api/stats/reuse?eventId=${event.id}`,
    );
    expect(stats.totalReused - baseline.totalReused).toBe(1);
    expect(stats.reusedVolumeLiters - baseline.reusedVolumeLiters).toBeCloseTo(
      (rack.sizeCm.width * rack.sizeCm.depth * rack.sizeCm.height) / 1000,
    );
    expect(
      stats.byCategory.find((entry) => entry.category === 'DISPLAY_RACK')?.quantity,
    ).toBe(1);
    expect(stats.byEvent).toEqual([
      expect.objectContaining({ eventId: event.id, quantity: 1 }),
    ]);
    await expect(
      giver.page.getByText(`${stats.totalReused} items reused in total`, {
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      giver.page.getByText(`${event.name} · 1 items`, { exact: true }),
    ).toBeVisible();
    const finalRack = await apiGet<Item>(
      receiverA.page,
      `/api/items/${rack.id}`,
    );
    expect(finalRack.availableQty).toBe(0);
    // Timing contains only local replay durations, never identity or request payloads.
    await test.info().attach('replay-latency.json', {
      body: Buffer.from(
        JSON.stringify({
          mode: 'replay',
          n: 1,
          binsPhotoToResultMs: Math.round(binsMs),
          concurrentRaceMs: Math.round(raceMs),
          giverPhotoHandoverMs: Math.round(handoverMs),
          scenarioMs: Math.round(performance.now() - scenarioStart),
        }),
      ),
      contentType: 'application/json',
    });
  } finally {
    for (const context of contexts) await context.close().catch(() => undefined);
  }
});
