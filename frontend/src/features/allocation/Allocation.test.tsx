import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import type { AllocationResult, Item, PickupRequest, Reservation } from '../../api/generated/model'
import { AllocationProposal } from './AllocationProposal'
import { AllocationBoard } from './AllocationBoard'
import { countdown } from './countdown'

const request: PickupRequest = {
  id: 1,
  organizationId: 2,
  category: 'DISPLAY_RACK',
  quantity: 3,
  vehicleType: 'SEDAN',
  createdAt: '2026-11-01T10:00:00+09:00',
}
const item: Item = {
  id: 7,
  eventId: 1,
  organizationId: 1,
  category: 'FOLDING_TABLE',
  label: 'Folding table',
  sizeCm: { width: 60, depth: 40, height: 10 },
  condition: 'GOOD',
  totalQty: 3,
  availableQty: 3,
  pickupDeadline: '2026-11-02T18:00:00+09:00',
}
const reservation: Reservation = {
  id: 9,
  requestId: 1,
  organizationId: 2,
  itemId: 7,
  itemLabel: item.label,
  quantity: 1,
  status: 'HELD',
  holdExpiresAt: '2026-11-02T17:00:00+09:00',
  pickupDeadline: item.pickupDeadline,
  createdAt: request.createdAt,
}
const result: AllocationResult = {
  requestId: 1,
  status: 'NO_MATCH',
  explanation: 'Display racks do not fit in a sedan.',
  reservations: [],
  rejections: [
    {
      itemId: 3,
      code: 'VEHICLE_TOO_SMALL',
      message: 'Insufficient vehicle height for display racks.',
    },
  ],
  alternatives: [{ item, quantity: 3, reason: 'Fits in a sedan when folded.' }],
  toolCalls: [
    {
      name: 'checkVehicleFit',
      arguments: { itemId: 3 },
      outcome: 'REJECTED',
      code: 'VEHICLE_TOO_SMALL',
      summary: 'Checked vehicle dimensions.',
    },
  ],
}
const server = setupServer(
  http.get('*/api/reservations', () => HttpResponse.json([])),
  http.get('*/api/items', () => HttpResponse.json([])),
)
beforeAll(() => server.listen())
afterEach(() => {
  cleanup()
  server.resetHandlers()
  vi.useRealTimers()
  vi.restoreAllMocks()
})
afterAll(() => server.close())

test('shows measured AI progress and prevents duplicate allocation while pending', async () => {
  let calls = 0
  let complete: (() => void) | undefined
  server.use(
    http.post('*/api/pickup-requests/1/allocation', async () => {
      calls++
      await new Promise<void>((resolve) => {
        complete = resolve
      })
      return HttpResponse.json(result)
    }),
  )
  render(<AllocationProposal request={request} />)
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeEnabled(),
  )
  await userEvent.dblClick(screen.getByRole('button', { name: 'Get allocation proposal' }))
  expect(
    await screen.findByText('Muse Spark is checking inventory and vehicle constraints…'),
  ).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('Usually about 10s')
  expect(screen.getByRole('button', { name: 'Checking allocation…' })).toBeDisabled()
  await waitFor(() => expect(calls).toBe(1))
  complete?.()
  expect(await screen.findByText(result.explanation)).toBeInTheDocument()
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
})

