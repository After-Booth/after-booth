import { http as mockHttp, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import { http, setAccessToken } from '../../api/http'
import { changePassword } from '../../api/generated/afterbooth'
import { authOperation, clearSession, onSessionEnd, signOut } from './session'

let refreshes = 0
const server = setupServer(
  mockHttp.get('*/api/auth/csrf', () => HttpResponse.json({ csrfToken: 'synthetic-csrf' })),
  mockHttp.post('*/api/auth/refresh', async ({ request }) => {
    expect(request.headers.get('X-CSRF-Token')).toBe('synthetic-csrf')
    refreshes += 1
    await new Promise((resolve) => setTimeout(resolve, 30))
    return HttpResponse.json({ accessToken: 'new-token', tokenType: 'Bearer', expiresIn: 600 })
  }),
  mockHttp.get('*/api/private', ({ request }) =>
    request.headers.get('Authorization') === 'Bearer new-token'
      ? HttpResponse.json({ ok: true })
      : HttpResponse.json(
          { code: 'UNAUTHENTICATED', message: '로그인해 주세요.' },
          { status: 401 },
        ),
  ),
)
beforeAll(() => server.listen())
afterEach(() => {
  clearSession()
  server.resetHandlers()
  refreshes = 0
})
afterAll(() => server.close())

test('concurrent expired requests share one refresh and retry with the new access token', async () => {
  setAccessToken('expired-token')
  const result = await Promise.all([
    http<{ data: { ok: boolean } }>('/api/private'),
    http<{ data: { ok: boolean } }>('/api/private'),
  ])
  expect(result.map((entry) => entry.data.ok)).toEqual([true, true])
  expect(refreshes).toBe(1)
})

test('a request from an old session is not retried after account change', async () => {
  let release!: () => void
  const wait = new Promise<void>((resolve) => {
    release = resolve
  })
  let calls = 0
  server.use(
    mockHttp.get('*/api/private', async () => {
      calls += 1
      await wait
      return HttpResponse.json(
        { code: 'UNAUTHENTICATED', message: '로그인해 주세요.' },
        { status: 401 },
      )
    }),
  )
  setAccessToken('old-token')
  const request = http('/api/private')
  await new Promise((resolve) => setTimeout(resolve, 10))
  clearSession()
  setAccessToken('other-member-token')
  release()
  await expect(request).rejects.toMatchObject({ status: 401 })
  expect(calls).toBe(1)
  expect(refreshes).toBe(0)
})

test('an expired refresh session clears the access token and is not retried', async () => {
  server.use(
    mockHttp.post('*/api/auth/refresh', () => {
      refreshes += 1
      return HttpResponse.json(
        { code: 'UNAUTHENTICATED', message: '로그인해 주세요.' },
        { status: 401 },
      )
    }),
  )
  setAccessToken('expired-token')
  await expect(http('/api/private')).rejects.toMatchObject({ status: 401 })
  expect(refreshes).toBe(1)
  let authorization: string | null = 'unknown'
  server.use(
    mockHttp.get('*/api/private', ({ request }) => {
      authorization = request.headers.get('Authorization')
      return HttpResponse.json({ ok: true })
    }),
  )
  await http('/api/private')
  expect(authorization).toBeNull()
})

test('a temporary refresh failure reports the error without clearing the current session', async () => {
  server.use(
    mockHttp.post('*/api/auth/refresh', () =>
      HttpResponse.json(
        { code: 'AUTH_UNAVAILABLE', message: '잠시 후 다시 시도해 주세요.' },
        { status: 503 },
      ),
    ),
  )
  setAccessToken('expired-token')
  await expect(http('/api/private')).rejects.toMatchObject({ status: 503 })
  let authorization: string | null = null
  server.use(
    mockHttp.get('*/api/private', ({ request }) => {
      authorization = request.headers.get('Authorization')
      return HttpResponse.json({ ok: true })
    }),
  )
  await http('/api/private')
  expect(authorization).toBe('Bearer expired-token')
})

test('logout clears the access token locally even when the server call fails', async () => {
  server.use(
    mockHttp.post('*/api/auth/logout', () =>
      HttpResponse.json(
        { code: 'AUTH_UNAVAILABLE', message: '잠시 후 다시 시도해 주세요.' },
        { status: 503 },
      ),
    ),
  )
  const ended = vi.fn()
  const unsubscribe = onSessionEnd(ended)
  setAccessToken('new-token')
  await expect(signOut()).rejects.toMatchObject({ status: 503 })
  expect(ended).toHaveBeenCalledOnce()
  unsubscribe()
  let authorization: string | null = 'unknown'
  server.use(
    mockHttp.get('*/api/private', ({ request }) => {
      authorization = request.headers.get('Authorization')
      return HttpResponse.json({ ok: true })
    }),
  )
  await http('/api/private')
  expect(authorization).toBeNull()
})

test('a 401 that persists after a successful refresh ends the session without more retries', async () => {
  let protectedCalls = 0
  server.use(
    mockHttp.get('*/api/private', () => {
      protectedCalls += 1
      return HttpResponse.json(
        { code: 'UNAUTHENTICATED', message: '로그인이 필요해요.' },
        { status: 401 },
      )
    }),
  )
  const ended = vi.fn()
  const unsubscribe = onSessionEnd(ended)
  setAccessToken('expired-token')
  await expect(http('/api/private')).rejects.toMatchObject({ status: 401 })
  expect(refreshes).toBe(1)
  expect(protectedCalls).toBe(2)
  expect(ended).toHaveBeenCalledOnce()
  unsubscribe()
})

test('an authentication retry does not refresh again when the token was already renewed', async () => {
  let attempts = 0
  server.use(
    mockHttp.post('*/api/members/me/password', () =>
      ++attempts === 1
        ? HttpResponse.json(
            { code: 'UNAUTHENTICATED', message: '로그인이 필요해요.' },
            { status: 401 },
          )
        : new HttpResponse(null, { status: 204 }),
    ),
  )
  setAccessToken('expired-token')
  await authOperation(async (csrf) => {
    // Another request renews the session while this action is in flight.
    if (attempts === 0) setAccessToken('new-token', true)
    return changePassword(
      { currentPassword: 'synthetic-old-password', newPassword: 'synthetic-new-password' },
      csrf,
    )
  }, true)
  expect(refreshes).toBe(0)
  expect(attempts).toBe(2)
})

test('a protected authentication mutation refreshes once and obtains a fresh CSRF token before retry', async () => {
  let csrfCount = 0
  const requests: Array<{ token: string | null; csrf: string | null }> = []
  server.use(
    mockHttp.get('*/api/auth/csrf', () => HttpResponse.json({ csrfToken: `csrf-${++csrfCount}` })),
    mockHttp.post('*/api/auth/refresh', () => {
      refreshes += 1
      return HttpResponse.json({ accessToken: 'new-token', tokenType: 'Bearer', expiresIn: 600 })
    }),
    mockHttp.post('*/api/members/me/password', ({ request }) => {
      requests.push({
        token: request.headers.get('Authorization'),
        csrf: request.headers.get('X-CSRF-Token'),
      })
      return requests.length === 1
        ? HttpResponse.json(
            { code: 'UNAUTHENTICATED', message: '로그인해 주세요.' },
            { status: 401 },
          )
        : new HttpResponse(null, { status: 204 })
    }),
  )
  setAccessToken('expired-token')
  await authOperation(
    (csrf) =>
      changePassword(
        { currentPassword: 'synthetic-old-password', newPassword: 'synthetic-new-password' },
        csrf,
      ),
    true,
  )
  expect(refreshes).toBe(1)
  expect(requests).toEqual([
    { token: 'Bearer expired-token', csrf: 'csrf-1' },
    { token: 'Bearer new-token', csrf: 'csrf-3' },
  ])
})
