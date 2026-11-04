import { http, HttpResponse } from 'msw'
import {
  getInterpretConditionsMockHandler,
  getListOrganizationsMockHandler,
} from '../../api/generated/afterbooth.msw'
import type { CreatePickupRequest, PickupRequest } from '../../api/generated/model'

export const mockPickupRequests = new Map<number, PickupRequest>()

export const requestHandlers = [
  getListOrganizationsMockHandler([
    { id: 1, name: 'Weekend event team' },
    { id: 2, name: 'Neighborhood culture group' },
  ]),
  getInterpretConditionsMockHandler({
    context: 'RECEIVER',
    transcript: '주말 행사에 쓸 진열대 세 개가 필요해요. 승용차로 가져갈게요.',
    conditions: {
      category: 'DISPLAY_RACK',
      quantity: 3,
      vehicleType: 'SEDAN',
      purpose: 'Weekend event display',
    },
    missingFields: [],
  }),
  http.post('*/api/pickup-requests', async ({ request }) => {
    if (!request.headers.get('Idempotency-Key'))
      return HttpResponse.json(
        { code: 'INVALID_INPUT', message: 'Please save again.' },
        { status: 400 },
      )
    const body = (await request.json()) as CreatePickupRequest
    const saved: PickupRequest = {
      id: mockPickupRequests.size + 1,
      ...body,
      createdAt: new Date().toISOString(),
    }
    mockPickupRequests.set(saved.id, saved)
    return HttpResponse.json(saved, { status: 201 })
  }),
]
