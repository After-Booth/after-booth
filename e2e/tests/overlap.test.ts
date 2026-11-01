import { expect, test } from '@playwright/test';
import {
  intervalsOverlap,
  throwIfAllocationError,
  waitForAllocation,
  type AllocationResult,
  type CapturedAllocation,
} from './helpers';

function allocationUrl(requestId: number): string {
  return `http://localhost:18080/api/pickup-requests/${requestId}/allocation`;
}

function successEntry(requestId: number): CapturedAllocation {
  const body: AllocationResult = {
    requestId,
    status: 'HELD',
    explanation: 'held for test',
    reservations: [],
    rejections: [],
    alternatives: [],
    toolCalls: [],
  };
  return { url: allocationUrl(requestId), status: 200, body };
}

function jsonErrorEntry(requestId: number, status: number): CapturedAllocation {
  return {
    url: allocationUrl(requestId),
    status,
    body: { code: 'SERVICE_UNAVAILABLE', message: 'busy' } as unknown as AllocationResult,
  };
}

test('sequential HTTP intervals do not overlap even when UI completion overlaps', () => {
  const startA = 0;
  const endA = 10;
  const startB = 20;
  const endB = 30;
  const uiDoneA = 100;
  const uiDoneB = 100;
  const buggyGate = Math.max(startA, startB) < Math.min(uiDoneA, uiDoneB);
  expect(buggyGate, 'old HTTP-start vs UI-done gate would wrongly pass').toBe(true);
  expect(intervalsOverlap(startA, endA, startB, endB)).toBe(false);
});

test('genuinely overlapping HTTP intervals overlap', () => {
  expect(intervalsOverlap(0, 20, 10, 30)).toBe(true);
  expect(intervalsOverlap(10, 30, 0, 20)).toBe(true);
  expect(intervalsOverlap(0, 30, 10, 20)).toBe(true);
});

test('touching HTTP endpoints do not overlap', () => {
  expect(intervalsOverlap(0, 10, 10, 20)).toBe(false);
  expect(intervalsOverlap(10, 20, 0, 10)).toBe(false);
});

test('waitForAllocation returns the matching success entry', async () => {
  const store: CapturedAllocation[] = [successEntry(7)];
  const found = await waitForAllocation(store, 7, 1000);
  expect(found.status).toBe(200);
  expect(found.body.requestId).toBe(7);
});

test('waitForAllocation fail-fasts on contracted JSON error without requestId', async () => {
  const store: CapturedAllocation[] = [jsonErrorEntry(11, 503)];
  await expect(waitForAllocation(store, 11, 1000)).rejects.toThrow(
    /HTTP 503.*\/api\/pickup-requests\/11\/allocation/,
  );
});

test('waitForAllocation fail-fasts on non-2xx status-only entry', async () => {
  const store: CapturedAllocation[] = [{ url: allocationUrl(12), status: 500 }];
  await expect(waitForAllocation(store, 12, 1000)).rejects.toThrow(
    /HTTP 500.*\/api\/pickup-requests\/12\/allocation/,
  );
});

test('waitForAllocation fail-fasts on malformed 2xx without requestId', async () => {
  const emptyJson: CapturedAllocation[] = [
    { url: allocationUrl(13), status: 200, body: {} as unknown as AllocationResult },
  ];
  await expect(waitForAllocation(emptyJson, 13, 1000)).rejects.toThrow(/missing requestId/);
  const statusOnlyOk: CapturedAllocation[] = [{ url: allocationUrl(13), status: 200 }];
  await expect(waitForAllocation(statusOnlyOk, 13, 1000)).rejects.toThrow(/missing requestId/);
});

test('waitForAllocation ignores unrelated URLs and times out', async () => {
  const store: CapturedAllocation[] = [successEntry(999)];
  await expect(waitForAllocation(store, 14, 200)).rejects.toThrow(/Timed out/);
});

test('throwIfAllocationError preserves success when both success and error exist', () => {
  const store: CapturedAllocation[] = [jsonErrorEntry(15, 503), successEntry(15)];
  expect(() => throwIfAllocationError(store, 15)).not.toThrow();
});

test('throwIfAllocationError throws on HTTP error and malformed bodies', () => {
  expect(() => throwIfAllocationError([jsonErrorEntry(16, 503)], 16)).toThrow(/HTTP 503/);
  expect(() =>
    throwIfAllocationError(
      [{ url: allocationUrl(17), status: 200, body: {} as unknown as AllocationResult }],
      17,
    ),
  ).toThrow(/missing requestId/);
  expect(() => throwIfAllocationError([], 18)).not.toThrow();
});
