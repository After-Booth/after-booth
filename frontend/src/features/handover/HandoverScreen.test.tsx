import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import { HandoverScreen } from './HandoverScreen'

const reservation = {
  id: 7,
  requestId: 2,
  organizationId: 1,
  itemId: 3,
  itemLabel: 'Display rack',
  quantity: 3,
  status: 'CONFIRMED',
  holdExpiresAt: '2099-11-01T10:00:00+09:00',
  pickupDeadline: '2099-11-01T23:00:00+09:00',
  createdAt: '2099-11-01T09:00:00+09:00',
}
const result = (countedQty: number, status = 'MISMATCH', acceptedQty = 3) => ({
  reservation: {
    ...reservation,
    status: status === 'COMPLETED' ? 'HANDED_OVER' : 'CONFIRMED',
    ...(status === 'COMPLETED' ? { handedOverQty: acceptedQty } : {}),
  },
  status,
  expectedQty: 3,
  countedQty,
  boxes: [],
})
const emptyStats = { totalReused: 0, reusedVolumeLiters: 0, byCategory: [], byEvent: [] }
const server = setupServer(
  http.get('*/api/reservations', () => HttpResponse.json([reservation])),
  http.get('*/api/events', () =>
    HttpResponse.json([
      {
        id: 1,
        name: '가을 행사',
        organizationId: 1,
        venue: '서울',
        teardownDeadline: reservation.pickupDeadline,
      },
    ]),
  ),
  http.get('*/api/stats/reuse', () => HttpResponse.json(emptyStats)),
)
beforeAll(() => {
  server.listen()
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = vi.fn(() => 'blob:handover')
      static revokeObjectURL = vi.fn()
    },
  )
})
afterEach(() => server.resetHandlers())
afterAll(() => {
  server.close()
  vi.unstubAllGlobals()
})

async function selectReservation() {
  const user = userEvent.setup()
  render(<HandoverScreen />)
  await screen.findByRole('option', { name: 'Reservation #7 · Display rack 3 items' })
  await user.selectOptions(screen.getByLabelText('Confirmed reservations'), '7')
  return user
}
const photo = () => new File(['photo'], 'handover.jpg', { type: 'image/jpeg' })

test('limits handover photos to JPEG and PNG and shows the recount duration while busy', async () => {
  let calls = 0
  server.use(
    http.post('*/api/reservations/7/handover', () => {
      calls++
      return HttpResponse.json(result(3, 'COMPLETED'))
    }),
  )
  await selectReservation()
  const input = screen.getByLabelText('Take or upload handover photo')
  expect(input).toHaveAttribute('accept', 'image/jpeg,image/png')
  // Checked synchronously after the change, before the upload can answer
  fireEvent.change(input, {
    target: { files: [new File(['photo'], 'handover.png', { type: 'image/png' })] },
  })
  expect(screen.getByRole('status')).toHaveTextContent(
    'Muse Spark is counting items and SAM 3.1 is checking segmentation. Please wait…',
  )
  expect(screen.getByRole('status')).toHaveTextContent('Usually about 4s')
  expect(input).toBeDisabled()
  expect(await screen.findByText('Handover complete')).toBeInTheDocument()
  expect(calls).toBe(1)
})

test('uploads a matching photo and refreshes category and event reuse totals', async () => {
  let completed = false
  server.use(
    http.post('*/api/reservations/7/handover', ({ request }) => {
      expect(request.headers.get('Idempotency-Key')).toBeTruthy()
      expect(request.headers.get('Content-Type')).toContain('multipart/form-data')
      completed = true
      return HttpResponse.json(result(3, 'COMPLETED'))
    }),
    http.get('*/api/stats/reuse', () =>
      HttpResponse.json(
        completed
          ? {
              totalReused: 3,
              reusedVolumeLiters: 1859,
              byCategory: [{ category: 'DISPLAY_RACK', label: 'Display rack', quantity: 3 }],
              byEvent: [{ eventId: 1, eventName: '가을 행사', quantity: 3 }],
            }
          : emptyStats,
      ),
    ),
  )
  const user = await selectReservation()
  await user.upload(screen.getByLabelText('Take or upload handover photo'), photo())
  expect(await screen.findByText('Handover complete')).toBeInTheDocument()
  expect(await screen.findByText('3 items reused in total')).toBeInTheDocument()
  expect(
    screen.getByText('Approximately 1.85㎥ of equipment reused instead of discarded'),
  ).toBeInTheDocument()
  expect(screen.getByText('(Estimated from registered dimensions)')).toBeInTheDocument()
  expect(screen.getByText('가을 행사 · 3 items')).toBeInTheDocument()
})

test.each([0, 3])(
  'allows manual partial resolution after a completed mismatch count of %i',
  async (count) => {
    let resolutions = 0
    server.use(
      http.post('*/api/reservations/7/handover', () => HttpResponse.json(result(count))),
      http.post('*/api/reservations/7/handover/resolve', async ({ request }) => {
        resolutions++
        expect(await request.json()).toEqual({ acceptedQty: 2 })
        return HttpResponse.json(result(count, 'COMPLETED', 2))
      }),
    )
    const user = await selectReservation()
    await user.upload(screen.getByLabelText('Take or upload handover photo'), photo())
    expect(
      await screen.findByText(`Reserved 3 items · Counted in photo ${count} items`),
    ).toBeInTheDocument()
    const input = screen.getByLabelText('Actual handed-over quantity')
    for (const value of ['0', '-1', '4']) {
      await user.clear(input)
      await user.type(input, value)
      expect(
        screen.getByRole('button', { name: 'Complete handover with this quantity' }),
      ).toBeDisabled()
    }
    await user.clear(input)
    await user.type(input, '2')
    await user.dblClick(
      screen.getByRole('button', { name: 'Complete handover with this quantity' }),
    )
    expect(await screen.findByText('Display rack 2 items will be reused.')).toBeInTheDocument()
    expect(resolutions).toBe(1)
  },
)

