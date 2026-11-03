import { useState, type FormEvent } from 'react'
import {
  registerMember,
  requestEmailVerification,
  verifyEmail,
  requestPasswordReset,
  resetPassword,
  changePassword,
  withdrawMember,
} from '../../api/generated/afterbooth'
import type { MemberView } from '../../api/generated/model'
import { ApiError } from '../../api/http'
import { authOperation, clearSession, signIn, signOut } from './session'

const input = 'mt-2 block min-h-12 w-full rounded-xl border border-stone-300 bg-white px-3'
const button =
  'min-h-12 w-full rounded-xl bg-emerald-800 px-5 py-3 font-semibold text-white disabled:opacity-50'
type Mode = 'login' | 'register' | 'verify' | 'forgot' | 'reset'

function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code === 'CSRF_REJECTED') return '보안 확인이 만료되었어요. 다시 시도해 주세요.'
    if (error.status === 429) return '요청이 너무 많아요. 잠시 후 다시 시도해 주세요.'
    if (error.status === 503) return '서비스를 잠시 이용할 수 없어요. 잠시 후 다시 시도해 주세요.'
    return error.message
  }
  return '요청을 처리하지 못했어요. 연결을 확인하고 다시 시도해 주세요.'
}

export function AuthScreen({ onLogin }: { onLogin: (member: MemberView) => void }) {
  const [mode, setMode] = useState<Mode>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [challenge, setChallenge] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  function switchMode(next: Mode) {
    setPassword('')
    setCode('')
    setChallenge('')
    setMessage('')
    setMode(next)
  }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setMessage('')
    try {
      if (mode === 'login') {
        const member = await signIn(email, password)
        setPassword('')
        onLogin(member)
      }
      if (mode === 'register') {
        await authOperation((csrf) => registerMember({ email, password }, csrf))
        const response = await authOperation((csrf) => requestEmailVerification({ email }, csrf))
        if (response.status === 202) {
          setChallenge(response.data.challengeId)
          setMessage(response.data.message)
          setMode('verify')
        }
      }
      if (mode === 'verify') {
        await authOperation((csrf) =>
          verifyEmail({ email, password, challengeId: challenge, code }, csrf),
        )
        setPassword('')
        setCode('')
        setChallenge('')
        setMode('login')
        setMessage('이메일 인증이 완료되었어요. 로그인해 주세요.')
      }
      if (mode === 'forgot') {
        const response = await authOperation((csrf) => requestPasswordReset({ email }, csrf))
        if (response.status === 202) {
          setChallenge(response.data.challengeId)
          setMessage(response.data.message)
          setMode('reset')
        }
      }
      if (mode === 'reset') {
        await authOperation((csrf) =>
          resetPassword({ email, challengeId: challenge, code, newPassword: password }, csrf),
        )
        setPassword('')
        setCode('')
        setChallenge('')
        clearSession()
        setMode('login')
        setMessage('비밀번호를 변경했어요. 다시 로그인해 주세요.')
      }
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }
  const title = {
    login: '다시 만나요',
    register: '애프터부스 시작하기',
    verify: '이메일 인증',
    forgot: '비밀번호 재설정',
    reset: '새 비밀번호 설정',
  }[mode]
  return (
    <main className="mx-auto max-w-xl px-5 py-10 text-slate-900">
      <header className="page-intro">
        <p className="section-kicker">물품의 다음 무대를 함께</p>
        <h1 className="mt-3 text-3xl font-bold">{title}</h1>
        <p className="mt-3 text-slate-600">행사가 끝난 물품을 필요한 곳으로 연결해요.</p>
      </header>
      <form
        onSubmit={(event) => void submit(event)}
        className="mt-6 space-y-5 rounded-2xl border border-stone-200 bg-white p-6"
      >
        <label className="block font-semibold">
          이메일
          <input
            className={input}
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            disabled={mode === 'verify' || mode === 'reset'}
          />
        </label>
        {(mode === 'verify' || mode === 'reset') && (
          <label className="block font-semibold">
            이메일 인증번호
            <input
              className={input}
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="[0-9]{6}"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              required
            />
          </label>
        )}
        {mode !== 'forgot' && (
          <label className="block font-semibold">
            {mode === 'reset' ? '새 비밀번호' : '비밀번호'}
            <input
              className={input}
              type="password"
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              minLength={mode === 'login' ? undefined : 12}
              maxLength={128}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </label>
        )}
        {mode === 'register' && (
          <p className="text-sm text-slate-600">
            비밀번호는 12자 이상 128자 이하여야 해요. 인증을 완료하지 않으면 가입 신청은 24시간 후
            만료돼요.
          </p>
        )}
        {message && (
          <p role="status" className="rounded-xl bg-emerald-50 p-4 text-emerald-900">
            {message}
          </p>
        )}
        <button disabled={busy} className={button}>
          {busy
            ? '처리 중이에요…'
            : {
                login: '로그인',
                register: '인증 메일 요청',
                verify: '가입 완료',
                forgot: '재설정 메일 요청',
                reset: '비밀번호 변경',
              }[mode]}
        </button>
      </form>
      <div className="mt-5 flex flex-wrap gap-4 text-sm font-semibold text-emerald-800">
        {mode !== 'login' && <button onClick={() => switchMode('login')}>로그인</button>}
        {mode === 'login' && (
          <>
            <button onClick={() => switchMode('register')}>회원가입</button>
            <button onClick={() => switchMode('forgot')}>비밀번호를 잊으셨나요?</button>
          </>
        )}
      </div>
    </main>
  )
}

