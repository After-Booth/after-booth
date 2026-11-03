import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, beforeAll, expect, test, vi } from 'vitest'
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
          { code: 'INVALID_CODE', message: '인증번호를 확인해 주세요.' },
          { status: 400 },
        )
  }),
)
beforeAll(() => server.listen())
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
  expect(await screen.findByText('인증번호를 확인해 주세요.')).toBeInTheDocument()
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
