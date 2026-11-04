import { useEffect, useState } from 'react'
import { AllocationBoard } from './features/allocation/AllocationBoard'
import { getHealth } from './api/generated/afterbooth'
import type { Health } from './api/generated/model'
import { RequestScreen } from './features/request/RequestScreen'
import { IntakeScreen } from './features/intake/IntakeScreen'
import { HandoverScreen } from './features/handover/HandoverScreen'
import { AccountScreen, AuthScreen } from './features/auth/AuthScreen'
import {
  clearSession,
  onSessionEnd,
  onSessionTrouble,
  renewSession,
  restoreSession,
} from './features/auth/session'
import type { MemberView } from './api/generated/model'

const links = [
  { href: '#/', label: '물품 등록', short: '등록' },
  { href: '#/request', label: '물품 픽업 요청하기', short: '픽업 요청' },
  { href: '#/allocation', label: '배정 보드', short: '배정 보드' },
  { href: '#/handover', label: '물품 인계 및 재사용 통계', short: '인계' },
  { href: '#/account', label: '계정 관리', short: '계정' },
] as const

function SiteHeader({ hash }: { hash: string }) {
  const current = hash.startsWith('#/allocation') ? '#/allocation' : hash || '#/'
  return (
    <header className="site-header">
      <div className="site-header-inner">
        <a className="brand" href="#/" aria-label="애프터부스 홈">
          <span className="brand-mark" aria-hidden="true">
            <span />
          </span>
          <span>
            <strong>애프터부스</strong>
            <small>물품의 다음 쓰임을 잇다</small>
          </span>
        </a>
        <nav className="site-nav" aria-label="주요 메뉴">
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
            ? '서버 연결 실패'
            : health
              ? `서버 ${health.status} · AI ${health.aiMode}`
              : '서버 확인 중…'}
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
  useEffect(() => {
    let active = true
    const unsubscribe = onSessionEnd(() => {
      setMember(undefined)
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
        계정을 확인하고 있어요…
      </main>
    )
  else if (startupError)
    screen = (
      <main className="mx-auto max-w-xl px-5 py-10">
        <p role="alert">계정 정보를 확인하지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.</p>
        <button
          className="mt-4 min-h-12 text-emerald-800 underline"
          onClick={() => window.location.reload()}
        >
          다시 시도
        </button>
      </main>
    )
  else if (!member) screen = <AuthScreen onLogin={setMember} />
  else if (hash === '#/account') screen = <AccountScreen member={member} />
  else if (hash.startsWith('#/allocation')) {
    const id = Number(new URLSearchParams(hash.split('?')[1]).get('requestId'))
    screen = (
      <AllocationBoard key={id} requestId={Number.isSafeInteger(id) && id > 0 ? id : undefined} />
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
      {member && trouble && (
        <div role="alert" className="session-notice">
          <p>로그인 상태를 확인하지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.</p>
          <button
            disabled={retrying}
            onClick={() => {
              setRetrying(true)
              renewSession()
                .catch(() => undefined)
                .finally(() => setRetrying(false))
            }}
          >
            다시 시도
          </button>
          <button onClick={clearSession}>로그아웃</button>
        </div>
      )}
      {screen}
    </>
  )
}
