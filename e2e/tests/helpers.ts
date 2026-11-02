import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { performance } from 'node:perf_hooks';

export const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:18080';

// Contract shapes used for verification reads (subset of contract/openapi.yaml).
export interface Organization {
  id: number;
  name: string;
}

export interface Event {
  id: number;
  name: string;
  organizationId: number;
  venue: string;
  teardownDeadline: string;
}

export interface Item {
  id: number;
  eventId: number;
  organizationId: number;
  category: 'DISPLAY_RACK' | 'STORAGE_BIN' | 'FOLDING_TABLE';
  label: string;
  totalQty: number;
  availableQty: number;
  pickupDeadline: string;
}

export interface Reservation {
  id: number;
  requestId: number;
  organizationId: number;
  itemId: number;
  itemLabel: string;
  quantity: number;
  handedOverQty?: number;
  status: 'HELD' | 'CONFIRMED' | 'HANDED_OVER' | 'RELEASED' | 'EXPIRED';
  holdExpiresAt: string;
  pickupDeadline: string;
  createdAt: string;
}

export interface ToolCall {
  name: 'searchCandidates' | 'checkVehicleFit' | 'holdQuantity';
  arguments: Record<string, unknown>;
  outcome: 'OK' | 'REJECTED';
  code?: string;
  summary?: string;
}

export interface Rejection {
  itemId: number;
  code: string;
  message: string;
  limitingDimension?: string;
}

export interface Alternative {
  item: Item;
  quantity: number;
  reason: string;
}

export interface AllocationResult {
  requestId: number;
  status: 'HELD' | 'NO_MATCH';
  explanation: string;
  reservations: Reservation[];
  rejections: Rejection[];
  alternatives: Alternative[];
  toolCalls: ToolCall[];
}

export interface ReuseStats {
  totalReused: number;
  reusedVolumeLiters: number;
  byCategory: { category: string; label: string; quantity: number }[];
  byEvent: { eventId: number; eventName: string; quantity: number }[];
}

export const REJECTION_CODES = new Set([
  'VEHICLE_TOO_SMALL',
  'QTY_UNAVAILABLE',
  'QTY_EXCEEDS_REQUEST',
  'PICKUP_CLOSED',
  'CATEGORY_MISMATCH',
]);

export async function apiGet<T>(request: APIRequestContext, path: string): Promise<T> {
  const response = await request.get(BASE_URL + path);
  expect(response.status(), `GET ${path}`).toBe(200);
  return (await response.json()) as T;
}

export interface LatencyStep {
  step: string;
  ms: number;
}

// Wall-clock recorder for the per-step latency table in the report.
export class LatencyLog {
  private readonly steps: LatencyStep[] = [];

  now(): number {
    return performance.now();
  }

  add(step: string, ms: number): void {
    this.steps.push({ step, ms });
  }

  async measure<T>(step: string, fn: () => Promise<T>): Promise<T> {
    const start = performance.now();
    try {
      return await fn();
    } finally {
      this.steps.push({ step, ms: performance.now() - start });
    }
  }

  entries(): LatencyStep[] {
    return [...this.steps];
  }
}

export interface CapturedAllocation {
  url: string;
  status: number;
  body?: AllocationResult;
}

// Pure predicate for the race overlap gate: strict overlap of two HTTP
// intervals on the same monotonic clock. Touching endpoints do not overlap.
export function intervalsOverlap(
  startA: number,
  endA: number,
  startB: number,
  endB: number,
): boolean {
  return Math.max(startA, startB) < Math.min(endA, endB);
}

function allocationPath(requestId: number): string {
  return `/api/pickup-requests/${requestId}/allocation`;
}

function isOk(status: number): boolean {
  return status >= 200 && status < 300;
}

// Synchronous fail-fast check for the current store contents. Matches the
// allocation URL first, then throws on non-2xx (with HTTP status and URL) or
// on malformed 2xx bodies (missing requestId). A valid success entry wins, so
// the success path never throws.
export function throwIfAllocationError(
  store: CapturedAllocation[],
  requestId: number,
): void {
  const matching = store.filter((entry) => entry.url.includes(allocationPath(requestId)));
  const hasSuccess = matching.some(
    (entry) => isOk(entry.status) && entry.body?.requestId === requestId,
  );
  if (hasSuccess) return;
  const httpError = matching.find((entry) => !isOk(entry.status));
  if (httpError) {
    throw new Error(
      `Allocation request #${requestId} failed with HTTP ${httpError.status} at ${httpError.url}`,
    );
  }
  const malformed = matching.find(
    (entry) => isOk(entry.status) && entry.body?.requestId !== requestId,
  );
  if (malformed) {
    throw new Error(
      `Allocation request #${requestId} returned malformed body (missing requestId) ` +
        `with HTTP ${malformed.status} at ${malformed.url}`,
    );
  }
}

// Never-resolving fail-fast for racing against UI outcome waits. Rejects
// promptly on HTTP error, stays pending on success so the UI wait still
// decides the success path. Callers abort after the race settles.
export function failFastOnAllocationError(
  store: CapturedAllocation[],
  requestId: number,
  options?: { signal?: AbortSignal; pollMs?: number },
): Promise<never> {
  const pollMs = options?.pollMs ?? 100;
  return (async (): Promise<never> => {
    for (;;) {
      if (options?.signal?.aborted) {
        await new Promise<never>(() => undefined);
      }
      throwIfAllocationError(store, requestId);
      await new Promise((resolve) => setTimeout(resolve, pollMs));
    }
  })();
}

// Passive observer for allocation responses. It never alters, blocks or replays
// traffic; the scenario path stays on the real backend in replay mode.
export function captureAllocations(page: Page, store: CapturedAllocation[]): void {
  page.on('response', (response) => {
    const request = response.request();
    if (request.method() !== 'POST') return;
    const path = new URL(request.url()).pathname;
    if (!/^\/api\/pickup-requests\/\d+\/allocation$/.test(path)) return;
    void response.json().then(
      (body) =>
        store.push({ url: request.url(), status: response.status(), body: body as AllocationResult }),
      // Non-JSON responses carry no requestId, so keep a status-only entry
      // matched by URL in waitForAllocation to fail fast with the real status.
      () => store.push({ url: request.url(), status: response.status() }),
    );
  });
}

export async function waitForAllocation(
  store: CapturedAllocation[],
  requestId: number,
  timeoutMs = 30_000,
): Promise<CapturedAllocation & { body: AllocationResult }> {
  const deadline = performance.now() + timeoutMs;
  for (;;) {
    const matching = store.filter((entry) => entry.url.includes(allocationPath(requestId)));
    const success = matching.find(
      (entry) => isOk(entry.status) && entry.body?.requestId === requestId,
    );
    if (success?.body) return { url: success.url, status: success.status, body: success.body };
    throwIfAllocationError(store, requestId);
    if (performance.now() > deadline) {
      throw new Error(`Timed out waiting for the allocation response of request #${requestId}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
}

export function formatLatencyTable(
  entries: LatencyStep[],
  header: Record<string, string>,
): string {
  const headerLine = Object.entries(header)
    .map(([key, value]) => `${key}=${value}`)
    .join(' ');
  const rows = entries.map(
    (entry) => `| ${entry.step} | ${(entry.ms / 1000).toFixed(1)}s | ${Math.round(entry.ms)}ms |`,
  );
  return [
    headerLine,
    '| step | seconds | ms |',
    '|---|---|---|',
    ...rows,
  ].join('\n');
}