test('shows no match reasons and holds a human-selected alternative with one key', async () => {
  let holds = 0
  let sent: unknown
  server.use(
    http.post('*/api/pickup-requests/1/allocation', () => HttpResponse.json(result)),
    http.post('*/api/reservations', async ({ request: incoming }) => {
      holds++
      sent = await incoming.json()
      expect(incoming.headers.get('Idempotency-Key')).toMatch(/^[a-f0-9-]{36}$/)
      return HttpResponse.json({ ...reservation, quantity: 3 }, { status: 201 })
    }),
  )
  render(<AllocationProposal request={request} />)
  const user = userEvent.setup()
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeEnabled(),
  )
  await user.click(screen.getByRole('button', { name: 'Get allocation proposal' }))
  expect(await screen.findByText(result.explanation)).toBeInTheDocument()
  expect(screen.getByText('Insufficient vehicle height for display racks.')).toBeInTheDocument()
  expect(screen.queryByText(/Item #3/)).not.toBeInTheDocument()
  const failure = screen.getByText('No matching items could be held.')
  const headline = screen.getByText('Alternative: Folding table 3 items available')
  expect(failure.compareDocumentPosition(headline) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  await user.dblClick(screen.getByRole('button', { name: 'Folding table Choose alternative' }))
  expect(await screen.findByText('3 of 3 items held · Allocation successful')).toBeInTheDocument()
  expect(holds).toBe(1)
  expect(sent).toEqual({ requestId: 1, itemId: 7, quantity: 3 })
})

test('opens the server tool trace by default with each tool name, Korean gloss and outcome', async () => {
  server.use(
    http.post('*/api/pickup-requests/1/allocation', () =>
      HttpResponse.json({
        ...result,
        toolCalls: [
          {
            name: 'searchCandidates',
            arguments: {},
            outcome: 'OK',
            summary: 'Found two available item categories.',
          },
          { ...result.toolCalls[0] },
        ],
      }),
    ),
  )
  render(<AllocationProposal request={request} />)
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeEnabled(),
  )
  await userEvent.click(screen.getByRole('button', { name: 'Get allocation proposal' }))
  const trace = (await screen.findByText('Server tools called by Muse Spark')).closest('details')
  expect(trace).toHaveAttribute('open')
  const rows = within(trace!).getAllByRole('listitem')
  expect(rows).toHaveLength(2)
  expect(rows[0]).toHaveTextContent('searchCandidates')
  expect(rows[0]).toHaveTextContent('Search inventory candidates')
  expect(rows[0]).toHaveTextContent('Found two available item categories.')
  expect(rows[0]).toHaveTextContent('Passed')
  expect(rows[1]).toHaveTextContent('checkVehicleFit')
  expect(rows[1]).toHaveTextContent('Check vehicle fit')
  expect(rows[1]).toHaveTextContent('Rejected')
})

test('shows partial holds and caps alternative quantity to the remaining request quantity', async () => {
  server.use(
    http.post('*/api/pickup-requests/1/allocation', () =>
      HttpResponse.json({ ...result, status: 'HELD', reservations: [reservation] }),
    ),
  )
  render(<AllocationProposal request={request} />)
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeEnabled(),
  )
  await userEvent.click(screen.getByRole('button', { name: 'Get allocation proposal' }))
  expect(await screen.findByText('1 of 3 items held · Partial allocation')).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Folding table 2 items' })).toBeInTheDocument()
  expect(screen.queryByText(/Alternative\?/)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeDisabled()
})

test('tops up the same item on the mounted page after the model fails following a partial hold', async () => {
  const pickupRequest = { ...request, quantity: 5, category: item.category }
  const originalItem = { ...item, totalQty: 10, availableQty: 10 }
  const partialHold = { ...reservation, quantity: 2 }
  const explanation = 'Held 2 of 5 requested items. Check your reservations and confirm them.'
  let allocations = 0
  const payloads: unknown[] = []
  let releaseHold: (() => void) | undefined
  const locationBefore = window.location.href
  const pushState = vi.spyOn(window.history, 'pushState')
  const replaceState = vi.spyOn(window.history, 'replaceState')
  server.use(
    http.get('*/api/items', () => HttpResponse.json([originalItem])),
    http.post('*/api/pickup-requests/1/allocation', () => {
      allocations++
      return HttpResponse.json({
        ...result,
        status: 'HELD',
        explanation,
        reservations: [partialHold],
        rejections: [],
        alternatives: [
          {
            item: { ...originalItem, availableQty: 8 },
            quantity: 3,
            reason: 'The server checks vehicle and pickup constraints when selected.',
          },
        ],
      } satisfies AllocationResult)
    }),
    http.post('*/api/reservations', async ({ request: incoming }) => {
      payloads.push(await incoming.json())
      expect(incoming.headers.get('Idempotency-Key')).toMatch(/^[a-f0-9-]{36}$/)
      await new Promise<void>((resolve) => {
        releaseHold = resolve
      })
      return HttpResponse.json({ ...reservation, id: 10, quantity: 3 }, { status: 201 })
    }),
  )
  render(<AllocationProposal request={pickupRequest} />)
  const proposal = screen.getByRole('region', { name: 'Allocation proposal' })
  const user = userEvent.setup()
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeEnabled(),
  )
  await user.click(screen.getByRole('button', { name: 'Get allocation proposal' }))
  expect(await screen.findByText(explanation)).toBeInTheDocument()
  expect(screen.getByText('2 of 5 items held · Partial allocation')).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Folding table 3 items' })).toBeInTheDocument()
  const alternative = screen.getByRole('button', { name: 'Folding table Choose alternative' })
  await user.dblClick(alternative)
  expect(alternative).toBeDisabled()
  await waitFor(() => expect(payloads).toHaveLength(1))
  expect(payloads[0]).toEqual({ requestId: pickupRequest.id, itemId: originalItem.id, quantity: 3 })
  releaseHold?.()
  expect(await screen.findByText('5 of 5 items held · Allocation successful')).toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Allocation proposal' })).toBe(proposal)
  expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeDisabled()
  expect(
    screen.queryByRole('button', { name: 'Folding table Choose alternative' }),
  ).not.toBeInTheDocument()
  expect(allocations).toBe(1)
  expect(payloads).toHaveLength(1)
  expect(window.location.href).toBe(locationBefore)
  expect(pushState).not.toHaveBeenCalled()
  expect(replaceState).not.toHaveBeenCalled()
})

