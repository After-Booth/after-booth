import type { Condition, DraftCategory, IntakeStage } from '../../api/generated/model'
import { AiProgress } from '../../components/AiProgress'
import { AI_EXPECTED_SECONDS } from '../aiLatency'
import { CATEGORIES, CATEGORY_COLOR, CATEGORY_LABEL } from './categories'
import { problemOf, useIntake, type EditableLine } from './useIntake'
import { useState } from 'react'
import { EventPicker } from './EventPicker'

const STAGE_TEXT: Record<IntakeStage, string> = {
  ESTIMATE: 'Muse Spark is counting items in the photo…',
  SEGMENT: 'SAM 3.1 is checking each item outline…',
}

const CONDITION_LABEL: Record<Condition, string> = { GOOD: 'Good', FAIR: 'Fair', POOR: 'Poor' }

const SIZE_FIELDS = [
  ['width', 'Width'],
  ['depth', 'Depth'],
  ['height', 'Height'],
] as const

const fieldClass = 'mt-1 h-12 w-full rounded-lg border border-slate-300 bg-white px-3 text-base'

function countsByCategory(lines: EditableLine[]) {
  const totals = new Map<DraftCategory, number>()
  for (const line of lines) {
    const qty = Number(line.quantity)
    if (Number.isInteger(qty) && qty > 0) {
      totals.set(line.category, (totals.get(line.category) ?? 0) + qty)
    }
  }
  return [...totals]
}

