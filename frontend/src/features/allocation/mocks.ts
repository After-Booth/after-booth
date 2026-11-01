import { http, HttpResponse } from 'msw'
import { mockPickupRequests } from '../request/mocks'
import type {
  AllocationResult,
  HoldItemRequest,
  Item,
  Reservation,
} from '../../api/generated/model'

const deadline = new Date(Date.now() + 60 * 60 * 1000).toISOString()
const item: Item = {
  id: 2,
  eventId: 1,
  organizationId: 1,
  category: 'FOLDING_TABLE',
  label: '접이식 테이블',
  sizeCm: { width: 60, depth: 40, height: 10 },
  condition: 'GOOD',
  totalQty: 12,
  availableQty: 12,
  pickupDeadline: deadline,
}
const reservations: Reservation[] = []
const responses = new Map<string, Reservation>()

export const allocationHandlers = [
  http.get('*/api/pickup-requests/:requestId', ({ params }) => {
    const request = mockPickupRequests.get(Number(params.requestId))
    return request
      ? HttpResponse.json(request)
      : HttpResponse.json(
          { code: 'NOT_FOUND', message: '요청을 찾을 수 없어요. 새 픽업 요청을 작성해 주세요.' },
          { status: 404 },
        )
  }),
  http.get('*/api/items', () => HttpResponse.json([item])),
  http.get('*/api/reservations', ({ request }) => {
    const id = new URL(request.url).searchParams.get('requestId')
    return HttpResponse.json(
      id ? reservations.filter((r) => r.requestId === Number(id)) : reservations,
    )
  }),
  http.post('*/api/pickup-requests/:requestId/allocation', ({ params }) => {
    const result: AllocationResult = {
      requestId: Number(params.requestId),
      status: 'NO_MATCH',
      explanation: '진열대가 현재 없어요. 승용차에 실을 수 있는 접이식 테이블을 선택해 주세요.',
      reservations: [],
      rejections: [],
      alternatives: item.availableQty
        ? [
            {
              item,
              quantity: Math.min(3, item.availableQty),
              reason: '접으면 승용차에 실을 수 있어요.',
            },
          ]
        : [],
      toolCalls: [
        {
          name: 'searchCandidates',
          arguments: {},
          outcome: 'OK',
          summary: '남은 물품과 차량 조건을 확인했어요.',
        },
      ],
    }
    return HttpResponse.json(result)
  }),
  http.post('*/api/reservations', async ({ request }) => {
    const key = request.headers.get('Idempotency-Key')
    if (!key)
      return HttpResponse.json(
        { code: 'INVALID_INPUT', message: '요청을 다시 확인해 주세요.' },
        { status: 400 },
      )
    if (responses.has(key)) return HttpResponse.json(responses.get(key), { status: 201 })
    const body = (await request.json()) as HoldItemRequest
    if (body.quantity > item.availableQty)
      return HttpResponse.json(
        {
          code: 'QTY_UNAVAILABLE',
          message: '다른 요청이 먼저 확보했어요. 남은 수량을 확인해 주세요.',
        },
        { status: 409 },
      )
    item.availableQty -= body.quantity
    const reservation: Reservation = {
      id: reservations.length + 1,
      requestId: body.requestId,
      organizationId: 1,
      itemId: body.itemId,
      itemLabel: item.label,
      quantity: body.quantity,
      status: 'HELD',
      holdExpiresAt: new Date(Date.now() + 15 * 60 * 1000).toISOString(),
      pickupDeadline: deadline,
      createdAt: new Date().toISOString(),
    }
    reservations.push(reservation)
    responses.set(key, reservation)
    return HttpResponse.json(reservation, { status: 201 })
  }),
]