test('retains the key after an uncertain network failure and blocks duplicate allocation clicks', async () => {
  const keys: string[] = []
  let release: (() => void) | undefined
  server.use(
    http.post('*/api/pickup-requests/1/allocation', async ({ request: incoming }) => {
      keys.push(incoming.headers.get('Idempotency-Key')!)
      if (keys.length === 1) {
        await new Promise<void>((resolve) => {
          release = resolve
        })
        return HttpResponse.error()
      }
      return HttpResponse.json(result)
    }),
  )
  render(<AllocationProposal request={request} />)
  const user = userEvent.setup()
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeEnabled(),
  )
  await user.dblClick(screen.getByRole('button', { name: 'Get allocation proposal' }))
  expect(keys).toHaveLength(1)
  expect(screen.getByRole('button', { name: 'Checking allocation…' })).toBeDisabled()
  release?.()
  expect(await screen.findByRole('alert')).toHaveTextContent('Retry the same action.')
  await user.click(screen.getByRole('button', { name: 'Get allocation proposal' }))
  await screen.findByText(result.explanation)
  expect(keys).toHaveLength(2)
  expect(keys[1]).toBe(keys[0])
})

test('recovers an existing hold after an already-held response without another allocation', async () => {
  let reads = 0
  server.use(
    http.get('*/api/reservations', () => HttpResponse.json(++reads === 1 ? [] : [reservation])),
    http.get('*/api/items', () => HttpResponse.json([item])),
    http.post('*/api/pickup-requests/1/allocation', () =>
      HttpResponse.json(
        { code: 'REQUEST_ALREADY_HELD', message: 'Items are already held.' },
        { status: 409 },
      ),
    ),
  )
  render(<AllocationProposal request={request} />)
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeEnabled(),
  )
  await userEvent.click(screen.getByRole('button', { name: 'Get allocation proposal' }))
  expect(await screen.findByText('1 of 3 items held · Partial allocation')).toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('Items are already held')
  expect(
    await screen.findByRole('button', { name: 'Folding table Choose additional stock' }),
  ).toBeEnabled()
})

test('uses a new key for a new action after a definitive AI unavailable response', async () => {
  const keys: string[] = []
  server.use(
    http.post('*/api/pickup-requests/1/allocation', ({ request: incoming }) => {
      keys.push(incoming.headers.get('Idempotency-Key')!)
      return keys.length === 1
        ? HttpResponse.json(
            { code: 'AI_UNAVAILABLE', message: 'Allocation is temporarily unavailable.' },
            { status: 503 },
          )
        : HttpResponse.json(result)
    }),
  )
  render(<AllocationProposal request={request} />)
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeEnabled(),
  )
  await userEvent.click(screen.getByRole('button', { name: 'Get allocation proposal' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Allocation is temporarily unavailable.',
  )
  await userEvent.click(screen.getByRole('button', { name: 'Get allocation proposal' }))
  await screen.findByText(result.explanation)
  expect(keys[1]).not.toBe(keys[0])
})

test('retries conflicts after one, two and four seconds with the same key then starts a new action', async () => {
  const keys: string[] = []
  const timestamps: number[] = []
  server.use(
    http.post('*/api/pickup-requests/1/allocation', ({ request: incoming }) => {
      keys.push(incoming.headers.get('Idempotency-Key')!)
      timestamps.push(Date.now())
      return keys.length <= 4
        ? HttpResponse.json(
            { code: 'IDEMPOTENCY_CONFLICT', message: 'The same request is being processed.' },
            { status: 409 },
          )
        : HttpResponse.json(result)
    }),
  )
  render(<AllocationProposal request={request} />)
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeEnabled(),
  )
  await userEvent.click(screen.getByRole('button', { name: 'Get allocation proposal' }))
  expect(await screen.findByRole('alert', {}, { timeout: 8500 })).toHaveTextContent(
    'The same request is being processed.',
  )
  expect(keys).toHaveLength(4)
  expect(new Set(keys).size).toBe(1)
  expect(timestamps[1] - timestamps[0]).toBeGreaterThanOrEqual(950)
  expect(timestamps[2] - timestamps[1]).toBeGreaterThanOrEqual(1950)
  expect(timestamps[3] - timestamps[2]).toBeGreaterThanOrEqual(3950)
  await userEvent.click(screen.getByRole('button', { name: 'Get allocation proposal' }))
  await screen.findByText(result.explanation)
  expect(keys[4]).not.toBe(keys[0])
}, 10000)

