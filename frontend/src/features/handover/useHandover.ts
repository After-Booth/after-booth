import { useCallback, useEffect, useRef, useState } from 'react'
import {
  cancelReservation,
  getReuseStats,
  listEvents,
  listReservations,
  resolveHandover,
  submitHandoverPhoto,
} from '../../api/generated/afterbooth'
import type { Event, HandoverResult, Reservation, ReuseStats } from '../../api/generated/model'
import { ApiError } from '../../api/http'

type Action =
  | { kind: 'photo'; reservationId: number; photo: File; key: string }
  | { kind: 'resolve'; reservationId: number; quantity: number; key: string }
  | { kind: 'cancel'; reservationId: number; key: string }

const messageOf = (error: unknown) =>
  error instanceof ApiError ? error.message : '요청을 처리하지 못했어요. 다시 시도해 주세요.'

function startsNewAction(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false
  return (
    (error.status === 503 && error.code === 'AI_UNAVAILABLE') ||
    (error.status === 400 && error.code === 'INVALID_INPUT') ||
    (error.status === 404 && error.code === 'NOT_FOUND') ||
    (error.status === 409 &&
      ['IDEMPOTENCY_CONFLICT', 'INVALID_TRANSITION', 'PICKUP_CLOSED'].includes(error.code))
  )
}

async function retryConflict<T>(action: () => Promise<T>): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      return await action()
    } catch (error) {
      if (!(error instanceof ApiError) || error.code !== 'IDEMPOTENCY_CONFLICT' || attempt === 3)
        throw error
      await new Promise((resolve) => setTimeout(resolve, 1000 * 2 ** attempt))
    }
  }
}

export function useHandover() {
  const [reservations, setReservations] = useState<Reservation[]>([])
  const [events, setEvents] = useState<Event[]>([])
  const [stats, setStats] = useState<ReuseStats>()
  const [eventId, setEventId] = useState<number>()
  const [selectedId, setSelectedId] = useState<number>()
  const [result, setResult] = useState<HandoverResult>()
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [readError, setReadError] = useState('')
  const [cancelled, setCancelled] = useState(false)
  const [retryAction, setRetryAction] = useState<Action>()
  const lock = useRef(false)
  const generation = useRef(0)

  const refresh = useCallback(async () => {
    const current = ++generation.current
    try {
      const [holds, eventList, totals] = await Promise.all([
        listReservations(),
        listEvents(),
        getReuseStats(eventId ? { eventId } : undefined),
      ])
      if (current !== generation.current) return
      setReservations(holds.data)
      setEvents(eventList.data)
      if (totals.status === 200) setStats(totals.data)
      setReadError('')
    } catch (error) {
      if (current === generation.current) setReadError(messageOf(error))
    } finally {
      if (current === generation.current) setLoading(false)
    }
  }, [eventId])

  useEffect(() => {
    const timer = setTimeout(() => void refresh(), 0)
    return () => clearTimeout(timer)
  }, [refresh])

  const run = async (action: Action) => {
    if (lock.current) return
    lock.current = true
    setBusy(true)
    setMessage('')
    setRetryAction(undefined)
    try {
      const headers = { 'Idempotency-Key': action.key }
      if (action.kind === 'cancel') {
        await retryConflict(() => cancelReservation(action.reservationId, headers))
        setCancelled(true)
        setResult(undefined)
      } else {
        const response = await retryConflict(() =>
          action.kind === 'photo'
            ? submitHandoverPhoto(action.reservationId, { photo: action.photo }, headers)
            : resolveHandover(action.reservationId, { acceptedQty: action.quantity }, headers),
        )
        if (response.status === 200) setResult(response.data)
      }
      setLoading(true)
      await refresh()
    } catch (error) {
      setMessage(messageOf(error))
      setRetryAction(startsNewAction(error) ? { ...action, key: crypto.randomUUID() } : action)
    } finally {
      lock.current = false
      setBusy(false)
    }
  }

  const select = (id: number | undefined) => {
    if (lock.current) return
    setSelectedId(id)
    setResult(undefined)
    setMessage('')
    setRetryAction(undefined)
    setCancelled(false)
  }

  return {
    reservations,
    events,
    stats,
    eventId,
    setEventId: (id: number | undefined) => {
      if (lock.current || id === eventId) return
      setStats(undefined)
      setLoading(true)
      setEventId(id)
    },
    selectedId,
    select,
    result,
    loading,
    busy,
    message,
    readError,
    cancelled,
    refresh: () => {
      setLoading(true)
      return refresh()
    },
    canRetry: Boolean(retryAction),
    retry: () => retryAction && run(retryAction),
    upload: (photo: File) =>
      selectedId &&
      run({ kind: 'photo', reservationId: selectedId, photo, key: crypto.randomUUID() }),
    resolve: (quantity: number) =>
      selectedId &&
      run({ kind: 'resolve', reservationId: selectedId, quantity, key: crypto.randomUUID() }),
    cancel: () =>
      selectedId && run({ kind: 'cancel', reservationId: selectedId, key: crypto.randomUUID() }),
  }
}
