import { useEffect, useState } from 'react'
import { AllocationBoard } from './features/allocation/AllocationBoard'
import { getHealth } from './api/generated/afterbooth'
import type { Health } from './api/generated/model'
import { RequestScreen } from './features/request/RequestScreen'
import { IntakeScreen } from './features/intake/IntakeScreen'
import { HandoverScreen } from './features/handover/HandoverScreen'
import { AccountScreen, AuthScreen } from './features/auth/AuthScreen'
import {
  signOut,
  onSessionEnd,
  onSessionTrouble,
  renewSession,
  restoreSession,
} from './features/auth/session'
import type { MemberView } from './api/generated/model'

const links = [
  { href: '#/', label: 'Register items', short: 'Register' },
  { href: '#/request', label: 'Request item pickup', short: 'Pickup' },
  { href: '#/allocation', label: 'Allocation board', short: 'Board' },
  { href: '#/handover', label: 'Item handover and reuse stats', short: 'Handover' },
  { href: '#/account', label: 'Account settings', short: 'Account' },
] as const

function SiteHeader({ hash }: { hash: string }) {
  const current = hash.startsWith('#/allocation') ? '#/allocation' : hash || '#/'
  return (
    <header className="site-header">
      <div className="site-header-inner">
        <a className="brand" href="#/" aria-label="AfterBooth home">
          <span className="brand-mark" aria-hidden="true">
            <span />
          </span>
          <span>
            <strong>AfterBooth</strong>
            <small>Give equipment its next purpose</small>
          </span>
        </a>
        <nav className="site-nav" aria-label="Main navigation">
          {links.map((link) => (
            <a
              key={link.href}
              href={link.href}
              aria-label={link.label}
              aria-current={current === link.href ? 'page' : undefined}
            >
              {link.short}
            </a>
          ))}
        </nav>
      </div>
    </header>
  )
}

function HomeScreen() {
  const [health, setHealth] = useState<Health | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    getHealth()
      .then((res) => setHealth(res.data))
      .catch(() => setError(true))
  }, [])

  return (
    <>
      <IntakeScreen showEvents />
      <footer className="mx-auto max-w-2xl px-5 pb-8">
        <p className="text-xs text-slate-400" data-testid="health">
          {error
            ? 'Server connection failed'
            : health
              ? `Server ${health.status} · AI ${health.aiMode}`
              : 'Checking server…'}
        </p>
      </footer>
    </>
  )
}

export default function App() {
  const [hash, setHash] = useState(window.location.hash)
  const [member, setMember] = useState<MemberView>()
  const [checking, setChecking] = useState(true)
  const [startupError, setStartupError] = useState(false)
  const [trouble, setTrouble] = useState(false)
  const [retrying, setRetrying] = useState(false)
  const [logoutFailed, setLogoutFailed] = useState(false)
  useEffect(() => {
    let active = true
    const unsubscribe = onSessionEnd(() => {
      setMember(undefined)
      setLogoutFailed(false)
      window.location.hash = '#/'
    })
    const unsubscribeTrouble = onSessionTrouble(setTrouble)
    restoreSession()
      .then((current) => {
        if (active) {
          setMember(current)
          setChecking(false)
        }
      })
      .catch(() => {
        if (active) {
          setStartupError(true)
          setChecking(false)
        }
      })
    return () => {
      active = false
      unsubscribe()
      unsubscribeTrouble()
    }
  }, [])
  useEffect(() => {
    const navigate = () => setHash(window.location.hash)
    window.addEventListener('hashchange', navigate)
    return () => window.removeEventListener('hashchange', navigate)
  }, [])
  let screen
  if (checking)
    screen = (
      <main className="mx-auto max-w-xl px-5 py-10" role="status">
        Checking your account…
      </main>
    )
  else if (startupError)
    screen = (
      <main className="mx-auto max-w-xl px-5 py-10">
        <p role="alert">Unable to check your account. Check your connection and try again.</p>
        <button
          className="mt-4 min-h-12 text-emerald-800 underline"
          onClick={() => window.location.reload()}
        >
          Try again
        </button>
      </main>
    )
  else if (!member) screen = <AuthScreen onLogin={setMember} />
  else if (hash === '#/account') screen = <AccountScreen member={member} />
  else if (hash.startsWith('#/allocation')) {
    const id = Number(new URLSearchParams(hash.split('?')[1]).get('requestId'))
    screen = (
      <AllocationBoard
        key={id}
        organizationId={member.organizationId}
        requestId={Number.isSafeInteger(id) && id > 0 ? id : undefined}
      />
    )
  } else if (hash === '#/handover') screen = <HandoverScreen />
  else
    screen =
      hash === '#/request' ? (
        <RequestScreen organizationId={member.organizationId} />
      ) : (
        <HomeScreen />
      )
  return (
    <>
      {member && <SiteHeader hash={hash} />}
      {member && (trouble || logoutFailed) && (
        <div role="alert" className="session-notice">
          <p>
            {logoutFailed
              ? 'Sign out failed. Your session may still be active. Try signing out again.'
              : 'Unable to refresh your session. Check your connection and try again.'}
          </p>
          <button
            disabled={retrying}
            onClick={() => {
              setRetrying(true)
              renewSession()
                .catch(() => undefined)
                .finally(() => setRetrying(false))
            }}
          >
            Try again
          </button>
          <button
            disabled={retrying}
            onClick={() => {
              setRetrying(true)
              signOut()
                .catch(() => setLogoutFailed(true))
                .finally(() => setRetrying(false))
            }}
          >
            Sign out
          </button>
        </div>
      )}
      {screen}
    </>
  )
}
