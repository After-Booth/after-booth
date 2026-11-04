import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import { setAccessToken } from '../../api/http'
import { AccountScreen, AuthScreen } from './AuthScreen'
import { clearSession, onSessionEnd } from './session'

const member = { id: 7, email: 'person@example.test', state: 'ACTIVE' as const, organizationId: 9 }
const server = setupServer(
  http.get('*/api/auth/csrf', () => HttpResponse.json({ csrfToken: 'synthetic-csrf' })),
)
beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  clearSession()
  vi.restoreAllMocks()
})
afterAll(() => server.close())

test('login shows an authentication error and loads the account after a successful retry', async () => {
  let attempts = 0
  server.use(
    http.post('*/api/auth/login', () =>
      ++attempts === 1
        ? HttpResponse.json(
            { code: 'UNAUTHENTICATED', message: 'Sign in required.' },
            { status: 401 },
          )
        : HttpResponse.json({
            accessToken: 'synthetic-token',
            tokenType: 'Bearer',
            expiresIn: 600,
          }),
    ),
    http.get('*/api/members/me', () => HttpResponse.json(member)),
  )
  const onLogin = vi.fn()
  render(<AuthScreen onLogin={onLogin} />)
  await userEvent.type(screen.getByLabelText('Email'), member.email)
  await userEvent.type(screen.getByLabelText('Password'), 'synthetic-password')
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
  expect(await screen.findByText('Check your email or password.')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
  await vi.waitFor(() => expect(onLogin).toHaveBeenCalledWith(member))
  expect(screen.getByLabelText('Password')).toHaveValue('')
})

test('password reset handles an expired or used code and requires a fresh login after success', async () => {
  let challenges = 0
  server.use(
    http.post('*/api/auth/password-resets/request', () =>
      HttpResponse.json(
        { message: 'Check your email.', challengeId: `synthetic-challenge-${++challenges}` },
        { status: 202 },
      ),
    ),
    http.post('*/api/auth/password-resets/confirm', async ({ request }) =>
      ((await request.json()) as { challengeId: string }).challengeId === 'synthetic-challenge-1'
        ? HttpResponse.json(
            { code: 'INVALID_AUTH_CHALLENGE', message: 'Check verification details.' },
            { status: 400 },
          )
        : new HttpResponse(null, { status: 204 }),
    ),
  )
  const onLogin = vi.fn()
  render(<AuthScreen onLogin={onLogin} />)
  await userEvent.click(screen.getByRole('button', { name: 'Forgot password?' }))
  await userEvent.type(screen.getByLabelText('Email'), member.email)
  await userEvent.click(screen.getByRole('button', { name: 'Request reset email' }))
  await userEvent.type(await screen.findByLabelText('Email verification code'), '123456')
  await userEvent.type(screen.getByLabelText('New password'), 'synthetic-new-password')
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }))
  expect(await screen.findByText('Check verification details.')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }))
  expect(await screen.findByText('Check verification details.')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
  await userEvent.click(screen.getByRole('button', { name: 'Forgot password?' }))
  await userEvent.click(screen.getByRole('button', { name: 'Request reset email' }))
  await userEvent.type(await screen.findByLabelText('Email verification code'), '654321')
  await userEvent.type(screen.getByLabelText('New password'), 'synthetic-new-password')
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }))
  expect(await screen.findByText('Password changed. Please sign in again.')).toBeInTheDocument()
  expect(screen.getByLabelText('Password')).toHaveValue('')
  expect(onLogin).not.toHaveBeenCalled()
})

test('password change shows incorrect current password and clears the session after success', async () => {
  let attempts = 0
  server.use(
    http.post('*/api/members/me/password', () =>
      ++attempts === 1
        ? HttpResponse.json(
            { code: 'INVALID_CURRENT_PASSWORD', message: 'Check your current password.' },
            { status: 403 },
          )
        : new HttpResponse(null, { status: 204 }),
    ),
  )
  setAccessToken('synthetic-token')
  const ended = vi.fn()
  const unsubscribe = onSessionEnd(ended)
  render(<AccountScreen member={member} />)
  await userEvent.type(screen.getByLabelText('Current password'), 'synthetic-old-password')
  await userEvent.type(screen.getByLabelText('New password'), 'synthetic-new-password')
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }))
  expect(await screen.findByText('Check your current password.')).toBeInTheDocument()
  expect(ended).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }))
  await vi.waitFor(() => expect(ended).toHaveBeenCalledOnce())
  unsubscribe()
})

test('withdrawal explains an active transaction rejection and clears the session after success', async () => {
  let attempts = 0
  server.use(
    http.delete('*/api/members/me', () =>
      ++attempts === 1
        ? HttpResponse.json(
            {
              code: 'WITHDRAWAL_BLOCKED',
              message: 'Complete active transactions before withdrawing.',
            },
            { status: 409 },
          )
        : new HttpResponse(null, { status: 204 }),
    ),
  )
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  setAccessToken('synthetic-token')
  const ended = vi.fn()
  const unsubscribe = onSessionEnd(ended)
  render(<AccountScreen member={member} />)
  expect(screen.getByText(/register with the same email after 30 days/)).toBeInTheDocument()
  await userEvent.type(screen.getByLabelText('Current password'), 'synthetic-password')
  await userEvent.click(screen.getByRole('button', { name: 'Withdraw account' }))
  expect(
    await screen.findByText('Complete active transactions before withdrawing.'),
  ).toBeInTheDocument()
  expect(ended).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: 'Withdraw account' }))
  await vi.waitFor(() => expect(ended).toHaveBeenCalledOnce())
  unsubscribe()
})
