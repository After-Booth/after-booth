import { countdown } from './countdown'
import { AllocationProposal } from './AllocationProposal'
import { useAllocationBoard } from './useAllocationBoard'

const statuses = {
  HELD: 'Held',
  CONFIRMED: 'Confirmed',
  EXPIRED: 'Hold expired',
  RELEASED: 'Released',
  HANDED_OVER: 'Handed over',
}

export function AllocationBoard({
  requestId,
  organizationId,
}: {
  requestId?: number
  organizationId?: number
}) {
  const board = useAllocationBoard(requestId)
  return (
    <main className="min-h-dvh bg-stone-50 px-4 py-8 text-slate-900">
      <div className="mx-auto max-w-2xl space-y-6">
        <header className="page-intro">
          <p className="section-kicker">Connect items with their next event</p>
          <a href="#/request" className="text-emerald-800 underline">
            Request a pickup
          </a>
          <a href="#/handover" className="ml-4 text-emerald-800 underline">
            Handover and reuse stats
          </a>
          <h1 className="mt-4 text-3xl font-bold">Allocation board</h1>
          <p className="mt-2 text-slate-600">
            Stock and allocation results refresh every 3 seconds. All times are in Asia/Seoul (KST).
          </p>
        </header>
        {board.loading && <p role="status">Loading allocations…</p>}
        {board.message && (
          <p role="alert" className="text-red-700">
            {board.message}
          </p>
        )}
        {board.request && <AllocationProposal key={board.request.id} request={board.request} />}
        <section
          className="rounded-2xl border border-stone-200 bg-white p-5 sm:p-7"
          aria-labelledby="stock-heading"
        >
          <h2 id="stock-heading" className="text-xl font-bold">
            Available items
          </h2>
          {!board.loading && !board.message && !board.items.length && (
            <p className="mt-4">No items registered yet.</p>
          )}
          <ul className="mt-3 divide-y divide-stone-200">
            {board.items.map((item) => (
              <li key={item.id} className="py-5">
                <div className="flex justify-between gap-3">
                  <h3 className="font-bold">{item.label}</h3>
                  <strong className="text-emerald-800">
                    Available quantity {item.availableQty} items
                  </strong>
                </div>
                <p className="mt-2 font-semibold text-amber-900">
                  {countdown(item.pickupDeadline, board.now)}
                </p>
                <p className="mt-1 text-sm text-slate-600">
                  Pickup deadline{' '}
                  {new Intl.DateTimeFormat('en-US', {
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
        <section
          className="rounded-2xl border border-stone-200 bg-white p-5 sm:p-7"
          aria-labelledby="results-heading"
        >
          <h2 id="results-heading" className="text-xl font-bold">
            Allocation results by request
          </h2>
          {!board.loading && !board.reservations.length && (
            <p className="mt-3">No allocated requests yet.</p>
          )}
          <ul className="mt-3 space-y-3">
            {board.reservations.map((reservation) => (
              <li key={reservation.id} className="rounded-xl border border-stone-200 bg-white p-4">
                Request #{reservation.requestId} · {reservation.itemLabel} {reservation.quantity}{' '}
                items
                <span className="ml-2 font-semibold text-emerald-900">
                  {statuses[reservation.status]}
                </span>
                {reservation.organizationId === organizationId &&
                  ['HELD', 'CONFIRMED'].includes(reservation.status) && (
                    <a
                      className="block min-h-12 py-3 text-emerald-800 underline"
                      href={`#/allocation?requestId=${reservation.requestId}`}
                    >
                      Manage my reservation
                    </a>
                  )}
                {reservation.status === 'HELD' && (
                  <p className="mt-2 text-sm">
                    Held {countdown(reservation.holdExpiresAt, board.now)}
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
