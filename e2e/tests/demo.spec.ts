import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import fs from 'node:fs';
import { performance } from 'node:perf_hooks';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  BASE_URL,
  LatencyLog,
  REJECTION_CODES,
  apiGet,
  captureAllocations,
  failFastOnAllocationError,
  formatLatencyTable,
  intervalsOverlap,
  waitForAllocation,
  type AllocationResult,
  type CapturedAllocation,
  type Event,
  type Item,
  type Organization,
  type Reservation,
  type ReuseStats,
} from './helpers';

// Exact demo inputs from fixtures/README.md section "Demo scenario". Byte-identical
// files and the exact race phrase are required for replay fixtures to match.
const SAMPLES_DIR = fileURLToPath(new URL('../../spike/samples/', import.meta.url));
const PHOTO_BINS = path.join(SAMPLES_DIR, 'bins-orange-stack.jpg');
const AUDIO_SEDAN = path.join(SAMPLES_DIR, 'audio-receiver-racks-sedan.wav');
const PHOTO_RACK = path.join(SAMPLES_DIR, 'rack-gondola-single.jpg');

const ORG_FIRST = 'Community Makers';
const ORG_RACE_A = 'Seoul Event Collective';
const ORG_RACE_B = 'Campus Festival Crew';
const RACE_TEXT = '진열대 한 개가 필요해요. 밴으로 가져갈게요.';

const AI_TIMEOUT_MS = 90_000;
const PHOTO_DEMO_BAR_MS = 15_000;

interface PickupRequest {
  id: number;
  organizationId: number;
  category: string;
  quantity: number;
  vehicleType: string;
}