test('polls stock and concurrent reservation results, displays Seoul time and cleans up timers', async () => {
  let reads = 0
  const clearIntervalSpy = vi.spyOn(globalThis, 'clearInterval')
  server.use(
    http.get('*/api/items', () =>
      HttpResponse.json([{ ...item, availableQty: ++reads === 1 ? 1 : 0 }]),
    ),
    http.get('*/api/reservations', () => HttpResponse.json([{ ...reservation, requestId: 42 }])),
  )
  const view = render(<AllocationBoard />)
  expect(await screen.findByText('Available quantity 1 items')).toBeInTheDocument()
  expect(screen.getByText(/Request #42/)).toBeInTheDocument()
  expect(screen.getByText(/Pickup deadline November 2 at 18:00/)).toBeInTheDocument()
  expect(
    await screen.findByText('Available quantity 0 items', {}, { timeout: 4500 }),
  ).toBeInTheDocument()
  const clearsBeforeUnmount = clearIntervalSpy.mock.calls.length
  view.unmount()
  expect(clearIntervalSpy.mock.calls.length - clearsBeforeUnmount).toBe(2)
})

test('clamps expired countdowns and counts seconds before the pickup deadline', () => {
  expect(countdown(item.pickupDeadline, Date.parse(item.pickupDeadline) - 61000)).toBe(
    '0h 1m 1s remaining',
  )
  expect(countdown(item.pickupDeadline, Date.parse(item.pickupDeadline) + 1000)).toBe(
    'Pickup deadline',
  )
})

test('recovers a partial hold after reload and tops up from current stock with the remaining quantity', async () => {
  let payload: unknown
  server.use(
    http.get('*/api/reservations', () => HttpResponse.json([reservation])),
    http.get('*/api/items', ({ request: incoming }) => {
      expect(new URL(incoming.url).searchParams.get('availableOnly')).toBe('true')
      return HttpResponse.json([item])
    }),
    http.post('*/api/reservations', async ({ request: incoming }) => {
      payload = await incoming.json()
      return HttpResponse.json({ ...reservation, id: 10, quantity: 2 }, { status: 201 })
    }),
  )
  render(<AllocationProposal request={request} />)
  expect(await screen.findByText('1 of 3 items held · Partial allocation')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeDisabled()
  expect(await screen.findByRole('heading', { name: 'Folding table 2 items' })).toBeInTheDocument()
  expect(
    screen.getByText(
      'The server rechecks vehicle size, pickup deadline and available quantity when you choose.',
    ),
  ).toBeInTheDocument()
  await userEvent.click(
    screen.getByRole('button', { name: 'Folding table Choose additional stock' }),
  )
  expect(await screen.findByText('3 of 3 items held · Allocation successful')).toBeInTheDocument()
  expect(payload).toEqual({ requestId: 1, itemId: 7, quantity: 2 })
})

test('clears a transient reservation polling error when the next refresh succeeds', async () => {
  let reads = 0
  server.use(
    http.get('*/api/reservations', () =>
      ++reads === 1 ? HttpResponse.error() : HttpResponse.json([reservation]),
    ),
  )
  render(<AllocationProposal request={request} />)
  expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load reservations.')
  await screen.findByText('1 of 3 items held · Partial allocation', {}, { timeout: 4500 })
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test('keeps an actionable allocation error visible across successful reservation refreshes', async () => {
  let reads = 0
  server.use(
    http.get('*/api/reservations', () => {
      reads++
      return HttpResponse.json([])
    }),
    http.post('*/api/pickup-requests/1/allocation', () =>
      HttpResponse.json(
        { code: 'AI_UNAVAILABLE', message: 'Allocation is temporarily unavailable.' },
        { status: 503 },
      ),
    ),
  )
  render(<AllocationProposal request={request} />)
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeEnabled(),
  )
  await userEvent.click(screen.getByRole('button', { name: 'Get allocation proposal' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Allocation is temporarily unavailable.',
  )
  await waitFor(() => expect(reads).toBeGreaterThanOrEqual(2), { timeout: 4500 })
  expect(screen.getByRole('alert')).toHaveTextContent('Allocation is temporarily unavailable.')
})

test('offers current stock after AI unavailable and manually holds the full request without duplicate writes', async () => {
  let resolveStock: (() => void) | undefined
  let finishHold: (() => void) | undefined
  const payloads: unknown[] = []
  const keys: string[] = []
  const clear = vi.spyOn(globalThis, 'clearInterval')
  server.use(
    http.post('*/api/pickup-requests/1/allocation', () =>
      HttpResponse.json(
        { code: 'AI_UNAVAILABLE', message: 'AI allocation is temporarily unavailable.' },
        { status: 503 },
      ),
    ),
    http.get('*/api/items', async ({ request: incoming }) => {
      expect(new URL(incoming.url).searchParams.get('availableOnly')).toBe('true')
      await new Promise<void>((resolve) => {
        resolveStock = resolve
      })
      return HttpResponse.json([{ ...item, availableQty: 10 }])
    }),
    http.post('*/api/reservations', async ({ request: incoming }) => {
      keys.push(incoming.headers.get('Idempotency-Key')!)
      payloads.push(await incoming.json())
      if (keys.length === 1) {
        await new Promise<void>((resolve) => {
          finishHold = resolve
        })
        return HttpResponse.error()
      }
      return HttpResponse.json({ ...reservation, quantity: 3 }, { status: 201 })
    }),
  )
  const view = render(<AllocationProposal request={request} />)
  const user = userEvent.setup()
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeEnabled(),
  )
  await user.click(screen.getByRole('button', { name: 'Get allocation proposal' }))
  expect(await screen.findByText('Checking current stock…')).toBeInTheDocument()
  expect(screen.queryByText('No additional stock available now.')).not.toBeInTheDocument()
  await waitFor(() => expect(resolveStock).toBeDefined())
  resolveStock?.()
  const select = await screen.findByRole('button', {
    name: 'Folding table Choose additional stock',
  })
  expect(screen.getByRole('heading', { name: 'Folding table 3 items' })).toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('AI allocation is temporarily unavailable.')
  await user.dblClick(select)
  await waitFor(() => expect(payloads).toHaveLength(1))
  finishHold?.()
  await waitFor(() => expect(select).toBeEnabled())
  expect(screen.getByRole('alert')).toHaveTextContent('Retry the same action.')
  await user.click(select)
  expect(await screen.findByText('3 of 3 items held · Allocation successful')).toBeInTheDocument()
  expect(payloads).toEqual([
    { requestId: 1, itemId: 7, quantity: 3 },
    { requestId: 1, itemId: 7, quantity: 3 },
  ])
  expect(keys[0]).toBe(keys[1])
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.queryByText(result.explanation)).not.toBeInTheDocument()
  expect(clear.mock.calls.length).toBeGreaterThanOrEqual(1)
  const beforeUnmount = clear.mock.calls.length
  view.unmount()
  expect(clear.mock.calls.length).toBe(beforeUnmount + 1)
})

test('shows Korean server rejection for manual stock and resets recovery when the request changes', async () => {
  server.use(
    http.get('*/api/items', () => HttpResponse.json([{ ...item, availableQty: 20 }])),
    http.get('*/api/reservations', ({ request: incoming }) =>
      HttpResponse.json(
        new URL(incoming.url).searchParams.get('requestId') === '1' ? [reservation] : [],
      ),
    ),
    http.post('*/api/reservations', async ({ request: incoming }) => {
      expect(await incoming.json()).toEqual({ requestId: 1, itemId: 7, quantity: 2 })
      return HttpResponse.json(
        { code: 'VEHICLE_TOO_SMALL', message: 'Folding tables do not fit in this vehicle.' },
        { status: 422 },
      )
    }),
  )
  const view = render(<AllocationProposal request={request} />)
  await userEvent.click(
    await screen.findByRole('button', { name: 'Folding table Choose additional stock' }),
  )
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Folding tables do not fit in this vehicle.',
  )
  expect(screen.getByText('1 of 3 items held · Partial allocation')).toBeInTheDocument()
  view.rerender(<AllocationProposal request={{ ...request, id: 2 }} />)
  expect(screen.queryByText('1 of 3 items held · Partial allocation')).not.toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Get allocation proposal' })).toBeEnabled(),
  )
  expect(
    screen.queryByRole('button', { name: 'Folding table Choose additional stock' }),
  ).not.toBeInTheDocument()
})

test('confirms a held reservation with one key so it can be handed over', async () => {
  const keys: (string | null)[] = []
  let confirmed = false
  server.use(
    http.get('*/api/reservations', () =>
      HttpResponse.json([{ ...reservation, status: confirmed ? 'CONFIRMED' : 'HELD' }]),
    ),
    http.post('*/api/reservations/9/confirm', ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key'))
      confirmed = true
      return HttpResponse.json({ ...reservation, status: 'CONFIRMED' })
    }),
  )
  render(<AllocationProposal request={request} />)
  const confirm = await screen.findByRole('button', { name: 'Folding table Confirm reservation' })
  expect(screen.getByText(/Confirm your reservation/)).toBeInTheDocument()
  await userEvent.dblClick(confirm)
  expect(await screen.findByText('Folding table 1 items · Confirmed')).toBeInTheDocument()
  expect(keys).toHaveLength(1)
  expect(keys[0]).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Folding table Confirm reservation' })).toBeNull()
})

test('keeps the confirm key after an unknown outcome and refreshes an expired hold', async () => {
  const keys: (string | null)[] = []
  let expired = false
  server.use(
    http.get('*/api/reservations', () =>
      HttpResponse.json([{ ...reservation, status: expired ? 'EXPIRED' : 'HELD' }]),
    ),
    http.post('*/api/reservations/9/confirm', ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key'))
      if (keys.length === 1) return HttpResponse.error()
      expired = true
      return HttpResponse.json(
        { code: 'HOLD_EXPIRED', message: 'The hold expired and items returned to stock.' },
        { status: 409 },
      )
    }),
  )
  render(<AllocationProposal request={request} />)
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Folding table Confirm reservation' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Unable to check the reservation update. Please try again.',
  )
  await user.click(screen.getByRole('button', { name: 'Folding table Confirm reservation' }))
  expect(
    await screen.findByText('The hold expired and items returned to stock.'),
  ).toBeInTheDocument()
  expect(keys).toEqual([keys[0], keys[0]])
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: 'Folding table Confirm reservation' })).toBeNull(),
  )
})

