import { useState } from 'react'
import { AiProgress } from '../../components/AiProgress'
import { AI_EXPECTED_SECONDS } from '../aiLatency'
import { useHandover } from './useHandover'

const button =
  'min-h-12 rounded-xl bg-emerald-800 px-5 py-3 font-semibold text-white disabled:opacity-50'

export function HandoverScreen() {
  const handover = useHandover()
  const [quantity, setQuantity] = useState('')
  const reservation = handover.reservations.find((entry) => entry.id === handover.selectedId)
  const confirmed = reservation?.status === 'CONFIRMED'
  const completed = handover.result?.status === 'COMPLETED'
  const accepted = Number(quantity)
  const validQuantity =
    Number.isInteger(accepted) && accepted >= 1 && accepted <= (reservation?.quantity ?? 0)

  return (
    <main className="min-h-dvh bg-stone-50 px-4 py-8 text-slate-900">
      <div className="mx-auto max-w-2xl space-y-8">
        <header>
          <a href="#/allocation" className="text-emerald-800 underline">
            배정 보드
          </a>
          <h1 className="mt-4 text-3xl font-bold">물품 인계</h1>
          <p className="mt-2 text-slate-600">현장에서 받은 물품을 촬영하고 인계 수량을 확인해요.</p>
        </header>
        {handover.loading && <p role="status">최신 예약과 통계를 불러오는 중이에요…</p>}
        {handover.readError && (
          <div role="alert">
            <p>{handover.readError}</p>
            <button
              className="mt-2 underline"
              disabled={handover.loading || handover.busy}
              onClick={() => void handover.refresh()}
            >
              조회 다시 시도
            </button>
          </div>
        )}
        <section className="space-y-4" aria-labelledby="handover-heading">
          <h2 id="handover-heading" className="text-xl font-bold">
            인계할 예약 선택
          </h2>
          <label className="block">
            확정된 예약
            <select
              className="mt-2 block min-h-12 w-full rounded-xl border border-stone-300 bg-white px-3"
              value={handover.selectedId ?? ''}
              disabled={handover.busy}
              onChange={(event) => {
                handover.select(Number(event.target.value) || undefined)
                setQuantity('')
              }}
            >
              <option value="">예약을 선택해 주세요</option>
              {handover.reservations
                .filter((entry) => entry.status === 'CONFIRMED' || entry.id === handover.selectedId)
                .map((entry) => (
                  <option key={entry.id} value={entry.id}>
                    예약 #{entry.id} · {entry.itemLabel} {entry.quantity}개
                  </option>
                ))}
            </select>
          </label>
          {!handover.loading &&
            !handover.readError &&
            !handover.reservations.some((entry) => entry.status === 'CONFIRMED') && (
              <p>인계할 확정 예약이 없어요.</p>
            )}
          {reservation && (
            <p>
              예약 수량 {reservation.quantity}개 · 픽업 마감{' '}
              {new Intl.DateTimeFormat('ko-KR', {
                timeZone: 'Asia/Seoul',
                dateStyle: 'short',
                timeStyle: 'short',
              }).format(new Date(reservation.pickupDeadline))}
            </p>
          )}
          {confirmed && !completed && !handover.cancelled && (
            <label className={`block text-center ${button}`}>
              {handover.result ? '인계 사진 다시 촬영' : '인계 사진 촬영 또는 업로드'}
              <input
                className="sr-only"
                type="file"
                accept="image/jpeg,image/png"
                capture="environment"
                disabled={handover.busy}
                onChange={(event) => {
                  const photo = event.target.files?.[0]
                  event.target.value = ''
                  if (photo) void handover.upload(photo)
                }}
              />
            </label>
          )}
          {handover.busy && (
            <AiProgress
              label="물품 수량과 분할 결과를 확인하고 있어요. 잠시 기다려 주세요…"
              expectedSeconds={AI_EXPECTED_SECONDS.handover}
            />
          )}
          {handover.message && (
            <div role="alert">
              <p>{handover.message}</p>
              {handover.canRetry && (
                <button
                  className="mt-2 underline"
                  disabled={handover.busy}
                  onClick={() => void handover.retry()}
                >
                  다시 시도
                </button>
              )}
            </div>
          )}
          {handover.result?.status === 'MISMATCH' && (
            <div className="space-y-4 rounded-xl border border-amber-300 bg-amber-50 p-5">
              <h3 className="text-lg font-bold">수량 확인이 필요해요</h3>
              <p className="text-sm">마지막으로 완료된 사진 확인 결과예요.</p>
              <p>
                예약 {handover.result.expectedQty}개 · 사진에서 센 수량 {handover.result.countedQty}
                개
              </p>
              <p>
                수량이 같아도 일부 물품을 분할하지 못하면 확인이 필요해요. 사진을 다시 찍거나 실제
                받은 수량을 확정해 주세요.
              </p>
              <form
                onSubmit={(event) => {
                  event.preventDefault()
                  if (validQuantity) void handover.resolve(accepted)
                }}
              >
                <label className="block">
                  실제 받은 수량
                  <input
                    type="number"
                    min="1"
                    max={reservation?.quantity}
                    step="1"
                    required
                    value={quantity}
                    disabled={handover.busy}
                    onChange={(event) => setQuantity(event.target.value)}
                    className="mt-2 block min-h-12 w-full rounded-xl border border-stone-300 bg-white px-3"
                  />
                </label>
                <p className="mt-2 text-sm">
                  1개부터 예약 수량까지 입력해 주세요. 받지 않은 수량은 재고로 돌아가요.
                </p>
                <button
                  type="submit"
                  className={`mt-4 w-full ${button}`}
                  disabled={handover.busy || !validQuantity}
                >
                  이 수량으로 인계 완료
                </button>
              </form>
              <button
                className="min-h-12 underline"
                disabled={handover.busy}
                onClick={() => void handover.cancel()}
              >
                아무것도 받지 않았어요 · 예약 취소
              </button>
            </div>
          )}
          {completed && (
            <div role="status" className="rounded-xl bg-emerald-100 p-5">
              <h3 className="text-lg font-bold">인계를 완료했어요</h3>
              <p className="mt-2">
                {handover.result?.reservation.itemLabel}{' '}
                {handover.result?.reservation.handedOverQty}개가 다시 사용돼요.
              </p>
            </div>
          )}
          {handover.cancelled && <p role="status">예약을 취소하고 수량을 재고로 돌려놓았어요.</p>}
        </section>
        <section
          className="space-y-4 border-t border-stone-300 pt-6"
          aria-labelledby="stats-heading"
        >
          <h2 id="stats-heading" className="text-2xl font-bold">
            재사용 통계
          </h2>
          <label className="block">
            행사 필터
            <select
              className="mt-2 block min-h-12 w-full rounded-xl border border-stone-300 bg-white px-3"
              value={handover.eventId ?? ''}
              disabled={handover.busy}
              onChange={(event) => handover.setEventId(Number(event.target.value) || undefined)}
            >
              <option value="">전체 행사</option>
              {handover.events.map((event) => (
                <option key={event.id} value={event.id}>
                  {event.name}
                </option>
              ))}
            </select>
          </label>
          {handover.stats && (
            <>
              <p className="text-2xl font-bold text-emerald-900">
                총 {handover.stats.totalReused}개 재사용
              </p>
              {handover.stats.totalReused === 0 && <p>아직 완료된 인계가 없어요.</p>}
              <h3 className="font-bold">카테고리별</h3>
              <ul>
                {handover.stats.byCategory.map((entry) => (
                  <li key={entry.category}>
                    {entry.label} · {entry.quantity}개
                  </li>
                ))}
              </ul>
              <h3 className="font-bold">행사별</h3>
              <ul>
                {handover.stats.byEvent.map((entry) => (
                  <li key={entry.eventId}>
                    {entry.eventName} · {entry.quantity}개
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      </div>
    </main>
  )
}