export function IntakeScreen({ showEvents = false }: { showEvents?: boolean }) {
  const [eventId, setEventId] = useState<number>()
  const [creatingEvent, setCreatingEvent] = useState(false)
  const {
    photoUrl,
    draft,
    lines,
    uploading,
    confirming,
    error,
    items,
    canConfirm,
    upload,
    editLine,
    confirm,
  } = useIntake()
  const processing = draft?.status === 'PROCESSING'
  const busy = uploading || processing || confirming
  const ready = draft?.status === 'READY' && !items

  return (
    <main className="mx-auto max-w-2xl px-5 py-8 text-slate-900 sm:py-12">
      <div className="intake-intro">
        <p className="section-kicker">For what comes after the event</p>
        <h1 className="mt-3 text-3xl font-bold tracking-tight sm:text-4xl">
          Give leftover equipment
          <br />a new purpose
        </h1>
        <p className="mt-4 leading-relaxed text-blue-50">
          Photograph your booth to count equipment automatically.
          <br />
          Review and register it for the next event.
        </p>
      </div>
      {showEvents && (
        <EventPicker
          onSelect={setEventId}
          onBusyChange={setCreatingEvent}
          disabled={busy || !!photoUrl}
        />
      )}
      <section className="capture-panel" aria-label="Register leftover equipment">
        <div className="capture-heading">
          <span className="capture-icon" aria-hidden="true">
            ▧
          </span>
          <div>
            <h2 className="text-lg font-bold">Register leftover equipment</h2>
            <p className="mt-1 text-sm text-slate-600">Start with a single photo</p>
          </div>
        </div>

        <label
          className={`mt-5 block rounded-xl py-4 text-center text-lg font-semibold text-white ${
            busy || creatingEvent || (showEvents && !eventId) ? 'bg-slate-400' : 'bg-emerald-800'
          }`}
        >
          {photoUrl ? 'Retake photo' : 'Take booth photo'}
          <input
            type="file"
            accept="image/jpeg,image/png"
            capture="environment"
            className="sr-only"
            disabled={busy || creatingEvent || (showEvents && !eventId)}
            onChange={(e) => {
              const photo = e.currentTarget.files?.[0]
              e.currentTarget.value = ''
              if (photo) void upload(photo, eventId)
            }}
          />
        </label>
        {showEvents && !eventId && (
          <p className="mt-2 text-sm text-slate-600">Choose or create an event first.</p>
        )}

        {photoUrl && (
          <div className="relative mt-4 overflow-hidden rounded-xl bg-slate-100">
            <img src={photoUrl} alt="Booth photo" className="block w-full" />
            {ready &&
              lines.flatMap((line) =>
                line.boxes.map((box, i) => (
                  <div
                    key={`${line.lineId}-${i}`}
                    data-testid="box"
                    className={`absolute border-2 ${CATEGORY_COLOR[line.category].box}`}
                    style={{
                      left: `${box.x * 100}%`,
                      top: `${box.y * 100}%`,
                      width: `${box.width * 100}%`,
                      height: `${box.height * 100}%`,
                    }}
                  />
                )),
              )}
          </div>
        )}

        {(uploading || processing) && (
          <div className="mt-4">
            <AiProgress
              label={uploading ? 'Uploading photo…' : STAGE_TEXT[draft?.stage ?? 'ESTIMATE']}
              expectedSeconds={AI_EXPECTED_SECONDS.intake}
            />
          </div>
        )}
        {error && (
          <p role="alert" className="mt-4 rounded-lg bg-red-50 p-3 text-red-700">
            {error}
          </p>
        )}
        {draft?.status === 'FAILED' && (
          <p role="alert" className="mt-4 rounded-lg bg-red-50 p-3 text-red-700">
            {draft.failureMessage ?? 'Unable to analyze the photo. Please retake it.'}
          </p>
        )}

        {ready && lines.length === 0 && (
          <p className="mt-4 rounded-lg bg-slate-100 p-3">
            No items found. Retake a photo of the whole booth.
          </p>
        )}

        {ready && lines.length > 0 && (
          <section className="mt-4">
            <ul className="flex flex-wrap gap-2" aria-label="Quantity by category">
              {countsByCategory(lines).map(([category, total]) => (
                <li
                  key={category}
                  className="flex items-center gap-2 rounded-full bg-slate-100 px-3 py-1"
                >
                  <span className={`size-3 rounded-full ${CATEGORY_COLOR[category].dot}`} />
                  {CATEGORY_LABEL[category]} {total} items
                </li>
              ))}
            </ul>

            <ul className="mt-4 space-y-3">
              {lines.map((line) => {
                const problem = problemOf(line)
                return (
                  <li
                    key={line.lineId}
                    className={`rounded-xl border p-3 ${
                      line.needsReview ? 'border-amber-500 bg-amber-50' : 'border-slate-200'
                    }`}
                  >
                    <div className="flex items-center justify-between">
                      <span className="flex items-center gap-2 font-semibold">
                        <span
                          className={`size-3 rounded-full ${CATEGORY_COLOR[line.category].dot}`}
                        />
                        {line.originalLabel}
                      </span>
                      {line.needsReview && (
                        <span className="rounded bg-amber-500 px-2 py-0.5 text-sm font-semibold text-white">
                          Needs review
                        </span>
                      )}
                    </div>

                    <div className="mt-2 grid grid-cols-2 gap-3">
                      <label className="text-sm">
                        {line.originalLabel} Category
                        <select
                          className={fieldClass}
                          value={line.category}
                          onChange={(e) =>
                            editLine(line.lineId, { category: e.target.value as DraftCategory })
                          }
                        >
                          {CATEGORIES.map((c) => (
                            <option key={c} value={c}>
                              {CATEGORY_LABEL[c]}
                            </option>
                          ))}
                        </select>
                      </label>
                      <label className="text-sm">
                        {line.originalLabel} Quantity
                        <input
                          type="number"
                          inputMode="numeric"
                          min={1}
                          className={fieldClass}
                          value={line.quantity}
                          onChange={(e) => editLine(line.lineId, { quantity: e.target.value })}
                        />
                      </label>
                    </div>

                    <div className="mt-3 grid grid-cols-3 gap-3">
                      {SIZE_FIELDS.map(([key, name]) => (
                        <label key={key} className="text-sm">
                          {line.originalLabel} {name}(cm)
                          <input
                            type="number"
                            inputMode="numeric"
                            min={1}
                            className={fieldClass}
                            value={line[key]}
                            onChange={(e) => editLine(line.lineId, { [key]: e.target.value })}
                          />
                        </label>
                      ))}
                    </div>

                    <label className="mt-3 block text-sm">
                      {line.originalLabel} Status
                      <select
                        className={fieldClass}
                        value={line.condition}
                        onChange={(e) =>
                          editLine(line.lineId, { condition: e.target.value as Condition })
                        }
                      >
                        {(Object.keys(CONDITION_LABEL) as Condition[]).map((c) => (
                          <option key={c} value={c}>
                            {CONDITION_LABEL[c]}
                          </option>
                        ))}
                      </select>
                    </label>

                    {problem && (
                      <p className="mt-2 text-sm font-semibold text-red-700">{problem}</p>
                    )}
                  </li>
                )
              })}
            </ul>

            <button
              className="mt-5 h-14 w-full rounded-xl bg-emerald-600 text-lg font-semibold text-white disabled:bg-slate-300"
              disabled={!canConfirm || confirming}
              onClick={() => void confirm()}
            >
              {confirming ? 'Registering…' : 'Register inventory'}
            </button>
          </section>
        )}

        {items && (
          <section className="mt-4 rounded-xl bg-emerald-50 p-4">
            <p className="font-semibold text-emerald-800">Inventory registered.</p>
            <ul className="mt-2 space-y-1">
              {items.map((item) => (
                <li key={item.id}>
                  {item.label} {item.totalQty} items
                </li>
              ))}
            </ul>
          </section>
        )}
      </section>
    </main>
  )
}