export function AccountScreen({ member }: { member: MemberView }) {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  async function run(action: 'change' | 'withdraw' | 'logout') {
    if (busy) return
    if (
      action === 'withdraw' &&
      !window.confirm('정말 탈퇴하시겠어요? 탈퇴 후 계정과 이전 내역은 복구할 수 없어요.')
    )
      return
    setBusy(true)
    setMessage('')
    try {
      if (action === 'logout') await signOut()
      if (action === 'change') {
        await authOperation(
          (csrf) => changePassword({ currentPassword: current, newPassword: next }, csrf),
          true,
        )
        clearSession()
      }
      if (action === 'withdraw') {
        await authOperation((csrf) => withdrawMember({ password: current }, csrf), true)
        clearSession()
      }
      setCurrent('')
      setNext('')
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        clearSession()
        return
      }
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }
  return (
    <main className="mx-auto max-w-xl space-y-6 px-5 py-10 text-slate-900">
      <header className="page-intro">
        <p className="section-kicker">내 계정</p>
        <h1 className="mt-3 text-3xl font-bold">계정 관리</h1>
      </header>
      <section className="space-y-2 rounded-2xl border border-stone-200 bg-white p-6">
        <h2 className="font-bold">계정 정보</h2>
        <p>이메일: {member.email}</p>
        <p>회원 번호: {member.id}</p>
        <p>상태: {member.state}</p>
        <p>단체 번호: {member.organizationId}</p>
      </section>
      <section className="space-y-4 rounded-2xl border border-stone-200 bg-white p-6">
        <h2 className="font-bold">비밀번호 변경 및 탈퇴</h2>
        <label className="block">
          현재 비밀번호
          <input
            type="password"
            autoComplete="current-password"
            className={input}
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
          />
        </label>
        <label className="block">
          새 비밀번호
          <input
            type="password"
            autoComplete="new-password"
            className={input}
            value={next}
            onChange={(e) => setNext(e.target.value)}
          />
        </label>
        <button
          className={button}
          disabled={busy || !current || next.length < 12 || next.length > 128}
          onClick={() => void run('change')}
        >
          비밀번호 변경
        </button>
        <p className="text-sm text-slate-600">
          임시 확보 또는 확정된 거래가 있으면 탈퇴할 수 없어요. 탈퇴 후 같은 이메일은 30일 뒤 다시
          가입할 수 있으며 이전 내역은 복구되지 않아요.
        </p>
        <button
          className="min-h-12 text-red-700 underline disabled:opacity-50"
          disabled={busy || !current}
          onClick={() => void run('withdraw')}
        >
          회원 탈퇴
        </button>
        {message && (
          <p role="alert" className="text-red-700">
            {message}
          </p>
        )}
      </section>
      <button
        className="min-h-12 text-emerald-800 underline"
        disabled={busy}
        onClick={() => void run('logout')}
      >
        로그아웃
      </button>
    </main>
  )
}
