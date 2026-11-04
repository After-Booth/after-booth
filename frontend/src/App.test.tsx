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

test('shows the server status in English', async () => {
  render(<App />)
  expect(await screen.findByText('Server UP · AI replay')).toBeInTheDocument()
})

test('puts the photo capture first, links the screens and keeps one main landmark', async () => {
  render(<App />)
  const capture = await screen.findByLabelText('Take booth photo')
  const health = await screen.findByText('Server UP · AI replay')
  expect(capture.compareDocumentPosition(health) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(screen.getAllByRole('main')).toHaveLength(1)
  expect(screen.getByRole('link', { name: 'Request item pickup' })).toHaveAttribute(
    'href',
    '#/request',
  )
  expect(screen.getByRole('link', { name: 'Allocation board' })).toHaveAttribute(
    'href',
    '#/allocation',
  )
  expect(screen.getByRole('link', { name: 'Item handover and reuse stats' })).toHaveAttribute(
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
      HttpResponse.json({ code: 'UNAUTHENTICATED', message: 'Sign in required.' }, { status: 401 }),
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
    await userEvent.type(await screen.findByLabelText('Email'), email)
    await userEvent.type(screen.getByLabelText('Password'), 'synthetic-password')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
  }

  render(<App />)
  await signIn('a@example.test')
  expect(await screen.findByRole('option', { name: 'Member A private event' })).toBeInTheDocument()

  await userEvent.click(screen.getByRole('link', { name: 'Account settings' }))
  await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }))
  expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
  expect(screen.queryByText('Member A private event')).not.toBeInTheDocument()

  await signIn('b@example.test')
  await screen.findByLabelText('Event for registration')
  expect(screen.queryByRole('option', { name: 'Member A private event' })).not.toBeInTheDocument()
  expect(screen.getAllByRole('option')).toHaveLength(1)
  // Let the home screen's health request finish so nothing resolves after the test ends.
  expect(await screen.findByText('Server UP · AI replay')).toBeInTheDocument()
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
            { code: 'AUTH_UNAVAILABLE', message: 'Please try again shortly.' },
            { status: 503 },
          )
    }),
    http.get('*/api/events', () =>
      HttpResponse.json({ code: 'UNAUTHENTICATED', message: 'Sign in required.' }, { status: 401 }),
    ),
  )
  render(<App />)
  const notice = await screen.findByRole('alert')
  expect(notice).toHaveTextContent('Unable to refresh your session')
  expect(screen.getByRole('navigation', { name: 'Main navigation' })).toBeInTheDocument()

  healthy = true
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
  await vi.waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  expect(await screen.findByText('Server UP · AI replay')).toBeInTheDocument()
})

test('revokes the server session when signing out from a refresh outage', async () => {
  window.location.hash = '#/'
  let refreshes = 0
  let revoked = false
  server.use(
    http.post('*/api/auth/refresh', () =>
      ++refreshes === 1
        ? HttpResponse.json({ accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 600 })
        : HttpResponse.json({ code: 'AUTH_UNAVAILABLE', message: 'Unavailable' }, { status: 503 }),
    ),
    http.get('*/api/events', () =>
      HttpResponse.json({ code: 'UNAUTHENTICATED', message: 'Expired' }, { status: 401 }),
    ),
    http.post('*/api/auth/logout', () => {
      revoked = true
      return new HttpResponse(null, { status: 204 })
    }),
  )
  render(<App />)
  await screen.findByRole('alert')
  await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))
  expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
  expect(revoked).toBe(true)
})

test.each(['csrf', 'logout'])(
  'failed %s keeps a visible logout retry after refresh recovers',
  async (failure) => {
    window.location.hash = '#/'
    let refreshes = 0
    let failing = false
    let recovered = false
    let revoked = false
    server.use(
      http.post('*/api/auth/refresh', () =>
        revoked
          ? HttpResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 })
          : ++refreshes === 1 || recovered
            ? HttpResponse.json({ accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 600 })
            : HttpResponse.json({ code: 'AUTH_UNAVAILABLE' }, { status: 503 }),
      ),
      http.get('*/api/events', () =>
        recovered
          ? HttpResponse.json([])
          : HttpResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 }),
      ),
      http.get('*/api/auth/csrf', () =>
        failing && failure === 'csrf'
          ? HttpResponse.json({ code: 'AUTH_UNAVAILABLE' }, { status: 503 })
          : HttpResponse.json({ csrfToken: 'test-csrf' }),
      ),
      http.post('*/api/auth/logout', () => {
        if (failing) return HttpResponse.json({ code: 'AUTH_UNAVAILABLE' }, { status: 503 })
        revoked = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const view = render(<App />)
    await screen.findByRole('alert')
    failing = true
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(
      await screen.findByText(
        'Sign out failed. Your session may still be active. Try signing out again.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Welcome back' })).not.toBeInTheDocument()
    expect(revoked).toBe(false)
    failing = false
    recovered = true
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Sign out failed.')
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
    expect(revoked).toBe(true)
    view.unmount()
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
  },
)

test('account settings keeps a failed logout visible even for a 401 response', async () => {
  window.location.hash = '#/account'
  let healthy = false
  server.use(
    http.post('*/api/auth/logout', () =>
      healthy
        ? new HttpResponse(null, { status: 204 })
        : HttpResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 }),
    ),
  )
  render(<App />)
  await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }))
  expect(
    await screen.findByText(
      'Sign out failed. Your session may still be active. Try signing out again.',
    ),
  ).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Account settings' })).toBeInTheDocument()
  healthy = true
  await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))
  expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
})
