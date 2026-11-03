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
        if (active) setMessage('행사 목록을 불러오지 못했어요.')
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
      const data = { name, venue, teardownDeadline: new Date(deadline).toISOString() }
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
        setMessage('행사를 만들었어요. 이제 사진을 등록해 주세요.')
      }
    } catch (error) {
      setMessage(
        error instanceof ApiError ? error.message : '행사를 만들지 못했어요. 다시 시도해 주세요.',
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
      aria-label="행사 선택"
    >
      <label className="block font-bold">
        등록할 행사
        <select
          className={field}
          disabled={disabled || busy}
          value={id}
          onChange={(e) => {
            setId(e.target.value)
            onSelect(Number(e.target.value) || undefined)
          }}
        >
          <option value="">행사를 선택해 주세요</option>
          {events.map((event) => (
            <option key={event.id} value={event.id}>
              {event.name}
            </option>
          ))}
        </select>
      </label>
      {disabled && (
        <p className="mt-2 text-sm text-slate-600">
          사진 등록 중에는 행사를 바꿀 수 없어요. 다른 행사를 선택하려면 페이지를 새로고침해 주세요.
        </p>
      )}
      <details className="mt-4">
        <summary className="cursor-pointer font-semibold text-emerald-800">새 행사 만들기</summary>
        <form className="mt-4 space-y-4" onSubmit={(event) => void create(event)}>
          <label className="block">
            행사 이름
            <input
              className={field}
              maxLength={100}
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
            />
          </label>
          <label className="block">
            장소
            <input
              className={field}
              maxLength={200}
              value={venue}
              onChange={(e) => setVenue(e.target.value)}
              required
            />
          </label>
          <label className="block">
            철수 마감 시간
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
            {busy ? '만드는 중…' : '행사 만들기'}
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
