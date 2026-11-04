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

function errorMessage(error: unknown, loginAttempt = false) {
  if (error instanceof ApiError) {
    if (loginAttempt && error.status === 401) return 'Check your email or password.'
    if (error.code === 'CSRF_REJECTED') return 'Security check expired. Please try again.'
    if (error.status === 429) return 'Too many requests. Please try again shortly.'
    if (error.status === 503) return 'Service temporarily unavailable. Please try again shortly.'
    return error.message
  }
  return 'Unable to process the request. Check your connection and try again.'
}

export function AuthScreen({ onLogin }: { onLogin: (member: MemberView) => void }) {
  const [mode, setMode] = useState<Mode>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [challenge, setChallenge] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [unverified, setUnverified] = useState(false)
  function switchMode(next: Mode) {
    setUnverified(false)
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
    setUnverified(false)
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
        setMessage('Email verified. Please sign in.')
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
        setMessage('Password changed. Please sign in again.')
      }
    } catch (error) {
      setMessage(errorMessage(error, mode === 'login'))
      if (mode === 'login' && error instanceof ApiError && error.code === 'EMAIL_NOT_VERIFIED')
        setUnverified(true)
    } finally {
      setBusy(false)
    }
  }
  async function resendVerification() {
    if (busy) return
    setBusy(true)
    setMessage('')
    try {
      const response = await authOperation((csrf) => requestEmailVerification({ email }, csrf))
      if (response.status === 202) {
        setUnverified(false)
        setCode('')
        setChallenge(response.data.challengeId)
        setMessage(response.data.message)
        setMode('verify')
      }
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }
  const title = {
    login: 'Welcome back',
    register: 'Get started with AfterBooth',
    verify: 'Verify email',
    forgot: 'Reset password',
    reset: 'Set a new password',
  }[mode]
  return (
    <main className="mx-auto max-w-xl px-5 py-10 text-slate-900">
      <header className="page-intro">
        <p className="section-kicker">Give equipment its next stage</p>
        <h1 className="mt-3 text-3xl font-bold">{title}</h1>
        <p className="mt-3 text-slate-600">
          Connect leftover event equipment with people who need it.
        </p>
      </header>
      <form
        onSubmit={(event) => void submit(event)}
        className="mt-6 space-y-5 rounded-2xl border border-stone-200 bg-white p-6"
      >
        <label className="block font-semibold">
          Email
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
            Email verification code
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
            {mode === 'reset' ? 'New password' : 'Password'}
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
            Use 12–128 characters. Unverified registrations expire after 24 hours.
          </p>
        )}
        {message && (
          <p role="status" className="rounded-xl bg-emerald-50 p-4 text-emerald-900">
            {message}
          </p>
        )}
        {unverified && (
          <button
            type="button"
            disabled={busy}
            className="min-h-12 font-semibold text-emerald-800 underline"
            onClick={() => void resendVerification()}
          >
            Resend verification code
          </button>
        )}
        <button disabled={busy} className={button}>
          {busy
            ? 'Processing…'
            : {
                login: 'Sign in',
                register: 'Request verification email',
                verify: 'Complete signup',
                forgot: 'Request reset email',
                reset: 'Change password',
              }[mode]}
        </button>
      </form>
      <div className="mt-5 flex flex-wrap gap-4 text-sm font-semibold text-emerald-800">
        {mode !== 'login' && <button onClick={() => switchMode('login')}>Sign in</button>}
        {mode === 'login' && (
          <>
            <button onClick={() => switchMode('register')}>Create account</button>
            <button onClick={() => switchMode('forgot')}>Forgot password?</button>
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
      !window.confirm(
        'Withdraw your account? Your account and previous history cannot be restored.',
      )
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
      if (action !== 'logout' && error instanceof ApiError && error.status === 401) {
        clearSession()
        return
      }
      setMessage(
        action === 'logout'
          ? 'Sign out failed. Your session may still be active. Try signing out again.'
          : errorMessage(error),
      )
    } finally {
      setBusy(false)
    }
  }
  return (
    <main className="mx-auto max-w-xl space-y-6 px-5 py-10 text-slate-900">
      <header className="page-intro">
        <p className="section-kicker">My account</p>
        <h1 className="mt-3 text-3xl font-bold">Account settings</h1>
      </header>
      <section className="space-y-2 rounded-2xl border border-stone-200 bg-white p-6">
        <h2 className="font-bold">Account details</h2>
        <p>Email: {member.email}</p>
        <p>Member ID: {member.id}</p>
        <p>Status: {member.state}</p>
        <p>Organization ID: {member.organizationId}</p>
      </section>
      <section className="space-y-4 rounded-2xl border border-stone-200 bg-white p-6">
        <h2 className="font-bold">Password and withdrawal</h2>
        <label className="block">
          Current password
          <input
            type="password"
            autoComplete="current-password"
            className={input}
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
          />
        </label>
        <label className="block">
          New password
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
          Change password
        </button>
        <p className="text-sm text-slate-600">
          You cannot withdraw while you have held or confirmed transactions. You can register with
          the same email after 30 days; previous history will not be restored.
        </p>
        <button
          className="min-h-12 text-red-700 underline disabled:opacity-50"
          disabled={busy || !current}
          onClick={() => void run('withdraw')}
        >
          Withdraw account
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
        Sign out
      </button>
    </main>
  )
}
