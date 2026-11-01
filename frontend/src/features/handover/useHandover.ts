import { useCallback, useEffect, useRef, useState } from 'react'
import {
  cancelReservation,
  getReservation,
  getReuseStats,
  listEvents,
  listReservations,
  resolveHandover,
  submitHandoverPhoto,
} from '../../api/generated/afterbooth'
import type { Event, HandoverResult, Reservation, ReuseStats } from '../../api/generated/model'
import { ApiError } from '../../api/http'

type Action = (
  | { kind: 'photo'; reservationId: number; photo: File; key: string }
  | { kind: 'resolve'; reservationId: number; quantity: number; key: string }
  | { kind: 'cancel'; reservationId: number; key: string }
) & { reconcile?: boolean }

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

async function retryConflict<T>(action: () => Promise<T>, signal: AbortSignal): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    signal.throwIfAborted()
    try {
      return await action()
    } catch (error) {
      signal.throwIfAborted()
      if (!(error instanceof ApiError) || error.code !== 'IDEMPOTENCY_CONFLICT' || attempt === 3)
        throw error
      await new Promise<void>((resolve, reject) => {
        const abort = () => {
          clearTimeout(timer)
          reject(signal.reason)
        }
        const timer = setTimeout(
          () => {
            signal.removeEventListener('abort', abort)
            resolve()
          },
          1000 * 2 ** attempt,
        )
        signal.addEventListener('abort', abort, { once: true })
      })
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
  const [photoResult, setPhotoResult] = useState<HandoverResult>()
  const [recoveredReservation, setRecoveredReservation] = useState<Reservation>()
  const [photo, setPhoto] = useState<File>()
  const [photoUrl, setPhotoUrl] = useState<string>()
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [readError, setReadError] = useState('')
  const [cancelled, setCancelled] = useState(false)
  const [retryAction, setRetryAction] = useState<Action>()
  const lock = useRef(false)
  const generation = useRef(0)
  const lifetime = useRef<AbortController>(undefined)

  useEffect(() => {
    const controller = new AbortController()
    lifetime.current = controller
    return () => {
      controller.abort()
      generation.current++
    }
  }, [])

  useEffect(() => {
    const url = photo ? URL.createObjectURL(photo) : undefined
    setPhotoUrl(url)
    return () => {
      if (url) URL.revokeObjectURL(url)
    }
  }, [photo])

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
    const signal = lifetime.current?.signal
    if (lock.current || !signal || signal.aborted) return
    lock.current = true
    setBusy(true)
    setMessage('')
    setRetryAction(undefined)
    const reconcile = async () => {
      const response = await getReservation(action.reservationId)
      signal.throwIfAborted()
      if (response.status !== 200) return undefined
      const current = response.data
      setReservations((entries) =>
        entries.map((entry) => (entry.id === current.id ? current : entry)),
      )
      if (current.status !== 'CONFIRMED') setResult(undefined)
      if (current.status === 'HANDED_OVER' && current.handedOverQty !== undefined) {
        setRecoveredReservation(current)
      }
      return current
    }
    const isCompleted = (current: Reservation | undefined) =>
      current?.status === 'HANDED_OVER' && current.handedOverQty !== undefined
    let mutationStarted = false
    try {
      if (action.reconcile && action.kind !== 'cancel') {
        const current = await reconcile()
        if (isCompleted(current)) {
          setLoading(true)
          await refresh()
          return
        }
        if (current?.status !== 'CONFIRMED') {
          setMessage('현재 예약 상태에서는 인계할 수 없어요. 예약 상태를 확인해 주세요.')
          return
        }
      }
      const headers = { 'Idempotency-Key': action.key }
      mutationStarted = true
      if (action.kind === 'cancel') {
        await retryConflict(() => cancelReservation(action.reservationId, headers), signal)
        signal.throwIfAborted()
        setCancelled(true)
        setResult(undefined)
        setPhoto(undefined)
        setPhotoResult(undefined)
      } else {
        const response = await retryConflict(
          () =>
            action.kind === 'photo'
              ? submitHandoverPhoto(action.reservationId, { photo: action.photo }, headers)
              : resolveHandover(action.reservationId, { acceptedQty: action.quantity }, headers),
          signal,
        )
        signal.throwIfAborted()
        if (response.status === 200) {
          setResult(response.data)
          if (action.kind === 'photo') setPhotoResult(response.data)
        }
      }
      setLoading(true)
      await refresh()
    } catch (error) {
      if (signal.aborted) return
      const needsReconciliation =
        action.kind !== 'cancel' &&
        error instanceof ApiError &&
        ['IDEMPOTENCY_CONFLICT', 'INVALID_TRANSITION'].includes(error.code)
      let current: Reservation | undefined
      if (needsReconciliation) {
        try {
          current = await reconcile()
        } catch {
          // Keep the original action retryable if the recovery read also fails.
        }
        if (signal.aborted) return
        if (isCompleted(current)) {
          setLoading(true)
          await refresh()
          return
        }
      }
      setMessage(messageOf(error))
      if (!current || current.status === 'CONFIRMED') {
        setRetryAction({
          ...action,
          key: startsNewAction(error) ? crypto.randomUUID() : action.key,
          reconcile: (!mutationStarted && action.reconcile) || needsReconciliation,
        })
      }
    } finally {
      if (!signal.aborted) {
        lock.current = false
        setBusy(false)
      }
    }
  }

  const select = (id: number | undefined) => {
    if (lock.current) return
    setSelectedId(id)
    setResult(undefined)
    setPhotoResult(undefined)
    setRecoveredReservation(undefined)
    setPhoto(undefined)
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
    photoResult,
    recoveredReservation,
    photoUrl,
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
    upload: (photo: File) => {
      if (!selectedId || lock.current) return
      setPhoto(photo)
      setResult(undefined)
      setPhotoResult(undefined)
      setRecoveredReservation(undefined)
      return run({ kind: 'photo', reservationId: selectedId, photo, key: crypto.randomUUID() })
    },
    resolve: (quantity: number) =>
      selectedId &&
      run({ kind: 'resolve', reservationId: selectedId, quantity, key: crypto.randomUUID() }),
    cancel: () =>
      selectedId && run({ kind: 'cancel', reservationId: selectedId, key: crypto.randomUUID() }),
  }
}
