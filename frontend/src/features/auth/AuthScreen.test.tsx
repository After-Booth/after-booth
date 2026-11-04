import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import { AuthScreen } from './AuthScreen'

let verificationAttempts = 0
const server = setupServer(
  http.get('*/api/auth/csrf', () => HttpResponse.json({ csrfToken: 'synthetic-csrf' })),
  http.post('*/api/auth/registrations', () => new HttpResponse(null, { status: 202 })),
  http.post('*/api/auth/email-verifications/request', () =>
    HttpResponse.json(
      { message: 'Check your verification email.', challengeId: 'synthetic-challenge' },
      { status: 202 },
    ),
  ),
  http.post('*/api/auth/email-verifications/confirm', async ({ request }) => {
    const body = (await request.json()) as { password: string; code: string }
    expect(body.password).toBe('synthetic-password')
    verificationAttempts += 1
    return body.code === '123456'
      ? new HttpResponse(null, { status: 204 })
      : HttpResponse.json(
          { code: 'INVALID_AUTH_CHALLENGE', message: 'Check verification details.' },
          { status: 400 },
        )
  }),
)
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

test('signup retains the chosen password only until successful verification and requires login', async () => {
  const onLogin = vi.fn()
  render(<AuthScreen onLogin={onLogin} />)
  await userEvent.click(screen.getByRole('button', { name: 'Create account' }))
  await userEvent.type(screen.getByLabelText('Email'), 'person@example.test')
  await userEvent.type(screen.getByLabelText('Password'), 'synthetic-password')
  await userEvent.click(screen.getByRole('button', { name: 'Request verification email' }))
  const code = await screen.findByLabelText('Email verification code')
  await userEvent.type(code, '000000')
  await userEvent.click(screen.getByRole('button', { name: 'Complete signup' }))
  expect(await screen.findByText('Check verification details.')).toBeInTheDocument()
  await userEvent.clear(code)
  await userEvent.type(code, '123456')
  await userEvent.click(screen.getByRole('button', { name: 'Complete signup' }))
  expect(await screen.findByText('Email verified. Please sign in.')).toBeInTheDocument()
  expect(screen.getByLabelText('Password')).toHaveValue('')
  expect(onLogin).not.toHaveBeenCalled()
  expect(verificationAttempts).toBe(2)
})

test.each([
  [403, 'CSRF_REJECTED', 'Security check expired. Please try again.'],
  [429, 'RATE_LIMITED', 'Too many requests. Please try again shortly.'],
  [503, 'AUTH_UNAVAILABLE', 'Service temporarily unavailable. Please try again shortly.'],
  [401, 'UNAUTHENTICATED', 'Check your email or password.'],
])('login distinguishes a %i %s failure', async (status, code, message) => {
  server.use(
    http.post('*/api/auth/login', () =>
      HttpResponse.json({ code, message: 'Server message' }, { status }),
    ),
  )
  render(<AuthScreen onLogin={vi.fn()} />)
  await userEvent.type(screen.getByLabelText('Email'), 'person@example.test')
  await userEvent.type(screen.getByLabelText('Password'), 'synthetic-password')
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
  expect(await screen.findByText(message)).toBeInTheDocument()
})

test('a pending member who logs in can request a new code and finish verification', async () => {
  server.use(
    http.post('*/api/auth/login', () =>
      HttpResponse.json(
        { code: 'EMAIL_NOT_VERIFIED', message: 'Please verify your email.' },
        { status: 403 },
      ),
    ),
  )
  const onLogin = vi.fn()
  render(<AuthScreen onLogin={onLogin} />)
  await userEvent.type(screen.getByLabelText('Email'), 'person@example.test')
  await userEvent.type(screen.getByLabelText('Password'), 'synthetic-password')
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
  expect(await screen.findByText('Please verify your email.')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Resend verification code' }))
  await userEvent.type(await screen.findByLabelText('Email verification code'), '123456')
  await userEvent.click(screen.getByRole('button', { name: 'Complete signup' }))
  expect(await screen.findByText('Email verified. Please sign in.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Resend verification code' })).not.toBeInTheDocument()
  expect(onLogin).not.toHaveBeenCalled()
})
