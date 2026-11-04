import { useState } from 'react'
import { AiProgress } from '../../components/AiProgress'
import { AI_EXPECTED_SECONDS } from '../aiLatency'
import { AllocationProposal } from '../allocation/AllocationProposal'
import type { ItemCategory, VehicleType } from '../../api/generated/model'
import { seoulInput, useRequest } from './useRequest'

const categories: Record<ItemCategory, string> = {
  DISPLAY_RACK: 'Display rack',
  STORAGE_BIN: 'Storage bin',
  FOLDING_TABLE: 'Folding table',
}
const vehicles: Record<VehicleType, string> = {
  SEDAN: 'Sedan',
  SUV: 'SUV',
  VAN: 'Van',
  TRUCK_1T: '1-ton truck',
}
const inputClass =
  'mt-2 block min-h-12 w-full rounded-xl border border-slate-300 bg-white px-3 py-2 text-slate-900 focus:outline-2 focus:outline-emerald-700 disabled:opacity-50'
const buttonClass =
  'min-h-12 rounded-xl bg-emerald-800 px-5 py-3 font-semibold text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700 disabled:opacity-40'

export function RequestScreen({ organizationId }: { organizationId?: number }) {
  const request = useRequest(organizationId)
  const [text, setText] = useState('')
  const { status, fields, interpretation, saved } = request
  const busy = ['starting', 'recording', 'interpreting', 'saving'].includes(status)
  const editable = !!interpretation && status !== 'empty' && status !== 'saved'
  const missing = (field: string) =>
    interpretation?.missingFields.some((name) => name === field) ?? false
  const mark = (field: string) =>
    missing(field) ? (
      <span id={`${field}-hint`} className="ml-2 text-sm font-medium text-amber-800">
        Needs review · Enter manually
      </span>
    ) : null
  const attributes = (field: string) => ({
    'aria-describedby': missing(field) ? `${field}-hint` : undefined,
    className: `${inputClass} ${missing(field) ? 'border-amber-600 bg-amber-50' : ''}`,
  })

  return (
    <main className="min-h-dvh bg-stone-50 px-4 py-8 text-slate-900 sm:py-12">
      <div className="mx-auto max-w-xl space-y-7">
        <header className="page-intro">
          <p className="section-kicker">Pick up equipment</p>
          <h1 className="mt-3 text-3xl font-bold tracking-tight">Equipment for your next event</h1>
          <p className="mt-3 leading-relaxed text-slate-600">
            Tell us what you need and which vehicle you will bring.
            <br />
            Review the details before saving your request.
          </p>
        </header>

        <section className="rounded-2xl border border-stone-200 bg-white p-5">
          <label htmlFor="organization" className="font-semibold">
            1. Receiving organization
          </label>
          <select
            id="organization"
            className={inputClass}
            value={request.organizationId}
            disabled={
              !!organizationId || busy || status === 'saved' || request.catalogStatus !== 'ready'
            }
            onChange={(event) => request.setOrganizationId(event.target.value)}
          >
            <option value="">Choose an organization</option>
            {request.organizations.map((organization) => (
              <option key={organization.id} value={organization.id}>
                {organization.name}
              </option>
            ))}
          </select>
          <p className="mt-2 text-sm text-slate-500">
            {organizationId
              ? 'Requests use your account organization.'
              : 'Voice input cannot change your organization.'}
          </p>
          {request.catalogStatus === 'loading' && <p role="status">Loading organizations…</p>}
          {request.catalogStatus === 'error' && (
            <p role="alert" className="mt-2 text-red-700">
              Unable to load organizations. Please reload the page.
            </p>
          )}
          {request.catalogStatus === 'ready' && !request.organizations.length && (
            <p className="mt-2 text-amber-800">No organizations available yet.</p>
          )}
        </section>

        <section
          aria-labelledby="describe-heading"
          className="rounded-2xl border border-stone-200 bg-white p-5"
        >
          <h2 id="describe-heading" className="font-semibold">
            2. What equipment do you need?
          </h2>
          <p className="mt-2 text-sm leading-relaxed text-slate-600">
            Example: I need three display racks for a weekend event. I will bring a sedan.
          </p>
          <button
            type="button"
            aria-pressed={status === 'recording'}
            className={`${buttonClass} mt-5 w-full ${status === 'recording' ? 'bg-red-700' : ''}`}
            disabled={(busy && status !== 'recording') || status === 'saved'}
            onClick={() => void request.toggleRecording()}
          >
            {status === 'recording'
              ? 'Stop and interpret'
              : status === 'starting'
                ? 'Connecting microphone…'
                : 'Speak your request'}
          </button>
          <p className="mt-2 text-center text-sm text-slate-500">
            Press once to start and again to stop · Up to 60 seconds
          </p>
          <label className="mt-4 block rounded-xl border border-emerald-800 p-3 text-center font-semibold text-emerald-900 has-disabled:opacity-40">
            Upload recording (WAV)
            <input
              type="file"
              accept="audio/wav,.wav"
              className="sr-only"
              aria-describedby="audio-file-hint"
              disabled={busy || status === 'saved'}
              onChange={(event) => {
                const file = event.currentTarget.files?.[0]
                event.currentTarget.value = ''
                if (file) request.interpretAudio(file)
              }}
            />
          </label>
          <p id="audio-file-hint" className="mt-2 text-center text-sm text-slate-500">
            Upload a 16 kHz mono WAV file · Up to 60 seconds
          </p>
          {status === 'recording' && (
            <p role="status" className="mt-3 text-center font-semibold text-red-700">
              Recording {request.elapsed} / 60s
            </p>
          )}
          <form
            className="mt-6 border-t border-stone-200 pt-5"
            onSubmit={(event) => {
              event.preventDefault()
              request.interpretText(text)
            }}
          >
            <label htmlFor="request-text" className="text-sm font-semibold">
              Type your request
            </label>
            <textarea
              id="request-text"
              className={inputClass}
              rows={3}
              maxLength={500}
              value={text}
              disabled={busy || status === 'saved'}
              onChange={(event) => setText(event.target.value)}
              placeholder="Describe items, quantity, vehicle, pickup time and purpose"
            />
            <p className="mt-1 text-right text-xs text-slate-500">{text.length} / 500 characters</p>
            <button
              className={`${buttonClass} mt-3 w-full`}
              disabled={busy || !text.trim() || status === 'saved'}
            >
              Interpret request
            </button>
          </form>
        </section>

        <div aria-live="polite">
          {status === 'interpreting' && (
            <AiProgress
              label={
                request.interpretationSource === 'voice'
                  ? 'Muse Voice Transcribe is transcribing audio and Muse Spark is structuring your requirements…'
                  : 'Muse Spark is structuring your requirements…'
              }
              expectedSeconds={
                request.interpretationSource === 'voice'
                  ? AI_EXPECTED_SECONDS.voiceRequest
                  : AI_EXPECTED_SECONDS.textRequest
              }
            />
          )}
          {status === 'saving' && <p role="status">Saving request…</p>}
          {status === 'idle' && (
            <p className="text-center text-sm text-slate-500">
              No request yet. Start with voice or text.
            </p>
          )}
          {status === 'empty' && (
            <p className="rounded-xl bg-amber-50 p-5 text-amber-900">
              No speech detected. Speak again or type your request.
            </p>
          )}
        </div>
        {request.message && (
          <p role="alert" className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-800">
            {request.message}
          </p>
        )}

        {editable && (
          <section
            className="rounded-2xl border border-emerald-200 bg-white p-5"
            aria-labelledby="review-heading"
          >
            <h2 id="review-heading" className="text-xl font-bold">
              3. Review your requirements
            </h2>
            <p className="mt-2 text-sm text-slate-600">
              You can edit every field. Review before saving.
            </p>
            <div className="my-5 rounded-xl bg-stone-50 p-4">
              <h3 className="text-xs font-semibold text-slate-500">Original input</h3>
              <p className="mt-2 whitespace-pre-wrap">
                {interpretation.transcript || 'No speech recognized.'}
              </p>
            </div>
            <form
              className="space-y-5"
              onSubmit={(event) => {
                event.preventDefault()
                void request.submit()
              }}
            >
              <fieldset disabled={busy} className="space-y-5">
                <div>
                  <label htmlFor="category">Item category{mark('category')}</label>
                  <select
                    id="category"
                    required
                    {...attributes('category')}
                    value={fields.category}
                    onChange={(event) =>
                      request.setFields({ ...fields, category: event.target.value as ItemCategory })
                    }
                  >
                    <option value="">Choose an option</option>
                    {Object.entries(categories).map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label htmlFor="quantity">Quantity{mark('quantity')}</label>
                  <input
                    id="quantity"
                    type="number"
                    required
                    min={1}
                    step={1}
                    {...attributes('quantity')}
                    value={Number.isNaN(fields.quantity) ? '' : fields.quantity}
                    onChange={(event) =>
                      request.setFields({ ...fields, quantity: event.target.valueAsNumber })
                    }
                  />
                </div>
                <div>
                  <label htmlFor="vehicleType">Pickup vehicle{mark('vehicleType')}</label>
                  <select
                    id="vehicleType"
                    required
                    {...attributes('vehicleType')}
                    value={fields.vehicleType}
                    onChange={(event) =>
                      request.setFields({
                        ...fields,
                        vehicleType: event.target.value as VehicleType,
                      })
                    }
                  >
                    <option value="">Choose an option</option>
                    {Object.entries(vehicles).map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label htmlFor="pickupDeadline">
                    Pickup time (Asia/Seoul, KST){mark('pickupDeadline')}
                  </label>
                  <input
                    id="pickupDeadline"
                    type="datetime-local"
                    {...attributes('pickupDeadline')}
                    value={fields.pickupDeadline}
                    onChange={(event) =>
                      request.setFields({ ...fields, pickupDeadline: event.target.value })
                    }
                  />
                </div>
                <div>
                  <label htmlFor="purpose">Purpose{mark('purpose')}</label>
                  <input
                    id="purpose"
                    {...attributes('purpose')}
                    value={fields.purpose}
                    onChange={(event) =>
                      request.setFields({ ...fields, purpose: event.target.value })
                    }
                  />
                </div>
                <button
                  className={`${buttonClass} w-full`}
                  disabled={
                    !request.organizationId ||
                    !fields.category ||
                    !fields.vehicleType ||
                    !Number.isInteger(fields.quantity) ||
                    fields.quantity < 1
                  }
                >
                  Save pickup request
                </button>
              </fieldset>
            </form>
          </section>
        )}

        {saved && (
          <section
            role="status"
            className="rounded-2xl border border-emerald-300 bg-emerald-50 p-6"
          >
            <h2 className="text-xl font-bold text-emerald-900">Pickup request saved</h2>
            <p className="mt-2 text-sm">
              Request ID #{saved.id} ·{' '}
              {
                request.organizations.find(
                  (organization) => organization.id === saved.organizationId,
                )?.name
              }
            </p>
            <dl className="mt-4 grid grid-cols-[auto_1fr] gap-x-5 gap-y-3">
              <dt>Item</dt>
              <dd>
                {categories[saved.category]} {saved.quantity} items
              </dd>
              <dt>Vehicle</dt>
              <dd>{vehicles[saved.vehicleType]}</dd>
              <dt>Pickup time</dt>
              <dd>
                {saved.pickupDeadline
                  ? `${seoulInput(saved.pickupDeadline).replace('T', ' ')} (Asia/Seoul, KST)`
                  : 'Not specified'}
              </dd>
              <dt>Purpose</dt>
              <dd>{saved.purpose || 'Not provided'}</dd>
            </dl>
            {saved.transcript && (
              <p className="mt-4 whitespace-pre-wrap text-sm text-slate-600">
                Original input: {saved.transcript}
              </p>
            )}
          </section>
        )}
        {saved && <AllocationProposal key={saved.id} request={saved} />}
      </div>
    </main>
  )
}