async function readSavedRequestId(page: Page): Promise<number> {
  const text = await page.getByText(/요청 번호 #\d+/).textContent();
  const match = text?.match(/요청 번호 #(\d+)/);
  if (!match) throw new Error('Saved request number was not shown');
  return Number(match[1]);
}

// Records each allocation HTTP interval on the same monotonic clock, so the
// race overlap proof rests on server-bound traffic rather than on click or
// UI-visibility timestamps.
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

async function runPhotoIntake(page: Page, log: LatencyLog): Promise<number> {
  await page.goto('/');
  const input = page.locator('input[type="file"][accept="image/jpeg,image/png"]');
  const uploadStart = log.now();
  await input.setInputFiles(PHOTO_BINS);
  await expect(page.getByLabel('분류별 수량').getByText('수납함 4개')).toBeVisible({
    timeout: AI_TIMEOUT_MS,
  });
  const photoMs = log.now() - uploadStart;
  log.add('photo intake: upload bins photo -> auto result', photoMs);
  expect(photoMs, 'photo to auto result within the 15s demo bar').toBeLessThan(PHOTO_DEMO_BAR_MS);

  await log.measure('photo intake: confirm registration', async () => {
    await page.getByRole('button', { name: '재고로 등록' }).click();
    await expect(page.getByText('재고로 등록했어요.')).toBeVisible();
  });
  return photoMs;
}

async function runVoiceRequest(page: Page, log: LatencyLog): Promise<number> {
  await page.goto('/#/request');
  await page.locator('#organization').selectOption({ label: ORG_FIRST });
  await log.measure('request #1: voice WAV -> interpretation', async () => {
    await page
      .locator('input[type="file"][accept="audio/wav,.wav"]')
      .setInputFiles(AUDIO_SEDAN);
    await expect(page.getByText('3. 조건을 확인해 주세요')).toBeVisible({
      timeout: AI_TIMEOUT_MS,
    });
  });
  await expect(page.locator('#category')).toHaveValue('DISPLAY_RACK');
  await expect(page.locator('#quantity')).toHaveValue('3');
  await expect(page.locator('#vehicleType')).toHaveValue('SEDAN');
  await expect(page.locator('#purpose')).toHaveValue('주말 행사용');

  const requestId = await log.measure('request #1: save pickup request', async () => {
    await page.getByRole('button', { name: '픽업 요청 저장' }).click();
    await expect(page.getByText('픽업 요청을 저장했어요')).toBeVisible();
    return readSavedRequestId(page);
  });
  return requestId;
}

async function runFirstAllocation(
  page: Page,
  log: LatencyLog,
  allocations: CapturedAllocation[],
  requestId: number,
): Promise<AllocationResult> {
  await log.measure('request #1: allocation proposal (sedan cannot fit)', async () => {
    await page.getByRole('button', { name: '배정 제안 받기' }).click();
    const abort = new AbortController();
    try {
      await Promise.race([
        expect(page.getByText('조건에 맞는 물품을 확보하지 못했어요.')).toBeVisible({
          timeout: AI_TIMEOUT_MS,
        }),
        failFastOnAllocationError(allocations, requestId, { signal: abort.signal }),
      ]);
    } finally {
      abort.abort();
    }
  });
  const captured = await waitForAllocation(allocations, requestId);
  expect(captured.status).toBe(200);
  return captured.body;
}

async function prepareRaceRequest(page: Page, orgName: string): Promise<number> {
  await page.goto('/#/request');
  await page.locator('#organization').selectOption({ label: orgName });
  await page.locator('#request-text').fill(RACE_TEXT);
  await page.getByRole('button', { name: '입력한 내용 해석하기' }).click();
  await expect(page.getByText('3. 조건을 확인해 주세요')).toBeVisible({
    timeout: AI_TIMEOUT_MS,
  });
  await expect(page.locator('#category')).toHaveValue('DISPLAY_RACK');
  await expect(page.locator('#quantity')).toHaveValue('1');
  await expect(page.locator('#vehicleType')).toHaveValue('VAN');
  await page.getByRole('button', { name: '픽업 요청 저장' }).click();
  await expect(page.getByText('픽업 요청을 저장했어요')).toBeVisible();
  return readSavedRequestId(page);
}

test('demo scenario from photo intake to reuse stats', async ({ browser, request }) => {
  for (const file of [PHOTO_BINS, AUDIO_SEDAN, PHOTO_RACK]) {
    if (!fs.existsSync(file)) throw new Error(`Missing sample file: ${file}`);
  }
  const log = new LatencyLog();
  const contexts: BrowserContext[] = [];
  try {
    await log.measure('guard: server health and replay mode', async () => {
      const health = await apiGet<{ status: string; aiMode: string }>(request, '/api/health');
      expect(health.status).toBe('UP');
      expect(health.aiMode, 'E2E runs in replay mode only').toBe('replay');
    });
    const organizations = await apiGet<Organization[]>(request, '/api/organizations');
    const orgId = (name: string) => {
      const found = organizations.find((org) => org.name === name);
      if (!found) throw new Error(`Missing seeded organization: ${name}`);
      return found.id;
    };

    const main = await browser.newContext({ baseURL: BASE_URL });
    contexts.push(main);
    const page = await main.newPage();
    const mainAllocations: CapturedAllocation[] = [];
    captureAllocations(page, mainAllocations);

    const photoMs = await test.step('photo intake', () => runPhotoIntake(page, log));

    const firstRequestId = await test.step('voice pickup request', () =>
      runVoiceRequest(page, log),
    );
    const firstSaved = await apiGet<PickupRequest>(
      request,
      `/api/pickup-requests/${firstRequestId}`,
    );
    expect(firstSaved.organizationId).toBe(orgId(ORG_FIRST));
    expect(firstSaved.category).toBe('DISPLAY_RACK');
    expect(firstSaved.quantity).toBe(3);
    expect(firstSaved.vehicleType).toBe('SEDAN');

    const firstAllocation = await test.step('first allocation proposal', () =>
      runFirstAllocation(page, log, mainAllocations, firstRequestId),
    );
    expect(firstAllocation.status).toBe('NO_MATCH');
    expect(firstAllocation.reservations).toHaveLength(0);
    expect(
      firstAllocation.rejections.some((rejection) => rejection.code === 'VEHICLE_TOO_SMALL'),
      'sedan rejection explains the 180cm rack does not fit',
    ).toBe(true);
    expect(firstAllocation.explanation).toMatch(/[가-힣]/);
    const firstHolds = await apiGet<Reservation[]>(
      request,
      `/api/reservations?requestId=${firstRequestId}`,
    );
    expect(firstHolds).toHaveLength(0);

    // D-036 precondition, verified rather than assumed: the photo step must have
    // added bins only, leaving exactly one available display rack for the race.
    const rackId = await test.step('pre-race stock check', async () => {
      const items = await log.measure('pre-race API check: stock of one rack', () =>
        apiGet<Item[]>(request, '/api/items?availableOnly=true'),
      );
      const racks = items.filter((item) => item.category === 'DISPLAY_RACK');
      expect(racks, 'exactly one available display rack before the race').toHaveLength(1);
      expect(racks[0].availableQty).toBe(1);
      const allItems = await apiGet<Item[]>(request, '/api/items');
      expect(
        allItems.filter((item) => item.category === 'DISPLAY_RACK'),
        'photo intake added no racks',
      ).toHaveLength(1);
      expect(
        allItems
          .filter((item) => item.category === 'STORAGE_BIN')
          .reduce((sum, item) => sum + item.totalQty, 0),
        'photo intake added 4 bins to the 12 seeded bins',
      ).toBe(16);
      return racks[0].id;
    });

    const raceA = await browser.newContext({ baseURL: BASE_URL });
    const raceB = await browser.newContext({ baseURL: BASE_URL });
    contexts.push(raceA, raceB);
    const pageA = await raceA.newPage();
    const pageB = await raceB.newPage();
    const allocationsA: CapturedAllocation[] = [];
    const allocationsB: CapturedAllocation[] = [];
    const startsA = new Map<number, number>();
    const endsA = new Map<number, number>();
    const startsB = new Map<number, number>();
    const endsB = new Map<number, number>();
    captureAllocations(pageA, allocationsA);
    captureAllocations(pageB, allocationsB);
    watchAllocationIntervals(pageA, startsA, endsA);
    watchAllocationIntervals(pageB, startsB, endsB);

    const requestA = await test.step('race setup A', () =>
      log.measure('race A: same text -> saved request', () =>
        prepareRaceRequest(pageA, ORG_RACE_A),
      ),
    );
    const requestB = await test.step('race setup B', () =>
      log.measure('race B: same text -> saved request', () =>
        prepareRaceRequest(pageB, ORG_RACE_B),
      ),
    );
    expect(await apiGet<PickupRequest>(request, `/api/pickup-requests/${requestA}`))
      .toMatchObject({ organizationId: orgId(ORG_RACE_A), category: 'DISPLAY_RACK', quantity: 1 });
    expect(await apiGet<PickupRequest>(request, `/api/pickup-requests/${requestB}`))
      .toMatchObject({ organizationId: orgId(ORG_RACE_B), category: 'DISPLAY_RACK', quantity: 1 });

    const race = await test.step('two-context race for the last rack', async () => {
      const buttonA = pageA.getByRole('button', { name: '배정 제안 받기' });
      const buttonB = pageB.getByRole('button', { name: '배정 제안 받기' });
      await expect(buttonA).toBeEnabled({ timeout: 30_000 });
      await expect(buttonB).toBeEnabled({ timeout: 30_000 });
      const fire = async (
        page: Page,
        button: typeof buttonA,
        allocations: CapturedAllocation[],
        raceRequestId: number,
      ) => {
        const dispatch = performance.now();
        await button.click();
        const sent = performance.now();
        const abort = new AbortController();
        try {
          await Promise.race([
            expect(
              page.getByText(/배정 성공|조건에 맞는 물품을 확보하지 못했어요/),
            ).toBeVisible({ timeout: AI_TIMEOUT_MS }),
            failFastOnAllocationError(allocations, raceRequestId, { signal: abort.signal }),
          ]);
        } finally {
          abort.abort();
        }
        return { dispatch, sent, done: performance.now() };
      };
      const wallStart = log.now();
      const [outA, outB] = await Promise.all([
        fire(pageA, buttonA, allocationsA, requestA),
        fire(pageB, buttonB, allocationsB, requestB),
      ]);
      log.add('race: both allocations dispatched together', log.now() - wallStart);
      log.add('race allocation A: click -> outcome', outA.done - outA.dispatch);
      log.add('race allocation B: click -> outcome', outB.done - outB.dispatch);

      const captureA = await waitForAllocation(allocationsA, requestA);
      const captureB = await waitForAllocation(allocationsB, requestB);
      expect(captureA.status).toBe(200);
      expect(captureB.status).toBe(200);
      return { outA, outB, captureA: captureA.body, captureB: captureB.body };
    });

    // Concurrency evidence: both allocation HTTP intervals overlapped in
    // flight. UI-visibility times stay latency info only; a sequential HTTP
    // run cannot satisfy this HTTP-interval assertion.
    const startA = startsA.get(requestA);
    const startB = startsB.get(requestB);
    const endA = endsA.get(requestA);
    const endB = endsB.get(requestB);
    if (
      startA === undefined ||
      startB === undefined ||
      endA === undefined ||
      endB === undefined
    ) {
      throw new Error('Allocation HTTP interval was not observed on both race pages');
    }
    const clickGapMs = Math.abs(race.outA.sent - race.outB.sent);
    const requestGapMs = Math.abs(startA - startB);
    const httpOverlap = intervalsOverlap(startA, endA, startB, endB);
    expect(httpOverlap, 'race allocation HTTP intervals overlapped in flight').toBe(true);

    const wonA = race.captureA.status === 'HELD';
    const wonB = race.captureB.status === 'HELD';
    expect([wonA, wonB].filter(Boolean), 'exactly one race winner').toHaveLength(1);
    const winner = wonA
      ? { page: pageA, requestId: requestA, result: race.captureA }
      : { page: pageB, requestId: requestB, result: race.captureB };
    const loser = wonA
      ? { page: pageB, requestId: requestB, result: race.captureB }
      : { page: pageA, requestId: requestA, result: race.captureA };
    expect(loser.result.status).toBe('NO_MATCH');
    await expect(winner.page.getByText(/배정 성공/)).toBeVisible();
    await expect(loser.page.getByText('조건에 맞는 물품을 확보하지 못했어요.')).toBeVisible();

    const branch = raceBranch(loser.result);
    expect(loser.result.reservations).toHaveLength(0);
    expect(loser.result.explanation).toMatch(/[가-힣]/);
    for (const rejection of loser.result.rejections) {
      expect(REJECTION_CODES.has(rejection.code), `contract rejection code ${rejection.code}`).toBe(
        true,
      );
      expect(rejection.message.length).toBeGreaterThan(0);
    }
    expect(
      loser.result.rejections.some(
        (rejection) => rejection.itemId === rackId && rejection.code === 'QTY_UNAVAILABLE',
      ),
      'loser rejection names the last rack as out of stock',
    ).toBe(true);
    expect(loser.result.alternatives.length, 'loser gets an alternative').toBeGreaterThan(0);
    for (const alternative of loser.result.alternatives) {
      expect(alternative.quantity).toBeGreaterThanOrEqual(1);
      expect(alternative.reason.length).toBeGreaterThan(0);
    }
    const rejectedIds = new Set(loser.result.rejections.map((rejection) => rejection.itemId));
    for (const alternative of loser.result.alternatives) {
      expect(rejectedIds.has(alternative.item.id), 'alternative is not also rejected').toBe(false);
    }
    const firstAlternative = loser.result.alternatives[0];
    const alternativeDisplayQty = Math.min(firstAlternative.quantity, 1);
    const alternativeCard = loser.page
      .locator('article', { hasText: firstAlternative.reason })
      .first();
    await expect(
      alternativeCard.getByText(`${firstAlternative.item.label} ${alternativeDisplayQty}개`),
    ).toBeVisible();
    await expect(alternativeCard.getByText(firstAlternative.reason)).toBeVisible();
    const alternativeButton = alternativeCard.getByRole('button', {
      name: `${firstAlternative.item.label} 대안 선택`,
    });
    await expect(alternativeButton).toBeVisible();
    await expect(alternativeButton).toBeEnabled();

    await test.step('post-race stock check', async () => {
      const winnerHolds = await apiGet<Reservation[]>(
        request,
        `/api/reservations?requestId=${winner.requestId}`,
      );
      expect(winnerHolds).toHaveLength(1);
      expect(winnerHolds[0].status).toBe('HELD');
      expect(winnerHolds[0].itemId).toBe(rackId);
      expect(winnerHolds[0].quantity).toBe(1);
      expect(await apiGet<Reservation[]>(request, `/api/reservations?requestId=${loser.requestId}`))
        .toHaveLength(0);
      const allHolds = await apiGet<Reservation[]>(request, '/api/reservations');
      expect(
        allHolds.filter((hold) => hold.itemId === rackId),
        'exactly one hold on the last rack',
      ).toHaveLength(1);
      const items = await apiGet<Item[]>(request, '/api/items');
      for (const item of items) {
        expect(item.availableQty, `stock of item ${item.id} is never negative`).toBeGreaterThanOrEqual(
          0,
        );
      }
      expect(items.find((item) => item.id === rackId)?.availableQty).toBe(0);
    });

    const reservationId = winner.result.reservations[0]?.id;
    if (!reservationId) throw new Error('Winner allocation held no reservation');
    await test.step('winner confirms the held reservation', () =>
      log.measure('winner: confirm held reservation in UI', async () => {
        const confirm = winner.page.getByRole('button', { name: '진열대 예약 확정하기' });
        await confirm.click();
        await expect(confirm).toBeHidden({ timeout: 30_000 });
        await expect(winner.page.getByText('진열대 1개 · 예약 확정')).toBeVisible();
      }),
    );
    const confirmed = await apiGet<Reservation>(request, `/api/reservations/${reservationId}`);
    expect(confirmed.status).toBe('CONFIRMED');

    await test.step('handover and reuse stats', () =>
      log.measure('handover: rack photo -> completed', async () => {
        await winner.page.goto('/#/handover');
        await expect(winner.page.getByText('물품 인계')).toBeVisible();
        await winner.page.getByLabel('확정된 예약').selectOption(String(reservationId));
        await winner.page
          .locator('input[type="file"][accept="image/jpeg,image/png"]')
          .setInputFiles(PHOTO_RACK);
        await expect(winner.page.getByText('인계를 완료했어요')).toBeVisible({
          timeout: AI_TIMEOUT_MS,
        });
        await expect(winner.page.getByText('진열대 1개가 다시 사용돼요.')).toBeVisible();
      }),
    );

    await test.step('stats agreement', async () => {
      const stats = await log.measure('stats: UI and API agreement', () =>
        apiGet<ReuseStats>(request, '/api/stats/reuse'),
      );
      expect(stats.totalReused).toBe(1);
      expect(stats.reusedVolumeLiters, 'seeded 90×40×180 cm rack').toBe(648);
      expect(
        stats.byCategory.find((entry) => entry.category === 'DISPLAY_RACK')?.quantity,
      ).toBe(1);
      expect(stats.byEvent).toHaveLength(1);
      expect(stats.byEvent[0].quantity).toBe(1);
      const events = await apiGet<Event[]>(request, '/api/events');
      expect(stats.byEvent[0].eventId).toBe(events[0].id);

      await expect(winner.page.getByText('총 1개 재사용')).toBeVisible();
      await expect(winner.page.getByText('진열대 · 1개')).toBeVisible();
      await expect(winner.page.getByText('부피 약 648L')).toBeVisible();
      await expect(winner.page.getByText(`${events[0].name} · 1개`)).toBeVisible();

      const handedOver = await apiGet<Reservation>(request, `/api/reservations/${reservationId}`);
      expect(handedOver.status).toBe('HANDED_OVER');
      expect(handedOver.handedOverQty, 'handed over quantity matches reuse stats').toBe(
        stats.totalReused,
      );
      const items = await apiGet<Item[]>(request, '/api/items');
      expect(items.find((item) => item.id === rackId)?.availableQty).toBe(0);
    });

    await test.step('latency report', async () => {
      const runTag = process.env.E2E_RUN_TAG ?? 'run';
      const report = {
        mode: 'replay',
        baseURL: BASE_URL,
        at: new Date().toISOString(),
        n: 1,
        runTag,
        photoToResultMs: Math.round(photoMs),
        photoDemoBar15s: photoMs < PHOTO_DEMO_BAR_MS ? 'PASS' : 'FAIL',
        clickGapMs: Math.round(clickGapMs * 10) / 10,
        requestStartGapMs: Math.round(requestGapMs * 10) / 10,
        httpOverlap,
        httpDurationAMs: Math.round((endA - startA) * 10) / 10,
        httpDurationBMs: Math.round((endB - startB) * 10) / 10,
        raceBranch: branch,
        winnerRequestId: winner.requestId,
        loserRequestId: loser.requestId,
        reservationId,
        steps: log.entries(),
      };
      const dir = test.info().outputDir;
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, `latency-${runTag}.json`), JSON.stringify(report, null, 2));
      console.log(
        formatLatencyTable(log.entries(), {
          mode: 'replay',
          baseURL: BASE_URL,
          run: runTag,
          branch,
        }),
      );
      console.log(
        `race: click gap ${clickGapMs.toFixed(1)}ms, ` +
          `request gap ${requestGapMs.toFixed(1)}ms, ` +
          `http overlap ${httpOverlap} ` +
          `(A ${(endA - startA).toFixed(1)}ms, B ${(endB - startB).toFixed(1)}ms), ` +
          `branch ${branch}, winner request #${winner.requestId}`,
      );
    });
  } finally {
    for (const context of contexts) await context.close().catch(() => undefined);
  }
});