test('lets the receiver cancel a confirmed reservation with a stable retry key', async () => {
  const keys: string[] = []
  server.use(
    http.get('*/api/reservations', () =>
      HttpResponse.json([{ ...reservation, status: 'CONFIRMED' }]),
    ),
    http.post('*/api/reservations/9/cancel', ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key')!)
      return keys.length === 1
        ? HttpResponse.error()
        : HttpResponse.json({ ...reservation, status: 'RELEASED' })
    }),
  )
  render(<AllocationProposal request={request} />)
  const cancel = await screen.findByRole('button', { name: 'Folding table Cancel reservation' })
  await userEvent.click(cancel)
  await screen.findByRole('alert')
  await userEvent.click(cancel)
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: 'Folding table Cancel reservation' }),
    ).not.toBeInTheDocument(),
  )
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBe(keys[1])
})

test('offers the receiver a route from the board to their reservation management', async () => {
  server.use(
    http.get('*/api/reservations', () =>
      HttpResponse.json([reservation, { ...reservation, id: 10, organizationId: 99 }]),
    ),
  )
  render(<AllocationBoard organizationId={2} />)
  const links = await screen.findAllByRole('link', { name: 'Manage my reservation' })
  expect(links).toHaveLength(1)
  expect(links[0]).toHaveAttribute('href', '#/allocation?requestId=1')
})
