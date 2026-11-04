import { useEffect, useRef, useState, type FormEvent } from 'react'
import { createEvent, listEvents } from '../../api/generated/afterbooth'
import type { Event } from '../../api/generated/model'
import { ApiError } from '../../api/http'

export function EventPicker({
  onSelect,
  onBusyChange,
  disabled = false,
}: {
  onSelect: (id: number | undefined) => void
  onBusyChange?: (busy: boolean) => void
  disabled?: boolean
}) {
  const [events, setEvents] = useState<Event[]>([])
  const [id, setId] = useState('')
  const [name, setName] = useState('')
  const [venue, setVenue] = useState('')
  const [deadline, setDeadline] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const attempt = useRef<{ payload: string; key: string } | undefined>(undefined)
  useEffect(() => {
    let active = true
    listEvents()
      .then((result) => {
        if (active) setEvents(result.data)
      })
      .catch(() => {
        if (active) setMessage('Unable to load events.')
      })
    return () => {
      active = false
    }
  }, [])
  async function create(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    onBusyChange?.(true)
    setMessage('')
    try {
      const data = {
        name,
        venue,
        teardownDeadline: new Date(`${deadline.slice(0, 16)}:00+09:00`).toISOString(),
      }
      const payload = JSON.stringify(data)
      if (attempt.current?.payload !== payload)
        attempt.current = { payload, key: crypto.randomUUID() }
      const result = await createEvent(data, { 'Idempotency-Key': attempt.current.key })
      if (result.status === 201) {
        setEvents((old) => [...old, result.data])
        setId(String(result.data.id))
        onSelect(result.data.id)
        setName('')
        setVenue('')
        setDeadline('')
        attempt.current = undefined
        setMessage('Event created. Add your photo now.')
      }
    } catch (error) {
      setMessage(
        error instanceof ApiError ? error.message : 'Unable to create the event. Please try again.',
      )
    } finally {
      setBusy(false)
      onBusyChange?.(false)
    }
  }
  const field = 'mt-2 block min-h-12 w-full rounded-xl border border-stone-300 bg-white px-3'
  return (
    <section
      className="mb-4 rounded-2xl border border-stone-200 bg-white p-6"
      aria-label="Event selection"
    >
      <label className="block font-bold">
        Event for registration
        <select
          className={field}
          disabled={disabled || busy}
          value={id}
          onChange={(e) => {
            setId(e.target.value)
            onSelect(Number(e.target.value) || undefined)
          }}
        >
          <option value="">Choose an event</option>
          {events.map((event) => (
            <option key={event.id} value={event.id}>
              {event.name}
            </option>
          ))}
        </select>
      </label>
      {disabled && (
        <p className="mt-2 text-sm text-slate-600">
          You cannot change events while registering a photo. Reload the page to choose another
          event.
        </p>
      )}
      <details className="mt-4">
        <summary className="cursor-pointer font-semibold text-emerald-800">
          Create a new event
        </summary>
        <form className="mt-4 space-y-4" onSubmit={(event) => void create(event)}>
          <label className="block">
            Event name
            <input
              className={field}
              maxLength={100}
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
            />
          </label>
          <label className="block">
            Venue
            <input
              className={field}
              maxLength={200}
              value={venue}
              onChange={(e) => setVenue(e.target.value)}
              required
            />
          </label>
          <label className="block">
            Teardown deadline (Asia/Seoul, KST)
            <input
              type="datetime-local"
              className={field}
              value={deadline}
              onChange={(e) => setDeadline(e.target.value)}
              required
            />
          </label>
          <button
            className="min-h-12 w-full rounded-xl bg-emerald-800 px-5 font-semibold text-white disabled:opacity-50"
            disabled={busy || disabled}
          >
            {busy ? 'Creating…' : 'Create event'}
          </button>
        </form>
      </details>
      {message && (
        <p role="status" className="mt-3 text-sm">
          {message}
        </p>
      )}
    </section>
  )
}
