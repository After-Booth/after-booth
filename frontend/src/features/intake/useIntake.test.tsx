import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import {
  confirmIntakeDraft,
  createIntakeDraft,
  getIntakeDraft,
  listEvents,
} from '../../api/generated/afterbooth'
import type { IntakeDraft } from '../../api/generated/model'
import { ApiError } from '../../api/http'
import { useIntake } from './useIntake'

vi.mock('../../api/generated/afterbooth', () => ({
  confirmIntakeDraft: vi.fn(),
  createIntakeDraft: vi.fn(),
  getIntakeDraft: vi.fn(),
  listEvents: vi.fn(),
}))

const draft: IntakeDraft = {
  id: 5,
  eventId: 7,
  status: 'READY',
  createdAt: '2026-11-01T10:00:00+09:00',
  lines: [
    {
      lineId: 1,
      label: 'Display rack',
      category: 'DISPLAY_RACK',
      count: 1,
      boxes: [],
      sizeCm: { width: 90, depth: 40, height: 180 },
      condition: 'GOOD',
      confidence: 0.9,
      needsReview: false,
    },
  ],
}
const photo = new File(['photo'], 'photo.png', { type: 'image/png' })
const conflict = () => new ApiError(409, 'IDEMPOTENCY_CONFLICT', 'Please try again shortly.')

beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  URL.createObjectURL = vi.fn(() => 'blob:photo')
  URL.revokeObjectURL = vi.fn()
  vi.mocked(listEvents).mockResolvedValue({
    data: [
      {
        id: 7,
        name: 'Demo',
        organizationId: 1,
        venue: 'Seoul',
        teardownDeadline: '2026-11-07T18:00:00+09:00',
      },
    ],
    status: 200,
    headers: new Headers(),
  })
  vi.mocked(createIntakeDraft).mockResolvedValue({
    data: draft,
    status: 202,
    headers: new Headers(),
  })
  vi.mocked(confirmIntakeDraft).mockResolvedValue({ data: [], status: 201, headers: new Headers() })
})
afterEach(() => vi.useRealTimers())

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

test.each(['create', 'confirm'] as const)(
  '%s retries conflicts after 1, 2 and 4 seconds with one key, then uses a new key on the next tap',
  async (operation) => {
    const { result } = renderHook(() => useIntake())
    if (operation === 'confirm')
      await act(async () => {
        await result.current.upload(photo)
      })
    const mutation =
      operation === 'create' ? vi.mocked(createIntakeDraft) : vi.mocked(confirmIntakeDraft)
    mutation.mockRejectedValue(conflict())
    const run = () =>
      operation === 'create' ? result.current.upload(photo) : result.current.confirm()
    await act(async () => {
      void run()
    })
    expect(mutation).toHaveBeenCalledTimes(1)
    for (const [index, delay] of [1000, 2000, 4000].entries()) {
      await advance(delay - 1)
      expect(mutation).toHaveBeenCalledTimes(index + 1)
      await advance(1)
      expect(mutation).toHaveBeenCalledTimes(index + 2)
    }
    const keyOf = (index: number) => {
      const args = mutation.mock.calls[index]
      return operation === 'create'
        ? (args as Parameters<typeof createIntakeDraft>)[1]['Idempotency-Key']
        : (args as Parameters<typeof confirmIntakeDraft>)[2]['Idempotency-Key']
    }
    expect(new Set([0, 1, 2, 3].map(keyOf)).size).toBe(1)
    expect(result.current.error).toBe('Please try again shortly.')
    expect(result.current.uploading || result.current.confirming).toBe(false)
    await act(async () => {
      void run()
    })
    expect(keyOf(4)).not.toBe(keyOf(0))
  },
)

test.each(['create', 'confirm'] as const)(
  '%s completes when the conflicting action becomes available',
  async (operation) => {
    const { result } = renderHook(() => useIntake())
    if (operation === 'confirm')
      await act(async () => {
        await result.current.upload(photo)
      })
    const mutation =
      operation === 'create' ? vi.mocked(createIntakeDraft) : vi.mocked(confirmIntakeDraft)
    mutation.mockRejectedValueOnce(conflict())
    await act(async () => {
      void (operation === 'create' ? result.current.upload(photo) : result.current.confirm())
    })
    await advance(1000)
    expect(mutation).toHaveBeenCalledTimes(2)
    expect(result.current.error).toBeUndefined()
    expect(operation === 'create' ? result.current.draft?.status : result.current.items).toEqual(
      operation === 'create' ? 'READY' : [],
    )
  },
)

