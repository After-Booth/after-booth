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
      { message: '인증 메일을 확인해 주세요.', challengeId: 'synthetic-challenge' },
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
          { code: 'INVALID_AUTH_CHALLENGE', message: '인증 정보를 확인해 주세요.' },
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
  await userEvent.click(screen.getByRole('button', { name: '회원가입' }))
  await userEvent.type(screen.getByLabelText('이메일'), 'person@example.test')
  await userEvent.type(screen.getByLabelText('비밀번호'), 'synthetic-password')
  await userEvent.click(screen.getByRole('button', { name: '인증 메일 요청' }))
  const code = await screen.findByLabelText('이메일 인증번호')
  await userEvent.type(code, '000000')
  await userEvent.click(screen.getByRole('button', { name: '가입 완료' }))
  expect(await screen.findByText('인증 정보를 확인해 주세요.')).toBeInTheDocument()
  await userEvent.clear(code)
  await userEvent.type(code, '123456')
  await userEvent.click(screen.getByRole('button', { name: '가입 완료' }))
  expect(
    await screen.findByText('이메일 인증이 완료되었어요. 로그인해 주세요.'),
  ).toBeInTheDocument()
  expect(screen.getByLabelText('비밀번호')).toHaveValue('')
  expect(onLogin).not.toHaveBeenCalled()
  expect(verificationAttempts).toBe(2)
})

test.each([
  [403, 'CSRF_REJECTED', '보안 확인이 만료되었어요. 다시 시도해 주세요.'],
  [429, 'RATE_LIMITED', '요청이 너무 많아요. 잠시 후 다시 시도해 주세요.'],
  [503, 'AUTH_UNAVAILABLE', '서비스를 잠시 이용할 수 없어요. 잠시 후 다시 시도해 주세요.'],
  [401, 'UNAUTHENTICATED', '이메일 또는 비밀번호를 확인해 주세요.'],
])('login distinguishes a %i %s failure', async (status, code, message) => {
  server.use(
    http.post('*/api/auth/login', () =>
      HttpResponse.json({ code, message: '서버 문구' }, { status }),
    ),
  )
  render(<AuthScreen onLogin={vi.fn()} />)
  await userEvent.type(screen.getByLabelText('이메일'), 'person@example.test')
  await userEvent.type(screen.getByLabelText('비밀번호'), 'synthetic-password')
  await userEvent.click(screen.getByRole('button', { name: '로그인' }))
  expect(await screen.findByText(message)).toBeInTheDocument()
})
