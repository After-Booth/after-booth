import { useEffect, useState } from 'react'
import { getPickupRequest, listItems, listReservations } from '../../api/generated/afterbooth'
import type { Item, PickupRequest, Reservation } from '../../api/generated/model'
import { ApiError } from '../../api/http'

export function useAllocationBoard(requestId?: number) {
  const [items, setItems] = useState<Item[]>([])
  const [reservations, setReservations] = useState<Reservation[]>([])
  const [request, setRequest] = useState<PickupRequest>()
  const [loading, setLoading] = useState(true)
  const [message, setMessage] = useState('')
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const controller = new AbortController()
    let inFlight = false
    async function refresh() {
      if (inFlight) return
      inFlight = true
      try {
        const [inventory, holds, pickup] = await Promise.all([
          listItems(undefined, { signal: controller.signal }),
          listReservations(undefined, { signal: controller.signal }),
          requestId ? getPickupRequest(requestId, { signal: controller.signal }) : undefined,
        ])
        if (!controller.signal.aborted) {
          setItems(inventory.data)
          setReservations(holds.data)
          if (pickup?.status === 200) setRequest(pickup.data)
          setMessage('')
        }
      } catch (error) {
        if (!controller.signal.aborted)
          setMessage(
            error instanceof ApiError
              ? error.message
              : '최신 수량을 불러오지 못했어요. 잠시 후 자동으로 다시 확인해요.',
          )
      } finally {
        if (!controller.signal.aborted) setLoading(false)
        inFlight = false
      }
    }
    void refresh()
    const poll = setInterval(() => void refresh(), 3000)
    const clock = setInterval(() => setNow(Date.now()), 1000)
    return () => {
      controller.abort()
      clearInterval(poll)
      clearInterval(clock)
    }
  }, [requestId])
  return { items, reservations, request, loading, message, now }
}
