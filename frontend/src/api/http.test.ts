import { http as mswHttp, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, beforeAll, expect, test } from 'vitest'
import { ApiError, http } from './http'

const server = setupServer(
  mswHttp.get('http://test/ok', () => HttpResponse.json({ value: 1 })),
  mswHttp.get('http://test/conflict', () =>
    HttpResponse.json(
      { code: 'QTY_UNAVAILABLE', message: 'Insufficient available quantity.' },
      { status: 409 },
    ),
  ),
  mswHttp.get('http://test/broken', () => new HttpResponse('oops', { status: 500 })),
)

beforeAll(() => server.listen())
afterAll(() => server.close())

test('returns data and status for a successful response', async () => {
  const res = await http<{ data: { value: number }; status: number }>('http://test/ok')
  expect(res.status).toBe(200)
  expect(res.data.value).toBe(1)
})

test('throws ApiError with the server code and message on failure', async () => {
  await expect(http('http://test/conflict')).rejects.toMatchObject({
    status: 409,
    code: 'QTY_UNAVAILABLE',
    message: 'Insufficient available quantity.',
  })
})

test('throws ApiError even when the body is not JSON', async () => {
  await expect(http('http://test/broken')).rejects.toBeInstanceOf(ApiError)
})
