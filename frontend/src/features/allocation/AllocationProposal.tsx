import type { PickupRequest, ToolCallName } from '../../api/generated/model'
import { AiProgress } from '../../components/AiProgress'
import { AI_EXPECTED_SECONDS } from '../aiLatency'
import { useAllocation } from './useAllocation'

const button =
  'min-h-12 rounded-xl bg-emerald-800 px-5 py-3 font-semibold text-white disabled:opacity-40'

const TOOL_GLOSS: Record<ToolCallName, string> = {
  searchCandidates: 'Search inventory candidates',
  checkVehicleFit: 'Check vehicle fit',
  holdQuantity: 'Hold quantity',
}

export function AllocationProposal({ request }: { request: PickupRequest }) {
  return <RequestAllocationProposal key={request.id} request={request} />
}

function RequestAllocationProposal({ request }: { request: PickupRequest }) {
  const allocation = useAllocation(request)
  const held = allocation.reservations.filter((r) => ['HELD', 'CONFIRMED'].includes(r.status))
  const quantity = held.reduce((sum, reservation) => sum + reservation.quantity, 0)
  const leadAlternative =
    allocation.result?.status === 'NO_MATCH' && quantity === 0
      ? allocation.result.alternatives[0]
      : undefined
  return (
    <section
      aria-labelledby="allocation-heading"
      className="space-y-4 rounded-2xl border border-emerald-300 bg-white p-5"
    >
      <h2 id="allocation-heading" className="text-xl font-bold">
        Allocation proposal
      </h2>
      <p className="text-sm text-slate-600">
        Check vehicle fit and pickup time, then hold your items.
      </p>
      {allocation.loading && <p role="status">Checking existing reservations…</p>}
      <button
        className={button}
        disabled={allocation.pending || allocation.loading || quantity > 0}
        onClick={() => void allocation.execute()}
      >
        {allocation.pending ? 'Checking allocation…' : 'Get allocation proposal'}
      </button>
      {allocation.pending && (
        <AiProgress
          label="Muse Spark is checking inventory and vehicle constraints…"
          expectedSeconds={AI_EXPECTED_SECONDS.allocation}
        />
      )}
      {allocation.message && (
        <p role="alert" className="text-red-700">
          {allocation.message}
        </p>
      )}
      {quantity > 0 && (
        <p role="status" className="font-bold text-emerald-900">
          {quantity} of {request.quantity} items held
          {quantity < request.quantity ? ' · Partial allocation' : ' · Allocation successful'}
        </p>
      )}
      {allocation.result && (
        <>
          {allocation.result.status === 'NO_MATCH' && quantity === 0 && (
            <p className="font-semibold text-amber-800">No matching items could be held.</p>
          )}
          {leadAlternative && (
            <p className="text-lg font-bold text-emerald-900">
              Alternative: {leadAlternative.item.label}{' '}
              {Math.min(leadAlternative.quantity, request.quantity - quantity)} items available
            </p>
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
            <details open>
              <summary className="cursor-pointer text-sm font-semibold">
                Server tools called by Muse Spark
              </summary>
              <ol className="mt-2 space-y-3 text-sm">
                {allocation.result.toolCalls.map((call, index) => (
                  <li key={index} className="flex flex-wrap items-center gap-2">
                    <code className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-xs">
                      {call.name}
                    </code>
                    <span className="font-semibold">{TOOL_GLOSS[call.name]}</span>
                    <span
                      className={`rounded px-2 py-0.5 text-xs font-semibold ${
                        call.outcome === 'REJECTED'
                          ? 'bg-amber-100 text-amber-900'
                          : 'bg-emerald-100 text-emerald-900'
                      }`}
                    >
                      {call.outcome === 'REJECTED' ? 'Rejected' : 'Passed'}
                    </span>
                    <span className="w-full text-slate-700">
                      {call.summary ?? 'Constraints checked.'}
                    </span>
                  </li>
                ))}
              </ol>
            </details>
          )}
          {quantity < request.quantity && (
            <div className="space-y-3">
              <h3 className="font-semibold">Choose an alternative</h3>
              {allocation.result.alternatives.length === 0 ? (
                <p className="text-sm text-slate-600">
                  No alternatives available now. Check remaining stock on the allocation board.
                </p>
              ) : (
                allocation.result.alternatives.map((alternative) => (
                  <article key={alternative.item.id} className="border-t border-stone-200 pt-3">
                    <h4 className="font-semibold">
                      {alternative.item.label}{' '}
                      {Math.min(alternative.quantity, request.quantity - quantity)} items
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
                      {alternative.item.label} Choose alternative
                    </button>
                  </article>
                ))
              )}
            </div>
          )}
        </>
      )}
      {held.length > 0 && (
        <ul className="space-y-3 text-sm">
          {held.map((reservation) => (
            <li key={reservation.id} className="flex flex-wrap items-center gap-3">
              <span>
                {reservation.itemLabel} {reservation.quantity} items ·{' '}
                {reservation.status === 'HELD' ? 'Held' : 'Confirmed'}
              </span>
              <button
                className="min-h-12 underline"
                disabled={allocation.pending}
                onClick={() => void allocation.cancel(reservation)}
              >
                {reservation.itemLabel} Cancel reservation
              </button>
              {reservation.status === 'HELD' && (
                <button
                  className={button}
                  disabled={allocation.pending}
                  onClick={() => void allocation.confirm(reservation)}
                >
                  {reservation.itemLabel} Confirm reservation
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {held.some((reservation) => reservation.status === 'HELD') && (
        <p className="text-sm text-slate-600">
          Holds expire automatically. Confirm your reservation so the provider can complete the
          handover.
        </p>
      )}
      {allocation.manualSelection && (
        <section className="space-y-3" aria-labelledby="current-stock-heading">
          <h3 id="current-stock-heading" className="font-semibold">
            Additional available stock
          </h3>
          <p className="text-sm text-slate-600">
            The server rechecks vehicle size, pickup deadline and available quantity when you
            choose.
          </p>
          {allocation.stockLoading && <p role="status">Checking current stock…</p>}
          {allocation.stockMessage && <p role="alert">{allocation.stockMessage}</p>}
          {!allocation.stockLoading &&
            !allocation.stockMessage &&
            allocation.currentStock.length === 0 && (
              <p className="text-sm">No additional stock available now.</p>
            )}
          {allocation.currentStock
            .filter((item) => item.availableQty > 0)
            .map((item) => {
              const remaining = Math.min(item.availableQty, request.quantity - quantity)
              return (
                <article key={item.id} className="border-t border-stone-200 pt-3">
                  <h4 className="font-semibold">
                    {item.label} {remaining} items
                  </h4>
                  <button
                    className={`${button} mt-2`}
                    disabled={allocation.pending}
                    onClick={() =>
                      void allocation.execute({
                        item,
                        quantity: remaining,
                        reason: 'The server checks vehicle and pickup constraints when selected.',
                      })
                    }
                  >
                    {item.label} Choose additional stock
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
        Check remaining stock on the allocation board
      </a>
    </section>
  )
}
