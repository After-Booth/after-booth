import type { PickupRequest } from '../../api/generated/model'
import { useAllocation } from './useAllocation'

const button =
  'min-h-12 rounded-xl bg-emerald-800 px-5 py-3 font-semibold text-white disabled:opacity-40'

export function AllocationProposal({ request }: { request: PickupRequest }) {
  return <RequestAllocationProposal key={request.id} request={request} />
}

function RequestAllocationProposal({ request }: { request: PickupRequest }) {
  const allocation = useAllocation(request)
  const held = allocation.reservations.filter((r) => ['HELD', 'CONFIRMED'].includes(r.status))
  const quantity = held.reduce((sum, reservation) => sum + reservation.quantity, 0)
  return (
    <section
      aria-labelledby="allocation-heading"
      className="space-y-4 rounded-2xl border border-emerald-300 bg-white p-5"
    >
      <h2 id="allocation-heading" className="text-xl font-bold">
        배정 제안
      </h2>
      <p className="text-sm text-slate-600">차량과 픽업 시간을 확인하고 물품을 임시 확보해요.</p>
      {allocation.loading && <p role="status">기존 예약을 확인하는 중이에요…</p>}
      <button
        className={button}
        disabled={allocation.pending || allocation.loading || quantity > 0}
        onClick={() => void allocation.execute()}
      >
        {allocation.pending ? '배정 확인 중…' : '배정 제안 받기'}
      </button>
      {allocation.message && (
        <p role="alert" className="text-red-700">
          {allocation.message}
        </p>
      )}
      {quantity > 0 && (
        <p role="status" className="font-bold text-emerald-900">
          {request.quantity}개 중 {quantity}개 확보
          {quantity < request.quantity ? ' · 부분 배정' : ' · 배정 성공'}
        </p>
      )}
      {allocation.result && (
        <>
          {allocation.result.status === 'NO_MATCH' && quantity === 0 && (
            <p className="font-semibold text-amber-800">조건에 맞는 물품을 확보하지 못했어요.</p>
          )}
          <p>{allocation.result.explanation}</p>
          {allocation.result.rejections.length > 0 && (
            <ul className="space-y-2 text-sm text-amber-900">
              {allocation.result.rejections.map((rejection) => (
                <li key={`${rejection.itemId}:${rejection.code}`}>{rejection.message}</li>
              ))}
            </ul>
          )}
          {allocation.result.toolCalls.length > 0 && (
            <details>
              <summary className="cursor-pointer text-sm font-semibold">서버 검사 결과</summary>
              <ol className="mt-2 space-y-2 text-sm">
                {allocation.result.toolCalls.map((call, index) => (
                  <li key={index}>
                    {call.summary ?? '조건을 확인했어요.'}{' '}
                    {call.outcome === 'REJECTED' ? '(거절)' : '(통과)'}
                  </li>
                ))}
              </ol>
            </details>
          )}
          {quantity < request.quantity && (
            <div className="space-y-3">
              <h3 className="font-semibold">직접 선택할 수 있는 대안</h3>
              {allocation.result.alternatives.length === 0 ? (
                <p className="text-sm text-slate-600">
                  현재 선택할 수 있는 대안이 없어요. 배정 보드에서 남은 수량을 확인해 주세요.
                </p>
              ) : (
                allocation.result.alternatives.map((alternative) => (
                  <article key={alternative.item.id} className="border-t border-stone-200 pt-3">
                    <h4 className="font-semibold">
                      {alternative.item.label}{' '}
                      {Math.min(alternative.quantity, request.quantity - quantity)}개
                    </h4>
                    <p className="my-2 text-sm">{alternative.reason}</p>
                    <button
                      className={button}
                      disabled={allocation.pending}
                      onClick={() =>
                        void allocation.execute({
                          ...alternative,
                          quantity: Math.min(alternative.quantity, request.quantity - quantity),
                        })
                      }
                    >
                      {alternative.item.label} 대안 선택
                    </button>
                  </article>
                ))
              )}
            </div>
          )}
        </>
      )}
      {held.length > 0 && (
        <ul className="space-y-2 text-sm">
          {held.map((reservation) => (
            <li key={reservation.id}>
              {reservation.itemLabel} {reservation.quantity}개 ·{' '}
              {reservation.status === 'HELD' ? '임시 확보' : '예약 확정'}
            </li>
          ))}
        </ul>
      )}
      {allocation.manualSelection && (
        <section className="space-y-3" aria-labelledby="current-stock-heading">
          <h3 id="current-stock-heading" className="font-semibold">
            추가로 선택할 수 있는 현재 재고
          </h3>
          <p className="text-sm text-slate-600">
            선택하면 서버가 차량 크기와 픽업 마감, 남은 수량을 다시 확인해요.
          </p>
          {allocation.stockLoading && <p role="status">현재 재고를 확인하는 중이에요…</p>}
          {allocation.stockMessage && <p role="alert">{allocation.stockMessage}</p>}
          {!allocation.stockLoading &&
            !allocation.stockMessage &&
            allocation.currentStock.length === 0 && (
              <p className="text-sm">현재 추가로 선택할 재고가 없어요.</p>
            )}
          {allocation.currentStock
            .filter((item) => item.availableQty > 0)
            .map((item) => {
              const remaining = Math.min(item.availableQty, request.quantity - quantity)
              return (
                <article key={item.id} className="border-t border-stone-200 pt-3">
                  <h4 className="font-semibold">
                    {item.label} {remaining}개
                  </h4>
                  <button
                    className={`${button} mt-2`}
                    disabled={allocation.pending}
                    onClick={() =>
                      void allocation.execute({
                        item,
                        quantity: remaining,
                        reason: '선택하면 서버가 차량과 픽업 조건을 확인해요.',
                      })
                    }
                  >
                    {item.label} 추가 선택
                  </button>
                </article>
              )
            })}
        </section>
      )}
      <a
        href={`#/allocation?requestId=${request.id}`}
        className="inline-block py-3 font-semibold text-emerald-800 underline"
      >
        배정 보드에서 남은 수량 확인
      </a>
    </section>
  )
}
