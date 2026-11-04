import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import App from './App'

const server = setupServer(
  http.get('*/api/health', () => HttpResponse.json({ status: 'UP', aiMode: 'replay' })),
  http.get('*/api/auth/csrf', () => HttpResponse.json({ csrfToken: 'test-csrf' })),
  http.post('*/api/auth/refresh', () =>
    HttpResponse.json({ accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 600 }),
  ),
  http.get('*/api/members/me', () =>
    HttpResponse.json({ id: 1, email: 'test@example.test', state: 'ACTIVE', organizationId: 1 }),
  ),
  http.get('*/api/events', () => HttpResponse.json([])),
)

beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

test('shows the server status in Korean', async () => {
  render(<App />)
  expect(await screen.findByText('서버 UP · AI replay')).toBeInTheDocument()
})

test('puts the photo capture first, links the screens and keeps one main landmark', async () => {
  render(<App />)
  const capture = await screen.findByLabelText('부스 사진 찍기')
  const health = await screen.findByText('서버 UP · AI replay')
  expect(capture.compareDocumentPosition(health) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(screen.getAllByRole('main')).toHaveLength(1)
  expect(screen.getByRole('link', { name: '물품 픽업 요청하기' })).toHaveAttribute(
    'href',
    '#/request',
  )
  expect(screen.getByRole('link', { name: '배정 보드' })).toHaveAttribute('href', '#/allocation')
  expect(screen.getByRole('link', { name: '물품 인계 및 재사용 통계' })).toHaveAttribute(
    'href',
    '#/handover',
  )
})

test('switching accounts in one tab never shows the previous member’s data', async () => {
  window.location.hash = '#/'
  const privateEvent = {
    id: 1,
    name: 'Member A private event',
    organizationId: 1,
    venue: 'Hall',
    teardownDeadline: '2026-11-07T09:00:00Z',
  }
  const bearer = (request: Request) => request.headers.get('Authorization') ?? ''
  server.use(
    http.post('*/api/auth/refresh', () =>
      HttpResponse.json(
        { code: 'UNAUTHENTICATED', message: '로그인이 필요해요.' },
        { status: 401 },
      ),
    ),
    http.post('*/api/auth/login', async ({ request }) => {
      const { email } = (await request.json()) as { email: string }
      return HttpResponse.json({
        accessToken: email.startsWith('a') ? 'token-a' : 'token-b',
        tokenType: 'Bearer',
        expiresIn: 600,
      })
    }),
    http.get('*/api/members/me', ({ request }) => {
      const a = bearer(request) === 'Bearer token-a'
      return HttpResponse.json({
        id: a ? 1 : 2,
        email: a ? 'a@example.test' : 'b@example.test',
        state: 'ACTIVE',
        organizationId: a ? 1 : 2,
      })
    }),
    http.get('*/api/events', ({ request }) =>
      HttpResponse.json(bearer(request) === 'Bearer token-a' ? [privateEvent] : []),
    ),
    http.post('*/api/auth/logout', () => new HttpResponse(null, { status: 204 })),
  )
  const signIn = async (email: string) => {
    await userEvent.type(await screen.findByLabelText('이메일'), email)
    await userEvent.type(screen.getByLabelText('비밀번호'), 'synthetic-password')
    await userEvent.click(screen.getByRole('button', { name: '로그인' }))
  }

  render(<App />)
  await signIn('a@example.test')
  expect(await screen.findByRole('option', { name: 'Member A private event' })).toBeInTheDocument()

  await userEvent.click(screen.getByRole('link', { name: '계정 관리' }))
  await userEvent.click(await screen.findByRole('button', { name: '로그아웃' }))
  expect(await screen.findByRole('heading', { name: '다시 만나요' })).toBeInTheDocument()
  expect(screen.queryByText('Member A private event')).not.toBeInTheDocument()

  await signIn('b@example.test')
  await screen.findByLabelText('등록할 행사')
  expect(screen.queryByRole('option', { name: 'Member A private event' })).not.toBeInTheDocument()
  expect(screen.getAllByRole('option')).toHaveLength(1)
  // Let the home screen's health request finish so nothing resolves after the test ends.
  expect(await screen.findByText('서버 UP · AI replay')).toBeInTheDocument()
})

test('shows a notice when the session cannot be refreshed and recovers on retry', async () => {
  window.location.hash = '#/'
  let refreshes = 0
  let healthy = false
  server.use(
    http.post('*/api/auth/refresh', () => {
      refreshes += 1
      return refreshes === 1 || healthy
        ? HttpResponse.json({ accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 600 })
        : HttpResponse.json(
            { code: 'AUTH_UNAVAILABLE', message: '잠시 후 다시 시도해 주세요.' },
            { status: 503 },
          )
    }),
    http.get('*/api/events', () =>
      HttpResponse.json(
        { code: 'UNAUTHENTICATED', message: '로그인이 필요해요.' },
        { status: 401 },
      ),
    ),
  )
  render(<App />)
  const notice = await screen.findByRole('alert')
  expect(notice).toHaveTextContent('로그인 상태를 확인하지 못했어요')
  expect(screen.getByRole('navigation', { name: '주요 메뉴' })).toBeInTheDocument()

  healthy = true
  await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  await vi.waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  expect(await screen.findByText('서버 UP · AI replay')).toBeInTheDocument()
})
