import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { ApiError } from '../../api/http'
import { useRequest } from './useRequest'

const api = vi.hoisted(() => ({ create: vi.fn(), interpret: vi.fn(), list: vi.fn() }))
vi.mock('../../api/generated/afterbooth', () => ({
  createPickupRequest: api.create,
  interpretConditions: api.interpret,
  listOrganizations: api.list,
}))

beforeEach(() => {
  api.create.mockReset().mockRejectedValue(new TypeError('Connection lost'))
  api.list.mockResolvedValue({ data: [] })
  api.interpret.mockResolvedValue({
    status: 200,
    data: {
      context: 'RECEIVER',
      transcript: '진열대 필요해요',
      conditions: { category: 'DISPLAY_RACK', vehicleType: 'SEDAN' },
      missingFields: [],
    },
  })
})
afterEach(cleanup)

async function ready() {
  const hook = renderHook(() => useRequest())
  await act(async () => {
    hook.result.current.setOrganizationId('7')
    hook.result.current.interpretText('진열대 필요해요')
  })
  return hook
}

const keys = () => api.create.mock.calls.map((call) => call[1]['Idempotency-Key'] as string)

test('reuses the key on manual retry after an uncertain network failure', async () => {
  const { result } = await ready()
  await act(() => result.current.submit())
  await act(() => result.current.submit())
  expect(keys()).toHaveLength(2)
  expect(keys()[0]).toMatch(/^[0-9a-f-]{36}$/)
  expect(keys()[1]).toBe(keys()[0])
})

test.each(['fields', 'organization', 'transcript'] as const)(
  'uses a new key when %s changes after failure',
  async (change) => {
    const { result } = await ready()
    await act(() => result.current.submit())
    await act(async () => {
      if (change === 'fields') result.current.setFields({ ...result.current.fields, quantity: 2 })
      if (change === 'organization') result.current.setOrganizationId('8')
      if (change === 'transcript') {
        api.interpret.mockResolvedValueOnce({
          status: 200,
          data: {
            context: 'RECEIVER',
            transcript: '다른 원문',
            conditions: { category: 'DISPLAY_RACK', vehicleType: 'SEDAN' },
            missingFields: [],
          },
        })
        result.current.interpretText('다른 원문')
      }
    })
    await act(() => result.current.submit())
    expect(keys()).toHaveLength(2)
    expect(keys()[1]).not.toBe(keys()[0])
  },
)

test('clears the key after a successful save', async () => {
  const { result } = await ready()
  api.create.mockResolvedValue({ status: 201, data: { id: 42 } })
  await act(() => result.current.submit())
  await act(() => result.current.submit())
  expect(keys()[1]).not.toBe(keys()[0])
})

test('keeps the key during automatic in-flight conflict retries', async () => {
  const { result } = await ready()
  api.create
    .mockRejectedValueOnce(new ApiError(409, 'IDEMPOTENCY_CONFLICT', 'Processing'))
    .mockResolvedValueOnce({ status: 201, data: { id: 42 } })
  await act(() => result.current.submit())
  expect(keys()).toHaveLength(2)
  expect(keys()[1]).toBe(keys()[0])
})

test('uses a new key on the next submit after exhausting conflict retries', async () => {
  const { result } = await ready()
  api.create.mockRejectedValue(new ApiError(409, 'IDEMPOTENCY_CONFLICT', 'Processing'))
  vi.useFakeTimers()
  try {
    await act(async () => {
      const submission = result.current.submit()
      await vi.runAllTimersAsync()
      await submission
    })
    expect(result.current.status).toBe('error')
    expect(keys()).toHaveLength(4)
    expect(new Set(keys()).size).toBe(1)

    api.create.mockResolvedValueOnce({ status: 201, data: { id: 42 } })
    await act(() => result.current.submit())
    expect(keys()).toHaveLength(5)
    expect(keys()[4]).toMatch(/^[0-9a-f-]{36}$/)
    expect(keys()[4]).not.toBe(keys()[0])
  } finally {
    vi.useRealTimers()
  }
})
