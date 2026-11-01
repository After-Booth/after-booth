import { useEffect, useRef, useState } from 'react'
import {
  confirmReservation,
  holdItem,
  listItems,
  listReservations,
  runAllocationAgent,
} from '../../api/generated/afterbooth'
import type {
  AllocationResult,
  Alternative,
  Item,
  PickupRequest,
  Reservation,
} from '../../api/generated/model'
import { ApiError } from '../../api/http'

export function useAllocation(request: PickupRequest) {
  const [result, setResult] = useState<AllocationResult>()
  const [reservations, setReservations] = useState<Reservation[]>([])
  const [pending, setPending] = useState(false)
  const [loading, setLoading] = useState(true)
  const [message, setMessage] = useState('')
  const [pollMessage, setPollMessage] = useState('')
  const [stockMessage, setStockMessage] = useState('')
  const [stockLoading, setStockLoading] = useState(true)
  const [aiUnavailable, setAiUnavailable] = useState('')
  const [currentStock, setCurrentStock] = useState<Item[]>([])
  const busy = useRef(false)
  const revision = useRef(0)
  const active = useRef(true)
  const keys = useRef(new Map<string, string>())
  const timers = useRef(new Map<ReturnType<typeof setTimeout>, () => void>())

  useEffect(() => {
    active.current = true
    let cancelled = false
    let refreshing = false
    const controller = new AbortController()
    async function refresh() {
      if (refreshing || busy.current) return
      refreshing = true
      const started = revision.current
      try {
        const response = await listReservations(
          { requestId: request.id },
          { signal: controller.signal },
        )
        if (!cancelled && started === revision.current) {
          setReservations(response.data)
          setPollMessage('')
          setLoading(false)
        }
      } catch {
        if (!cancelled && started === revision.current) {
          setPollMessage('예약 현황을 불러오지 못했어요. 잠시 후 다시 확인해 주세요.')
          setLoading(false)
        }
      } finally {
        refreshing = false
      }
    }
    void refresh()
    const interval = setInterval(() => void refresh(), 3000)
    const waits = timers.current
    return () => {
      cancelled = true
      active.current = false
      controller.abort()
      clearInterval(interval)
      waits.forEach((resolve, timer) => {
        clearTimeout(timer)
        resolve()
      })
      waits.clear()
    }
  }, [request.id])

  const activeQuantity = reservations
    .filter((reservation) => ['HELD', 'CONFIRMED'].includes(reservation.status))
    .reduce((sum, reservation) => sum + reservation.quantity, 0)
  const recoveredPartial = !result && activeQuantity > 0 && activeQuantity < request.quantity

  const manualSelection =
    !loading && activeQuantity < request.quantity && (recoveredPartial || !!aiUnavailable)

  useEffect(() => {
    if (!manualSelection) return
    const controller = new AbortController()
    let refreshing = false
    async function refreshStock() {
      if (refreshing) return
      refreshing = true
      try {
        const response = await listItems({ availableOnly: true }, { signal: controller.signal })
        if (!controller.signal.aborted) {
          setCurrentStock(response.data)
          setStockMessage('')
          setStockLoading(false)
        }
      } catch {
        if (!controller.signal.aborted) {
          setStockMessage('추가로 선택할 재고를 불러오지 못했어요. 잠시 후 다시 확인해 주세요.')
          setStockLoading(false)
        }
      } finally {
        refreshing = false
      }
    }
    void refreshStock()
    const interval = setInterval(() => void refreshStock(), 3000)
    return () => {
      controller.abort()
      clearInterval(interval)
    }
  }, [manualSelection, request.id])

  async function execute(alternative?: Alternative) {
    if (busy.current || loading) return
    if (
      alternative &&
      (alternative.quantity <= 0 || alternative.quantity > request.quantity - activeQuantity)
    )
      return
    busy.current = true
    revision.current++
    setPending(true)
    setMessage('')
    const action = alternative ? `hold:${alternative.item.id}:${alternative.quantity}` : 'allocate'
    const key = keys.current.get(action) ?? crypto.randomUUID()
    keys.current.set(action, key)
    try {
      for (let attempt = 0; ; attempt++) {
        try {
          if (alternative) {
            const response = await holdItem(
              {
                requestId: request.id,
                itemId: alternative.item.id,
                quantity: alternative.quantity,
              },
              { 'Idempotency-Key': key },
            )
            if (response.status !== 201)
              throw new ApiError(response.status, response.data.code, response.data.message)
            if (active.current) {
              setReservations((previous) => [
                ...previous.filter((r) => r.id !== response.data.id),
                response.data,
              ])
              setResult(
                (previous) =>
                  previous && {
                    ...previous,
                    alternatives: previous.alternatives.filter(
                      (a) => a.item.id !== alternative.item.id,
                    ),
                  },
              )
            }
          } else {
            const response = await runAllocationAgent(request.id, { 'Idempotency-Key': key })
            if (response.status !== 200)
              throw new ApiError(response.status, response.data.code, response.data.message)
            if (active.current) {
              setAiUnavailable('')
              setResult(response.data)
              setReservations(response.data.reservations)
            }
          }
          keys.current.delete(action)
          break
        } catch (error) {
          if (!(error instanceof ApiError) || error.code !== 'IDEMPOTENCY_CONFLICT') throw error
          if (attempt === 3) {
            keys.current.delete(action)
            throw error
          }
          await new Promise<void>((resolve) => {
            const timer = setTimeout(
              () => {
                timers.current.delete(timer)
                resolve()
              },
              1000 * 2 ** attempt,
            )
            timers.current.set(timer, resolve)
          })
          if (!active.current) return
        }
      }
    } catch (error) {
      if (error instanceof ApiError && error.code !== 'IDEMPOTENCY_CONFLICT')
        keys.current.delete(action)
      if (active.current) {
        if (error instanceof ApiError && error.code === 'AI_UNAVAILABLE')
          setAiUnavailable(error.message)
        setMessage(
          error instanceof ApiError
            ? error.message
            : '배정 결과를 확인하지 못했어요. 같은 작업을 다시 시도해 주세요.',
        )
        if (error instanceof ApiError && error.code === 'REQUEST_ALREADY_HELD') {
          const response = await listReservations({ requestId: request.id }).catch(() => undefined)
          if (response && active.current) setReservations(response.data)
        }
      }
    } finally {
      busy.current = false
      if (active.current) setPending(false)
    }
  }

  async function confirm(reservation: Reservation) {
    if (busy.current || reservation.status !== 'HELD') return
    busy.current = true
    revision.current++
    setPending(true)
    setMessage('')
    const action = `confirm:${reservation.id}`
    const key = keys.current.get(action) ?? crypto.randomUUID()
    keys.current.set(action, key)
    try {
      const response = await confirmReservation(reservation.id, { 'Idempotency-Key': key })
      if (response.status !== 200)
        throw new ApiError(response.status, response.data.code, response.data.message)
      keys.current.delete(action)
      if (active.current)
        setReservations((previous) =>
          previous.map((r) => (r.id === response.data.id ? response.data : r)),
        )
    } catch (error) {
      // An unknown outcome keeps the key, so a retry replays the first confirmation
      if (error instanceof ApiError && error.status < 500 && error.code !== 'IDEMPOTENCY_CONFLICT')
        keys.current.delete(action)
      if (active.current) {
        setMessage(
          error instanceof ApiError
            ? error.message
            : '예약 확정 결과를 확인하지 못했어요. 다시 시도해 주세요.',
        )
        if (error instanceof ApiError && error.code === 'HOLD_EXPIRED') {
          const response = await listReservations({ requestId: request.id }).catch(() => undefined)
          if (response && active.current) setReservations(response.data)
        }
      }
    } finally {
      busy.current = false
      if (active.current) setPending(false)
    }
  }

  return {
    result,
    reservations,
    pending,
    loading,
    message: message || (activeQuantity < request.quantity ? aiUnavailable : '') || pollMessage,
    stockMessage,
    stockLoading,
    manualSelection,
    execute,
    confirm,
    recoveredPartial,
    currentStock,
  }
}