test('retakes the same file as a new action and retries an uncertain network failure with the same key', async () => {
  const keys: string[] = []
  server.use(
    http.post('*/api/reservations/7/handover', ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key')!)
      if (keys.length === 1) return HttpResponse.error()
      return HttpResponse.json(result(0))
    }),
  )
  const user = await selectReservation()
  const file = photo()
  await user.upload(screen.getByLabelText('Take or upload handover photo'), file)
  expect(await screen.findByRole('alert')).toHaveTextContent('Please try again.')
  expect(screen.queryByLabelText('Actual handed-over quantity')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Try again' }))
  await screen.findByText('Check the quantity')
  await user.upload(screen.getByLabelText('Retake handover photo'), file)
  await waitFor(() => expect(keys).toHaveLength(3))
  expect(keys[0]).toBe(keys[1])
  expect(keys[2]).not.toBe(keys[0])
})

test('uses a new action key after a completed AI failure', async () => {
  const keys: string[] = []
  server.use(
    http.post('*/api/reservations/7/handover', ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key')!)
      return keys.length === 1
        ? HttpResponse.json(
            { code: 'AI_UNAVAILABLE', message: 'Unable to check the photo.' },
            { status: 503 },
          )
        : HttpResponse.json(result(0))
    }),
  )
  const user = await selectReservation()
  await user.upload(screen.getByLabelText('Take or upload handover photo'), photo())
  expect(await screen.findByRole('alert')).toHaveTextContent('Unable to check the photo.')
  await user.click(screen.getByRole('button', { name: 'Try again' }))
  await screen.findByText('Check the quantity')
  expect(keys[1]).not.toBe(keys[0])
})

test.each([502, 504, 500])(
  'replays committed success after an ambiguous HTTP %i response',
  async (status) => {
    const storedResponses = new Map<string, ReturnType<typeof result>>()
    const keys: string[] = []
    let commits = 0
    server.use(
      http.post('*/api/reservations/7/handover', ({ request }) => {
        const key = request.headers.get('Idempotency-Key')!
        keys.push(key)
        const stored = storedResponses.get(key)
        if (stored) return HttpResponse.json(stored)
        commits++
        storedResponses.set(key, result(3, 'COMPLETED'))
        return status === 500
          ? HttpResponse.json(
              { code: 'INTERNAL_ERROR', message: 'Unable to check the request.' },
              { status },
            )
          : new HttpResponse('Gateway error', { status })
      }),
      http.get('*/api/stats/reuse', () =>
        HttpResponse.json({ ...emptyStats, totalReused: commits * 3 }),
      ),
    )
    const user = await selectReservation()
    await user.upload(screen.getByLabelText('Take or upload handover photo'), photo())
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Handover complete')).toBeInTheDocument()
    expect(await screen.findByText('3 items reused in total')).toBeInTheDocument()
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
    expect(commits).toBe(1)
  },
)

test('retries an in-flight idempotency conflict with the same key', async () => {
  const keys: string[] = []
  server.use(
    http.post('*/api/reservations/7/handover', ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key')!)
      return keys.length === 1
        ? HttpResponse.json(
            { code: 'IDEMPOTENCY_CONFLICT', message: 'Processing.' },
            { status: 409 },
          )
        : HttpResponse.json(result(0))
    }),
  )
  const user = await selectReservation()
  await user.upload(screen.getByLabelText('Take or upload handover photo'), photo())
  expect(screen.getByLabelText('Confirmed reservations')).toBeDisabled()
  await screen.findByText('Check the quantity', {}, { timeout: 2500 })
  expect(keys).toHaveLength(2)
  expect(keys[1]).toBe(keys[0])
})

test('directs a provider to receiver cancellation without a forbidden mutation', async () => {
  let cancelled = 0
  server.use(
    http.post('*/api/reservations/7/handover', () => HttpResponse.json(result(0))),
    http.post('*/api/reservations/7/cancel', () => {
      cancelled++
      return HttpResponse.json({ ...reservation, status: 'RELEASED' })
    }),
  )
  const user = await selectReservation()
  await user.upload(screen.getByLabelText('Take or upload handover photo'), photo())
  expect(await screen.findByText(/ask the receiver to cancel/)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /Cancel reservation/ })).not.toBeInTheDocument()
  expect(cancelled).toBe(0)
})

test('filters statistics by event and displays an empty result', async () => {
  const filters: (string | null)[] = []
  server.use(
    http.get('*/api/stats/reuse', ({ request }) => {
      filters.push(new URL(request.url).searchParams.get('eventId'))
      return HttpResponse.json(emptyStats)
    }),
  )
  const user = await selectReservation()
  await user.selectOptions(screen.getByLabelText('Filter by event'), '1')
  await waitFor(() => expect(filters).toContain('1'))
  expect(await screen.findByText('0 items reused in total')).toBeInTheDocument()
  expect(screen.getByText('No completed handovers yet.')).toBeInTheDocument()
  expect(screen.queryByText(/Approximately/)).not.toBeInTheDocument()
})