test('confirm uses a new key after a server error with an unchanged request', async () => {
  vi.mocked(confirmIntakeDraft).mockRejectedValueOnce(
    new ApiError(500, 'INTERNAL_ERROR', 'Please try again shortly.'),
  )
  const { result } = renderHook(() => useIntake())
  await act(async () => {
    await result.current.upload(photo)
  })
  await act(async () => {
    await result.current.confirm()
  })
  expect(result.current.error).toBe('Please try again shortly.')
  await act(async () => {
    await result.current.confirm()
  })
  expect(confirmIntakeDraft).toHaveBeenCalledTimes(2)
  const [first, second] = vi.mocked(confirmIntakeDraft).mock.calls
  expect(second[1]).toEqual(first[1])
  expect(second[2]['Idempotency-Key']).not.toBe(first[2]['Idempotency-Key'])
  expect(result.current.error).toBeUndefined()
  expect(result.current.items).toEqual([])
})

test('does not retry a non-conflict mutation error', async () => {
  vi.mocked(createIntakeDraft).mockRejectedValue(
    new ApiError(400, 'INVALID_INPUT', 'Check the photo.'),
  )
  const { result } = renderHook(() => useIntake())
  await act(async () => {
    await result.current.upload(photo)
  })
  await advance(7000)
  expect(createIntakeDraft).toHaveBeenCalledTimes(1)
  expect(result.current.error).toBe('Check the photo.')
})

async function processingHook() {
  vi.mocked(createIntakeDraft).mockResolvedValue({
    data: { ...draft, status: 'PROCESSING', lines: [] },
    status: 202,
    headers: new Headers(),
  })
  vi.mocked(getIntakeDraft).mockResolvedValue({
    data: { ...draft, status: 'PROCESSING', lines: [] },
    status: 200,
    headers: new Headers(),
  })
  const hook = renderHook(() => useIntake())
  await act(async () => {
    await hook.result.current.upload(photo)
  })
  return hook
}

test('recovers from two transient poll errors and stops after ready', async () => {
  const { result } = await processingHook()
  vi.mocked(getIntakeDraft)
    .mockRejectedValueOnce(new TypeError('Network failure'))
    .mockRejectedValueOnce(new ApiError(503, 'INTERNAL_ERROR', 'Please try again shortly.'))
    .mockResolvedValueOnce({ data: draft, status: 200, headers: new Headers() })
  await advance(2000)
  expect(result.current.error).toBeUndefined()
  expect(result.current.draft?.status).toBe('PROCESSING')
  await advance(1000)
  expect(result.current.draft?.status).toBe('READY')
  await advance(90_000)
  expect(getIntakeDraft).toHaveBeenCalledTimes(3)
  expect(result.current.error).toBeUndefined()
})

test('stops after three consecutive poll errors with a retake message', async () => {
  const { result } = await processingHook()
  vi.mocked(getIntakeDraft).mockRejectedValue(new TypeError('Network failure'))
  await advance(3000)
  expect(result.current.draft).toBeUndefined()
  expect(result.current.error).toContain('Please retake the photo.')
  await advance(90_000)
  expect(getIntakeDraft).toHaveBeenCalledTimes(3)
})

test('stops processing after 90 seconds even if a poll never resolves', async () => {
  const { result } = await processingHook()
  vi.mocked(getIntakeDraft).mockImplementation(() => new Promise(() => {}))
  await advance(90_000)
  expect(result.current.draft).toBeUndefined()
  expect(result.current.error).toContain('Please retake the photo.')
  expect(getIntakeDraft).toHaveBeenCalledTimes(1)
})

test('does not reset the deadline when processing stages change', async () => {
  const { result } = await processingHook()
  await advance(89_000)
  expect(result.current.draft?.status).toBe('PROCESSING')
  await advance(1000)
  expect(result.current.draft).toBeUndefined()
  expect(result.current.error).toContain('Please retake the photo.')
  expect(getIntakeDraft).toHaveBeenCalledTimes(89)
})

test('cancels polling and conflict retries on unmount', async () => {
  const polling = await processingHook()
  polling.unmount()
  await advance(90_000)
  expect(getIntakeDraft).not.toHaveBeenCalled()
  vi.mocked(createIntakeDraft).mockRejectedValue(conflict())
  const retrying = renderHook(() => useIntake())
  await act(async () => {
    void retrying.result.current.upload(photo)
  })
  retrying.unmount()
  await advance(7000)
  expect(createIntakeDraft).toHaveBeenCalledTimes(2)
})
