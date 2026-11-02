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
  label: '접이식 테이블',
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
  explanation: '진열대는 승용차에 실을 수 없어요.',
  reservations: [],
  rejections: [{ itemId: 3, code: 'VEHICLE_TOO_SMALL', message: '진열대는 차량 높이가 부족해요.' }],
  alternatives: [{ item, quantity: 3, reason: '접으면 승용차에 실을 수 있어요.' }],
  toolCalls: [
    {
      name: 'checkVehicleFit',
      arguments: { itemId: 3 },
      outcome: 'REJECTED',
      code: 'VEHICLE_TOO_SMALL',
      summary: '차량 크기를 확인했어요.',
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
  await waitFor(() => expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeEnabled())
  await userEvent.dblClick(screen.getByRole('button', { name: '배정 제안 받기' }))
  expect(
    await screen.findByText('Muse Spark 배정 에이전트가 재고와 차량 조건을 확인하고 있어요…'),
  ).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('보통 약 10초 걸려요')
  expect(screen.getByRole('button', { name: '배정 확인 중…' })).toBeDisabled()
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
  await waitFor(() => expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: '배정 제안 받기' }))
  expect(await screen.findByText(result.explanation)).toBeInTheDocument()
  expect(screen.getByText('진열대는 차량 높이가 부족해요.')).toBeInTheDocument()
  expect(screen.queryByText(/물품 #3/)).not.toBeInTheDocument()
  const failure = screen.getByText('조건에 맞는 물품을 확보하지 못했어요.')
  const headline = screen.getByText('대신 접이식 테이블 3개 어떠세요?')
  expect(failure.compareDocumentPosition(headline) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  await user.dblClick(screen.getByRole('button', { name: '접이식 테이블 대안 선택' }))
  expect(await screen.findByText('3개 중 3개 확보 · 배정 성공')).toBeInTheDocument()
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
            summary: '남은 물품 2종을 찾았어요.',
          },
          { ...result.toolCalls[0] },
        ],
      }),
    ),
  )
  render(<AllocationProposal request={request} />)
  await waitFor(() => expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '배정 제안 받기' }))
  const trace = (await screen.findByText('Muse Spark가 호출한 서버 도구')).closest('details')
  expect(trace).toHaveAttribute('open')
  const rows = within(trace!).getAllByRole('listitem')
  expect(rows).toHaveLength(2)
  expect(rows[0]).toHaveTextContent('searchCandidates')
  expect(rows[0]).toHaveTextContent('재고 후보 찾기')
  expect(rows[0]).toHaveTextContent('남은 물품 2종을 찾았어요.')
  expect(rows[0]).toHaveTextContent('통과')
  expect(rows[1]).toHaveTextContent('checkVehicleFit')
  expect(rows[1]).toHaveTextContent('차량에 실을 수 있는지 확인')
  expect(rows[1]).toHaveTextContent('거절')
})

test('shows partial holds and caps alternative quantity to the remaining request quantity', async () => {
  server.use(
    http.post('*/api/pickup-requests/1/allocation', () =>
      HttpResponse.json({ ...result, status: 'HELD', reservations: [reservation] }),
    ),
  )
  render(<AllocationProposal request={request} />)
  await waitFor(() => expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '배정 제안 받기' }))
  expect(await screen.findByText('3개 중 1개 확보 · 부분 배정')).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: '접이식 테이블 2개' })).toBeInTheDocument()
  expect(screen.queryByText(/어떠세요\?/)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeDisabled()
})

