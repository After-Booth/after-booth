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
            { code: 'UNAUTHENTICATED', message: '로그인이 필요해요.' },
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
  await userEvent.type(screen.getByLabelText('이메일'), member.email)
  await userEvent.type(screen.getByLabelText('비밀번호'), 'synthetic-password')
  await userEvent.click(screen.getByRole('button', { name: '로그인' }))
  expect(await screen.findByText('이메일 또는 비밀번호를 확인해 주세요.')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '로그인' }))
  await vi.waitFor(() => expect(onLogin).toHaveBeenCalledWith(member))
  expect(screen.getByLabelText('비밀번호')).toHaveValue('')
})

test('password reset handles an expired or used code and requires a fresh login after success', async () => {
  let challenges = 0
  server.use(
    http.post('*/api/auth/password-resets/request', () =>
      HttpResponse.json(
        { message: '메일을 확인해 주세요.', challengeId: `synthetic-challenge-${++challenges}` },
        { status: 202 },
      ),
    ),
    http.post('*/api/auth/password-resets/confirm', async ({ request }) =>
      ((await request.json()) as { challengeId: string }).challengeId === 'synthetic-challenge-1'
        ? HttpResponse.json(
            { code: 'INVALID_AUTH_CHALLENGE', message: '인증 정보를 확인해 주세요.' },
            { status: 400 },
          )
        : new HttpResponse(null, { status: 204 }),
    ),
  )
  const onLogin = vi.fn()
  render(<AuthScreen onLogin={onLogin} />)
  await userEvent.click(screen.getByRole('button', { name: '비밀번호를 잊으셨나요?' }))
  await userEvent.type(screen.getByLabelText('이메일'), member.email)
  await userEvent.click(screen.getByRole('button', { name: '재설정 메일 요청' }))
  await userEvent.type(await screen.findByLabelText('이메일 인증번호'), '123456')
  await userEvent.type(screen.getByLabelText('새 비밀번호'), 'synthetic-new-password')
  await userEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }))
  expect(await screen.findByText('인증 정보를 확인해 주세요.')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }))
  expect(await screen.findByText('인증 정보를 확인해 주세요.')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '로그인' }))
  await userEvent.click(screen.getByRole('button', { name: '비밀번호를 잊으셨나요?' }))
  await userEvent.click(screen.getByRole('button', { name: '재설정 메일 요청' }))
  await userEvent.type(await screen.findByLabelText('이메일 인증번호'), '654321')
  await userEvent.type(screen.getByLabelText('새 비밀번호'), 'synthetic-new-password')
  await userEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }))
  expect(
    await screen.findByText('비밀번호를 변경했어요. 다시 로그인해 주세요.'),
  ).toBeInTheDocument()
  expect(screen.getByLabelText('비밀번호')).toHaveValue('')
  expect(onLogin).not.toHaveBeenCalled()
})

test('password change shows incorrect current password and clears the session after success', async () => {
  let attempts = 0
  server.use(
    http.post('*/api/members/me/password', () =>
      ++attempts === 1
        ? HttpResponse.json(
            { code: 'INVALID_CURRENT_PASSWORD', message: '현재 비밀번호를 확인해 주세요.' },
            { status: 403 },
          )
        : new HttpResponse(null, { status: 204 }),
    ),
  )
  setAccessToken('synthetic-token')
  const ended = vi.fn()
  const unsubscribe = onSessionEnd(ended)
  render(<AccountScreen member={member} />)
  await userEvent.type(screen.getByLabelText('현재 비밀번호'), 'synthetic-old-password')
  await userEvent.type(screen.getByLabelText('새 비밀번호'), 'synthetic-new-password')
  await userEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }))
  expect(await screen.findByText('현재 비밀번호를 확인해 주세요.')).toBeInTheDocument()
  expect(ended).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }))
  await vi.waitFor(() => expect(ended).toHaveBeenCalledOnce())
  unsubscribe()
})

test('withdrawal explains an active transaction rejection and clears the session after success', async () => {
  let attempts = 0
  server.use(
    http.delete('*/api/members/me', () =>
      ++attempts === 1
        ? HttpResponse.json(
            { code: 'WITHDRAWAL_BLOCKED', message: '진행 중인 거래를 완료한 뒤 탈퇴해 주세요.' },
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
  expect(screen.getByText(/30일 뒤 다시 가입/)).toBeInTheDocument()
  await userEvent.type(screen.getByLabelText('현재 비밀번호'), 'synthetic-password')
  await userEvent.click(screen.getByRole('button', { name: '회원 탈퇴' }))
  expect(await screen.findByText('진행 중인 거래를 완료한 뒤 탈퇴해 주세요.')).toBeInTheDocument()
  expect(ended).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: '회원 탈퇴' }))
  await vi.waitFor(() => expect(ended).toHaveBeenCalledOnce())
  unsubscribe()
})
