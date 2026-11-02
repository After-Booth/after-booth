import { useEffect, useState } from 'react'
import { AllocationBoard } from './features/allocation/AllocationBoard'
import { getHealth } from './api/generated/afterbooth'
import type { Health } from './api/generated/model'
import { RequestScreen } from './features/request/RequestScreen'
import { IntakeScreen } from './features/intake/IntakeScreen'
import { HandoverScreen } from './features/handover/HandoverScreen'

const navLink = 'inline-flex min-h-11 items-center font-semibold text-emerald-800 underline'

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
      <header className="mx-auto max-w-md bg-white px-4 pt-4 text-slate-900">
        <p className="text-lg font-bold">애프터부스</p>
        <p className="text-sm text-slate-600">행사가 끝난 물품을 다음 행사로 연결해요.</p>
        <nav className="mt-1 flex flex-wrap gap-x-4 text-sm">
          <a href="#/request" className={navLink}>
            물품 픽업 요청하기
          </a>
          <a href="#/allocation" className={navLink}>
            배정 보드
          </a>
          <a href="#/handover" className={navLink}>
            물품 인계 및 재사용 통계
          </a>
        </nav>
      </header>
      <IntakeScreen />
      <p className="mx-auto max-w-md px-4 pb-6 text-xs text-slate-400" data-testid="health">
        {error
          ? '서버 연결 실패'
          : health
            ? `서버 ${health.status} · AI ${health.aiMode}`
            : '서버 확인 중…'}
      </p>
    </>
  )
}

export default function App() {
  const [hash, setHash] = useState(window.location.hash)
  useEffect(() => {
    const navigate = () => setHash(window.location.hash)
    window.addEventListener('hashchange', navigate)
    return () => window.removeEventListener('hashchange', navigate)
  }, [])
  if (hash.startsWith('#/allocation')) {
    const id = Number(new URLSearchParams(hash.split('?')[1]).get('requestId'))
    return (
      <AllocationBoard key={id} requestId={Number.isSafeInteger(id) && id > 0 ? id : undefined} />
    )
  }
  if (hash === '#/handover') return <HandoverScreen />
  return hash === '#/request' ? <RequestScreen /> : <HomeScreen />
}