test('tops up the same item on the mounted page after the model fails following a partial hold', async () => {
  const pickupRequest = { ...request, quantity: 5, category: item.category }
  const originalItem = { ...item, totalQty: 10, availableQty: 10 }
  const partialHold = { ...reservation, quantity: 2 }
  const explanation =
    '요청한 5개 중 2개를 임시 확보했어요. 예약 현황을 확인하고 직접 확정해 주세요.'
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
            reason: '선택하면 서버가 차량과 픽업 조건을 확인해요.',
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
  const proposal = screen.getByRole('region', { name: '배정 제안' })
  const user = userEvent.setup()
  await waitFor(() => expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: '배정 제안 받기' }))
  expect(await screen.findByText(explanation)).toBeInTheDocument()
  expect(screen.getByText('5개 중 2개 확보 · 부분 배정')).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: '접이식 테이블 3개' })).toBeInTheDocument()
  const alternative = screen.getByRole('button', { name: '접이식 테이블 대안 선택' })
  await user.dblClick(alternative)
  expect(alternative).toBeDisabled()
  await waitFor(() => expect(payloads).toHaveLength(1))
  expect(payloads[0]).toEqual({ requestId: pickupRequest.id, itemId: originalItem.id, quantity: 3 })
  releaseHold?.()
  expect(await screen.findByText('5개 중 5개 확보 · 배정 성공')).toBeInTheDocument()
  expect(screen.getByRole('region', { name: '배정 제안' })).toBe(proposal)
  expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeDisabled()
  expect(screen.queryByRole('button', { name: '접이식 테이블 대안 선택' })).not.toBeInTheDocument()
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
  await waitFor(() => expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeEnabled())
  await user.dblClick(screen.getByRole('button', { name: '배정 제안 받기' }))
  expect(keys).toHaveLength(1)
  expect(screen.getByRole('button', { name: '배정 확인 중…' })).toBeDisabled()
  release?.()
  expect(await screen.findByRole('alert')).toHaveTextContent('같은 작업을 다시 시도')
  await user.click(screen.getByRole('button', { name: '배정 제안 받기' }))
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
        { code: 'REQUEST_ALREADY_HELD', message: '이미 확보된 물품이 있어요.' },
        { status: 409 },
      ),
    ),
  )
  render(<AllocationProposal request={request} />)
  await waitFor(() => expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '배정 제안 받기' }))
  expect(await screen.findByText('3개 중 1개 확보 · 부분 배정')).toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('이미 확보된 물품')
  expect(await screen.findByRole('button', { name: '접이식 테이블 추가 선택' })).toBeEnabled()
})

test('uses a new key for a new action after a definitive AI unavailable response', async () => {
  const keys: string[] = []
  server.use(
    http.post('*/api/pickup-requests/1/allocation', ({ request: incoming }) => {
      keys.push(incoming.headers.get('Idempotency-Key')!)
      return keys.length === 1
        ? HttpResponse.json(
            { code: 'AI_UNAVAILABLE', message: '배정을 잠시 사용할 수 없어요.' },
            { status: 503 },
          )
        : HttpResponse.json(result)
    }),
  )
  render(<AllocationProposal request={request} />)
  await waitFor(() => expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '배정 제안 받기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('배정을 잠시 사용할 수 없어요.')
  await userEvent.click(screen.getByRole('button', { name: '배정 제안 받기' }))
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
            { code: 'IDEMPOTENCY_CONFLICT', message: '같은 요청을 처리 중이에요.' },
            { status: 409 },
          )
        : HttpResponse.json(result)
    }),
  )
  render(<AllocationProposal request={request} />)
  await waitFor(() => expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '배정 제안 받기' }))
  expect(await screen.findByRole('alert', {}, { timeout: 8500 })).toHaveTextContent(
    '같은 요청을 처리 중이에요.',
  )
  expect(keys).toHaveLength(4)
  expect(new Set(keys).size).toBe(1)
  expect(timestamps[1] - timestamps[0]).toBeGreaterThanOrEqual(950)
  expect(timestamps[2] - timestamps[1]).toBeGreaterThanOrEqual(1950)
  expect(timestamps[3] - timestamps[2]).toBeGreaterThanOrEqual(3950)
  await userEvent.click(screen.getByRole('button', { name: '배정 제안 받기' }))
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
  expect(await screen.findByText('남은 수량 1개')).toBeInTheDocument()
  expect(screen.getByText(/요청 #42/)).toBeInTheDocument()
  expect(screen.getByText(/픽업 마감 11월 2일 18:00/)).toBeInTheDocument()
  expect(await screen.findByText('남은 수량 0개', {}, { timeout: 4500 })).toBeInTheDocument()
  const clearsBeforeUnmount = clearIntervalSpy.mock.calls.length
  view.unmount()
  expect(clearIntervalSpy.mock.calls.length - clearsBeforeUnmount).toBe(2)
})

