import { countdown } from './countdown'
import { AllocationProposal } from './AllocationProposal'
import { useAllocationBoard } from './useAllocationBoard'

const statuses = {
  HELD: '임시 확보',
  CONFIRMED: '예약 확정',
  EXPIRED: '확보 만료',
  RELEASED: '예약 해제',
  HANDED_OVER: '인계 완료',
}

export function AllocationBoard({ requestId }: { requestId?: number }) {
  const board = useAllocationBoard(requestId)
  return (
    <main className="min-h-dvh bg-stone-50 px-4 py-8 text-slate-900">
      <div className="mx-auto max-w-2xl space-y-6">
        <header>
          <a href="#/request" className="text-emerald-800 underline">
            픽업 요청하기
          </a>
          <h1 className="mt-4 text-3xl font-bold">배정 보드</h1>
          <p className="mt-2 text-slate-600">
            남은 수량과 요청 결과를 3초마다 확인해요. 시간은 한국 시간 기준이에요.
          </p>
        </header>
        {board.loading && <p role="status">배정 현황을 불러오는 중이에요…</p>}
        {board.message && (
          <p role="alert" className="text-red-700">
            {board.message}
          </p>
        )}
        {board.request && <AllocationProposal key={board.request.id} request={board.request} />}
        <section aria-labelledby="stock-heading">
          <h2 id="stock-heading" className="text-xl font-bold">
            남은 물품
          </h2>
          {!board.loading && !board.message && !board.items.length && (
            <p className="mt-4">등록된 물품이 아직 없어요.</p>
          )}
          <ul className="mt-3 divide-y divide-stone-200">
            {board.items.map((item) => (
              <li key={item.id} className="py-5">
                <div className="flex justify-between gap-3">
                  <h3 className="font-bold">{item.label}</h3>
                  <strong className="text-emerald-800">남은 수량 {item.availableQty}개</strong>
                </div>
                <p className="mt-2 font-semibold text-amber-900">
                  {countdown(item.pickupDeadline, board.now)}
                </p>
                <p className="mt-1 text-sm text-slate-600">
                  픽업 마감{' '}
                  {new Intl.DateTimeFormat('ko-KR', {
                    timeZone: 'Asia/Seoul',
                    month: 'long',
                    day: 'numeric',
                    hour: '2-digit',
                    minute: '2-digit',
                    hour12: false,
                  }).format(new Date(item.pickupDeadline))}
                </p>
              </li>
            ))}
          </ul>
        </section>
        <section aria-labelledby="results-heading">
          <h2 id="results-heading" className="text-xl font-bold">
            요청별 배정 결과
          </h2>
          {!board.loading && !board.reservations.length && (
            <p className="mt-3">아직 배정된 요청이 없어요.</p>
          )}
          <ul className="mt-3 space-y-3">
            {board.reservations.map((reservation) => (
              <li key={reservation.id} className="rounded-xl border border-stone-200 bg-white p-4">
                요청 #{reservation.requestId} · {reservation.itemLabel} {reservation.quantity}개
                <span className="ml-2 font-semibold text-emerald-900">
                  {statuses[reservation.status]}
                </span>
                {reservation.status === 'HELD' && (
                  <p className="mt-2 text-sm">
                    임시 확보 {countdown(reservation.holdExpiresAt, board.now)}
                  </p>
                )}
              </li>
            ))}
          </ul>
        </section>
      </div>
    </main>
  )
}
