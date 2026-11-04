import { useState } from 'react'
import { AiProgress } from '../../components/AiProgress'
import { AI_EXPECTED_SECONDS } from '../aiLatency'
import { useHandover } from './useHandover'
import { volumeText } from './volume'

const button =
  'min-h-12 rounded-xl bg-emerald-800 px-5 py-3 font-semibold text-white disabled:opacity-50'

export function HandoverScreen() {
  const handover = useHandover()
  const [quantity, setQuantity] = useState('')
  const reservation = handover.reservations.find((entry) => entry.id === handover.selectedId)
  const confirmed = reservation?.status === 'CONFIRMED'
  const completion = handover.recoveredReservation ?? handover.result?.reservation ?? reservation
  const completed = completion?.status === 'HANDED_OVER' && completion.handedOverQty !== undefined
  const accepted = Number(quantity)
  const validQuantity =
    Number.isInteger(accepted) && accepted >= 1 && accepted <= (reservation?.quantity ?? 0)

  return (
    <main className="min-h-dvh bg-stone-50 px-4 py-8 text-slate-900">
      <div className="mx-auto max-w-2xl space-y-8">
        <header className="page-intro">
          <p className="section-kicker">A new beginning for equipment</p>
          <a href="#/allocation" className="text-emerald-800 underline">
            Allocation board
          </a>
          <h1 className="mt-4 text-3xl font-bold">Item handover</h1>
          <p className="mt-2 text-slate-600">
            Providers: photograph the items at pickup and confirm the handed-over quantity.
          </p>
        </header>
        {handover.loading && <p role="status">Loading current reservations and stats…</p>}
        {handover.readError && (
          <div role="alert">
            <p>{handover.readError}</p>
            <button
              className="mt-2 underline"
              disabled={handover.loading || handover.busy}
              onClick={() => void handover.refresh()}
            >
              Retry loading
            </button>
          </div>
        )}
        <section
          className="space-y-4 rounded-2xl border border-stone-200 bg-white p-5 sm:p-7"
          aria-labelledby="handover-heading"
        >
          <h2 id="handover-heading" className="text-xl font-bold">
            Choose a reservation to hand over
          </h2>
          <label className="block">
            Confirmed reservations
            <select
              className="mt-2 block min-h-12 w-full rounded-xl border border-stone-300 bg-white px-3"
              value={handover.selectedId ?? ''}
              disabled={handover.busy}
              onChange={(event) => {
                handover.select(Number(event.target.value) || undefined)
                setQuantity('')
              }}
            >
              <option value="">Choose a reservation</option>
              {handover.reservations
                .filter(
                  (entry) =>
                    entry.status === 'CONFIRMED' ||
                    entry.status === 'HANDED_OVER' ||
                    entry.id === handover.selectedId,
                )
                .map((entry) => (
                  <option key={entry.id} value={entry.id}>
                    Reservation #{entry.id} · {entry.itemLabel} {entry.quantity} items
                  </option>
                ))}
            </select>
          </label>
          {!handover.loading &&
            !handover.readError &&
            !handover.reservations.some((entry) => entry.status === 'CONFIRMED') && (
              <p>No confirmed reservations to hand over.</p>
            )}
          {reservation && (
            <p>
              Reserved quantity {reservation.quantity} items · Pickup deadline (Asia/Seoul, KST){' '}
              {new Intl.DateTimeFormat('en-US', {
                timeZone: 'Asia/Seoul',
                dateStyle: 'short',
                timeStyle: 'short',
              }).format(new Date(reservation.pickupDeadline))}
            </p>
          )}
          {confirmed && !completed && (
            <label className={`block text-center ${button}`}>
              {handover.result ? 'Retake handover photo' : 'Take or upload handover photo'}
              <input
                className="sr-only"
                type="file"
                accept="image/jpeg,image/png"
                capture="environment"
                disabled={handover.busy}
                onChange={(event) => {
                  const photo = event.target.files?.[0]
                  event.target.value = ''
                  if (photo) {
                    setQuantity('')
                    void handover.upload(photo)
                  }
                }}
              />
            </label>
          )}
          {handover.photoUrl && (
            <figure>
              <div className="relative overflow-hidden rounded-xl bg-slate-100">
                <img src={handover.photoUrl} alt="Handover photo" className="block w-full" />
                {handover.photoResult?.boxes.map((box, index) => (
                  <div
                    key={index}
                    data-testid="handover-box"
                    className="pointer-events-none absolute border-2 border-emerald-600"
                    style={{
                      left: `${box.x * 100}%`,
                      top: `${box.y * 100}%`,
                      width: `${box.width * 100}%`,
                      height: `${box.height * 100}%`,
                    }}
                  />
                ))}
              </div>
              {handover.photoResult && handover.photoResult.boxes.length === 0 && (
                <figcaption className="mt-2 text-sm text-slate-600">
                  No quantity boxes to display on this photo.
                </figcaption>
              )}
            </figure>
          )}
          {handover.busy && (
            <AiProgress
              label="Muse Spark is counting items and SAM 3.1 is checking segmentation. Please wait…"
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
                  Try again
                </button>
              )}
            </div>
          )}
          {handover.result?.status === 'MISMATCH' && (
            <div className="space-y-4 rounded-xl border border-amber-300 bg-amber-50 p-5">
              <h3 className="text-lg font-bold">Check the quantity</h3>
              <p className="text-sm">The result of the last completed photo check.</p>
              <p>
                Reserved {handover.result.expectedQty} items · Counted in photo{' '}
                {handover.result.countedQty} items
              </p>
              <p>
                Even if quantities match, incomplete segmentation needs a check. Retake the photo or
                confirm the actual handed-over quantity.
              </p>
              <form
                onSubmit={(event) => {
                  event.preventDefault()
                  if (validQuantity) void handover.resolve(accepted)
                }}
              >
                <label className="block">
                  Actual handed-over quantity
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
                  Enter 1 up to the reserved quantity. Uncollected items return to stock.
                </p>
                <button
                  type="submit"
                  className={`mt-4 w-full ${button}`}
                  disabled={handover.busy || !validQuantity}
                >
                  Complete handover with this quantity
                </button>
              </form>
              <p>
                If nothing was handed over, ask the receiver to cancel their reservation from their
                allocation proposal.
              </p>
            </div>
          )}
          {completed && (
            <div role="status" className="rounded-xl bg-emerald-100 p-5">
              <h3 className="text-lg font-bold">Handover complete</h3>
              <p className="mt-2">
                {completion.itemLabel} {completion.handedOverQty} items will be reused.
              </p>
            </div>
          )}
        </section>
        <section
          className="space-y-4 rounded-2xl border border-stone-200 bg-white p-5 sm:p-7"
          aria-labelledby="stats-heading"
        >
          <h2 id="stats-heading" className="text-2xl font-bold">
            Reuse stats
          </h2>
          <label className="block">
            Filter by event
            <select
              className="mt-2 block min-h-12 w-full rounded-xl border border-stone-300 bg-white px-3"
              value={handover.eventId ?? ''}
              disabled={handover.busy}
              onChange={(event) => handover.setEventId(Number(event.target.value) || undefined)}
            >
              <option value="">All events</option>
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
                {handover.stats.totalReused} items reused in total
              </p>
              {handover.stats.totalReused > 0 && (
                <div>
                  <p className="font-semibold text-emerald-900">
                    Approximately {volumeText(handover.stats.reusedVolumeLiters)} of equipment
                    reused instead of discarded
                  </p>
                  <p className="text-sm text-slate-600">(Estimated from registered dimensions)</p>
                </div>
              )}
              {handover.stats.totalReused === 0 && <p>No completed handovers yet.</p>}
              <h3 className="font-bold">By category</h3>
              <ul>
                {handover.stats.byCategory.map((entry) => (
                  <li key={entry.category}>
                    {entry.label} · {entry.quantity} items
                  </li>
                ))}
              </ul>
              <h3 className="font-bold">By event</h3>
              <ul>
                {handover.stats.byEvent.map((entry) => (
                  <li key={entry.eventId}>
                    {entry.eventName} · {entry.quantity} items
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