test('clamps expired countdowns and counts seconds before the pickup deadline', () => {
  expect(countdown(item.pickupDeadline, Date.parse(item.pickupDeadline) - 61000)).toBe(
    '0시간 1분 1초 남음',
  )
  expect(countdown(item.pickupDeadline, Date.parse(item.pickupDeadline) + 1000)).toBe('픽업 마감')
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
  expect(await screen.findByText('3개 중 1개 확보 · 부분 배정')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeDisabled()
  expect(await screen.findByRole('heading', { name: '접이식 테이블 2개' })).toBeInTheDocument()
  expect(
    screen.getByText('선택하면 서버가 차량 크기와 픽업 마감, 남은 수량을 다시 확인해요.'),
  ).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '접이식 테이블 추가 선택' }))
  expect(await screen.findByText('3개 중 3개 확보 · 배정 성공')).toBeInTheDocument()
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
  expect(await screen.findByRole('alert')).toHaveTextContent('예약 현황을 불러오지 못했어요.')
  await screen.findByText('3개 중 1개 확보 · 부분 배정', {}, { timeout: 4500 })
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
        { code: 'AI_UNAVAILABLE', message: '배정을 잠시 사용할 수 없어요.' },
        { status: 503 },
      ),
    ),
  )
  render(<AllocationProposal request={request} />)
  await waitFor(() => expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '배정 제안 받기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('배정을 잠시 사용할 수 없어요.')
  await waitFor(() => expect(reads).toBeGreaterThanOrEqual(2), { timeout: 4500 })
  expect(screen.getByRole('alert')).toHaveTextContent('배정을 잠시 사용할 수 없어요.')
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
        { code: 'AI_UNAVAILABLE', message: 'AI 배정을 잠시 사용할 수 없어요.' },
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
  await waitFor(() => expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: '배정 제안 받기' }))
  expect(await screen.findByText('현재 재고를 확인하는 중이에요…')).toBeInTheDocument()
  expect(screen.queryByText('현재 추가로 선택할 재고가 없어요.')).not.toBeInTheDocument()
  await waitFor(() => expect(resolveStock).toBeDefined())
  resolveStock?.()
  const select = await screen.findByRole('button', { name: '접이식 테이블 추가 선택' })
  expect(screen.getByRole('heading', { name: '접이식 테이블 3개' })).toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('AI 배정을 잠시 사용할 수 없어요.')
  await user.dblClick(select)
  await waitFor(() => expect(payloads).toHaveLength(1))
  finishHold?.()
  await waitFor(() => expect(select).toBeEnabled())
  expect(screen.getByRole('alert')).toHaveTextContent('같은 작업을 다시 시도')
  await user.click(select)
  expect(await screen.findByText('3개 중 3개 확보 · 배정 성공')).toBeInTheDocument()
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
        { code: 'VEHICLE_TOO_SMALL', message: '접이식 테이블은 차량에 실을 수 없어요.' },
        { status: 422 },
      )
    }),
  )
  const view = render(<AllocationProposal request={request} />)
  await userEvent.click(await screen.findByRole('button', { name: '접이식 테이블 추가 선택' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '접이식 테이블은 차량에 실을 수 없어요.',
  )
  expect(screen.getByText('3개 중 1개 확보 · 부분 배정')).toBeInTheDocument()
  view.rerender(<AllocationProposal request={{ ...request, id: 2 }} />)
  expect(screen.queryByText('3개 중 1개 확보 · 부분 배정')).not.toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('button', { name: '배정 제안 받기' })).toBeEnabled())
  expect(screen.queryByRole('button', { name: '접이식 테이블 추가 선택' })).not.toBeInTheDocument()
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
  const confirm = await screen.findByRole('button', { name: '접이식 테이블 예약 확정하기' })
  expect(screen.getByText(/확정하면 인계 화면에서/)).toBeInTheDocument()
  await userEvent.dblClick(confirm)
  expect(await screen.findByText('접이식 테이블 1개 · 예약 확정')).toBeInTheDocument()
  expect(keys).toHaveLength(1)
  expect(keys[0]).toBeTruthy()
  expect(screen.queryByRole('button', { name: '접이식 테이블 예약 확정하기' })).toBeNull()
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
        { code: 'HOLD_EXPIRED', message: '임시 확보 시간이 지나 재고로 돌아갔어요.' },
        { status: 409 },
      )
    }),
  )
  render(<AllocationProposal request={request} />)
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: '접이식 테이블 예약 확정하기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '예약 확정 결과를 확인하지 못했어요. 다시 시도해 주세요.',
  )
  await user.click(screen.getByRole('button', { name: '접이식 테이블 예약 확정하기' }))
  expect(await screen.findByText('임시 확보 시간이 지나 재고로 돌아갔어요.')).toBeInTheDocument()
  expect(keys).toEqual([keys[0], keys[0]])
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: '접이식 테이블 예약 확정하기' })).toBeNull(),
  )
})
