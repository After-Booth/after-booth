import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, expect, test } from 'vitest'
import { HandoverScreen } from './HandoverScreen'

const reservation = {
  id: 7,
  requestId: 2,
  organizationId: 1,
  itemId: 3,
  itemLabel: '진열대',
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
const emptyStats = { totalReused: 0, byCategory: [], byEvent: [] }
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
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

async function selectReservation() {
  const user = userEvent.setup()
  render(<HandoverScreen />)
  await screen.findByRole('option', { name: '예약 #7 · 진열대 3개' })
  await user.selectOptions(screen.getByLabelText('확정된 예약'), '7')
  return user
}
const photo = () => new File(['photo'], 'handover.jpg', { type: 'image/jpeg' })

test('limits handover photos to JPEG and PNG and shows the recount duration while busy', async () => {
  let calls = 0
  let complete: (() => void) | undefined
  server.use(
    http.post('*/api/reservations/7/handover', async () => {
      calls++
      await new Promise<void>((resolve) => {
        complete = resolve
      })
      return HttpResponse.json(result(3, 'COMPLETED'))
    }),
  )
  const user = await selectReservation()
  const input = screen.getByLabelText('인계 사진 촬영 또는 업로드')
  expect(input).toHaveAttribute('accept', 'image/jpeg,image/png')
  await user.upload(input, new File(['photo'], 'handover.webp', { type: 'image/webp' }))
  expect(calls).toBe(0)
  await user.upload(input, new File(['photo'], 'handover.png', { type: 'image/png' }))
  expect(
    await screen.findByText('물품 수량과 분할 결과를 확인하고 있어요. 잠시 기다려 주세요…'),
  ).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('보통 약 6초 걸려요')
  expect(input).toBeDisabled()
  await waitFor(() => expect(calls).toBe(1))
  complete?.()
  expect(await screen.findByText('인계를 완료했어요')).toBeInTheDocument()
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
              byCategory: [{ category: 'DISPLAY_RACK', label: '진열대', quantity: 3 }],
              byEvent: [{ eventId: 1, eventName: '가을 행사', quantity: 3 }],
            }
          : emptyStats,
      ),
    ),
  )
  const user = await selectReservation()
  await user.upload(screen.getByLabelText('인계 사진 촬영 또는 업로드'), photo())
  expect(await screen.findByText('인계를 완료했어요')).toBeInTheDocument()
  expect(await screen.findByText('총 3개 재사용')).toBeInTheDocument()
  expect(screen.getByText('가을 행사 · 3개')).toBeInTheDocument()
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
    await user.upload(screen.getByLabelText('인계 사진 촬영 또는 업로드'), photo())
    expect(await screen.findByText(`예약 3개 · 사진에서 센 수량 ${count}개`)).toBeInTheDocument()
    const input = screen.getByLabelText('실제 받은 수량')
    for (const value of ['0', '-1', '4']) {
      await user.clear(input)
      await user.type(input, value)
      expect(screen.getByRole('button', { name: '이 수량으로 인계 완료' })).toBeDisabled()
    }
    await user.clear(input)
    await user.type(input, '2')
    await user.dblClick(screen.getByRole('button', { name: '이 수량으로 인계 완료' }))
    expect(await screen.findByText('진열대 2개가 다시 사용돼요.')).toBeInTheDocument()
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
  await user.upload(screen.getByLabelText('인계 사진 촬영 또는 업로드'), file)
  expect(await screen.findByRole('alert')).toHaveTextContent('다시 시도해 주세요.')
  expect(screen.queryByLabelText('실제 받은 수량')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '다시 시도' }))
  await screen.findByText('수량 확인이 필요해요')
  await user.upload(screen.getByLabelText('인계 사진 다시 촬영'), file)
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
            { code: 'AI_UNAVAILABLE', message: '사진을 확인하지 못했어요.' },
            { status: 503 },
          )
        : HttpResponse.json(result(0))
    }),
  )
  const user = await selectReservation()
  await user.upload(screen.getByLabelText('인계 사진 촬영 또는 업로드'), photo())
  expect(await screen.findByRole('alert')).toHaveTextContent('사진을 확인하지 못했어요.')
  await user.click(screen.getByRole('button', { name: '다시 시도' }))
  await screen.findByText('수량 확인이 필요해요')
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
              { code: 'INTERNAL_ERROR', message: '요청을 확인하지 못했어요.' },
              { status },
            )
          : new HttpResponse('Gateway error', { status })
      }),
      http.get('*/api/stats/reuse', () =>
        HttpResponse.json({ ...emptyStats, totalReused: commits * 3 }),
      ),
    )
    const user = await selectReservation()
    await user.upload(screen.getByLabelText('인계 사진 촬영 또는 업로드'), photo())
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText('인계를 완료했어요')).toBeInTheDocument()
    expect(await screen.findByText('총 3개 재사용')).toBeInTheDocument()
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
            { code: 'IDEMPOTENCY_CONFLICT', message: '처리 중이에요.' },
            { status: 409 },
          )
        : HttpResponse.json(result(0))
    }),
  )
  const user = await selectReservation()
  await user.upload(screen.getByLabelText('인계 사진 촬영 또는 업로드'), photo())
  expect(screen.getByLabelText('확정된 예약')).toBeDisabled()
  await screen.findByText('수량 확인이 필요해요', {}, { timeout: 2500 })
  expect(keys).toHaveLength(2)
  expect(keys[1]).toBe(keys[0])
})

test('cancels a zero receipt instead of submitting an invalid accepted quantity', async () => {
  let cancelled = 0
  server.use(
    http.post('*/api/reservations/7/handover', () => HttpResponse.json(result(0))),
    http.post('*/api/reservations/7/cancel', () => {
      cancelled++
      return HttpResponse.json({ ...reservation, status: 'RELEASED' })
    }),
  )
  const user = await selectReservation()
  await user.upload(screen.getByLabelText('인계 사진 촬영 또는 업로드'), photo())
  await user.click(
    await screen.findByRole('button', { name: '아무것도 받지 않았어요 · 예약 취소' }),
  )
  expect(await screen.findByText('예약을 취소하고 수량을 재고로 돌려놓았어요.')).toBeInTheDocument()
  expect(cancelled).toBe(1)
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
  await user.selectOptions(screen.getByLabelText('행사 필터'), '1')
  await waitFor(() => expect(filters).toContain('1'))
  expect(await screen.findByText('총 0개 재사용')).toBeInTheDocument()
  expect(screen.getByText('아직 완료된 인계가 없어요.')).toBeInTheDocument()
})
