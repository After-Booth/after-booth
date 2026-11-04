import { useCallback, useEffect, useRef, useState } from 'react'
import {
  confirmIntakeDraft,
  createIntakeDraft,
  getIntakeDraft,
  listEvents,
} from '../../api/generated/afterbooth'
import type {
  Condition,
  ConfirmIntakeRequest,
  DraftCategory,
  IntakeDraft,
  Item,
} from '../../api/generated/model'
import { ApiError } from '../../api/http'

const POLL_INTERVAL_MS = 1000
const POLL_TIMEOUT_MS = 90_000
const MAX_POLL_ERRORS = 3
const CONFLICT_RETRY_DELAYS = [1000, 2000, 4000]

async function retryConflict<T>(action: () => Promise<T>, signal: AbortSignal): Promise<T> {
  for (let attempt = 0; ; attempt += 1) {
    signal.throwIfAborted()
    try {
      return await action()
    } catch (e) {
      if (!(e instanceof ApiError) || e.code !== 'IDEMPOTENCY_CONFLICT') throw e
      const delay = CONFLICT_RETRY_DELAYS[attempt]
      if (delay === undefined) throw e
      await new Promise<void>((resolve, reject) => {
        const abort = () => {
          clearTimeout(timer)
          reject(signal.reason)
        }
        const timer = setTimeout(() => {
          signal.removeEventListener('abort', abort)
          resolve()
        }, delay)
        signal.addEventListener('abort', abort, { once: true })
      })
    }
  }
}

export type EditableLine = {
  lineId: number
  originalLabel: string
  category: DraftCategory
  quantity: string
  width: string
  depth: string
  height: string
  condition: Condition
  needsReview: boolean
  boxes: IntakeDraft['lines'][number]['boxes']
}

// the client throws ApiError for non-2xx, so a resolved response is always the success body
const bodyOf = <T>(res: { data: unknown }) => res.data as T

const messageOf = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : fallback)

const toEditable = (line: IntakeDraft['lines'][number]): EditableLine => ({
  lineId: line.lineId,
  originalLabel: line.label,
  category: line.category,
  quantity: String(line.count),
  width: line.sizeCm ? String(line.sizeCm.width) : '',
  depth: line.sizeCm ? String(line.sizeCm.depth) : '',
  height: line.sizeCm ? String(line.sizeCm.height) : '',
  condition: line.condition,
  needsReview: line.needsReview,
  boxes: line.boxes,
})

const isPositiveInt = (value: string) => /^\d+$/.test(value) && Number(value) >= 1

export function problemOf(line: EditableLine): string | undefined {
  if (line.category === 'UNKNOWN') return '분류를 골라 주세요'
  if (!isPositiveInt(line.quantity)) return '수량은 1개 이상이어야 해요'
  if (![line.width, line.depth, line.height].every(isPositiveInt)) {
    return '크기를 1cm 이상으로 입력해 주세요'
  }
  return undefined
}

function toRequest(lines: EditableLine[]): ConfirmIntakeRequest {
  return {
    lines: lines.map((line) => ({
      lineId: line.lineId,
      category: line.category as Exclude<DraftCategory, 'UNKNOWN'>,
      quantity: Number(line.quantity),
      sizeCm: {
        width: Number(line.width),
        depth: Number(line.depth),
        height: Number(line.height),
      },
      condition: line.condition,
    })),
  }
}

export function useIntake() {
  const [photoUrl, setPhotoUrl] = useState<string>()
  const [draft, setDraft] = useState<IntakeDraft>()
  const [lines, setLines] = useState<EditableLine[]>([])
  const [uploading, setUploading] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string>()
  const [items, setItems] = useState<Item[]>()
  // a retry of the same confirm request reuses its key (D-019); an edited request is a new action
  const confirmAttempt = useRef<{ body: string; key: string }>(undefined)
  const writeController = useRef<AbortController>(undefined)

  useEffect(() => () => writeController.current?.abort(), [])

  const receive = useCallback((next: IntakeDraft) => {
    setDraft(next)
    if (next.status === 'READY') setLines(next.lines.map(toEditable))
  }, [])

  const draftId = draft?.id
  const draftStatus = draft?.status
  useEffect(() => {
    if (draftStatus !== 'PROCESSING' || draftId === undefined) return
    const controller = new AbortController()
    let errors = 0
    let timer: ReturnType<typeof setTimeout>
    const stop = (message: string) => {
      controller.abort()
      clearTimeout(timer)
      setDraft(undefined)
      setError(message)
    }
    const deadline = setTimeout(
      () => stop('분석이 오래 걸리고 있어요. 사진을 다시 찍어 주세요.'),
      POLL_TIMEOUT_MS,
    )
    const poll = async () => {
      try {
        const res = await getIntakeDraft(draftId, { signal: controller.signal })
        if (controller.signal.aborted) return
        const next = bodyOf<IntakeDraft>(res)
        receive(next)
        errors = 0
        if (next.status !== 'PROCESSING') return
      } catch (e) {
        if (controller.signal.aborted) return
        errors += 1
        const transient =
          !(e instanceof ApiError) || e.status >= 500 || [408, 429].includes(e.status)
        if (!transient || errors >= MAX_POLL_ERRORS) {
          stop('분석 상태를 확인하지 못했어요. 사진을 다시 찍어 주세요.')
          return
        }
      }
      timer = setTimeout(() => void poll(), POLL_INTERVAL_MS)
    }
    timer = setTimeout(() => void poll(), POLL_INTERVAL_MS)
    return () => {
      controller.abort()
      clearTimeout(timer)
      clearTimeout(deadline)
    }
  }, [draftId, draftStatus, receive])

  useEffect(() => {
    return () => {
      if (photoUrl) URL.revokeObjectURL(photoUrl)
    }
  }, [photoUrl])

  const upload = useCallback(
    async (photo: File, selectedEventId?: number) => {
      writeController.current?.abort()
      const controller = new AbortController()
      writeController.current = controller
      confirmAttempt.current = undefined
      const key = crypto.randomUUID()
      setPhotoUrl(URL.createObjectURL(photo))
      setDraft(undefined)
      setLines([])
      setItems(undefined)
      setError(undefined)
      setUploading(true)
      try {
        const event = (await listEvents({ signal: controller.signal })).data.find(
          (entry) => selectedEventId === undefined || entry.id === selectedEventId,
        )
        if (controller.signal.aborted) return
        if (!event) {
          setError('등록할 행사를 찾지 못했어요.')
          return
        }
        const res = await retryConflict(
          () =>
            createIntakeDraft(
              { eventId: event.id, photo },
              { 'Idempotency-Key': key },
              { signal: controller.signal },
            ),
          controller.signal,
        )
        if (!controller.signal.aborted) receive(bodyOf<IntakeDraft>(res))
      } catch (e) {
        if (!controller.signal.aborted)
          setError(messageOf(e, '사진을 올리지 못했어요. 다시 찍어 주세요.'))
      } finally {
        if (!controller.signal.aborted) setUploading(false)
      }
    },
    [receive],
  )

  const editLine = useCallback((lineId: number, patch: Partial<EditableLine>) => {
    setLines((current) => current.map((l) => (l.lineId === lineId ? { ...l, ...patch } : l)))
  }, [])

  const canConfirm = lines.length > 0 && lines.every((l) => !problemOf(l))

  const confirm = useCallback(async () => {
    if (!draft || !canConfirm) return
    writeController.current?.abort()
    const controller = new AbortController()
    writeController.current = controller
    const request = toRequest(lines)
    const body = JSON.stringify(request)
    if (confirmAttempt.current?.body !== body) {
      confirmAttempt.current = { body, key: crypto.randomUUID() }
    }
    setError(undefined)
    setConfirming(true)
    const key = confirmAttempt.current.key
    try {
      const res = await retryConflict(
        () =>
          confirmIntakeDraft(
            draft.id,
            request,
            { 'Idempotency-Key': key },
            { signal: controller.signal },
          ),
        controller.signal,
      )
      if (!controller.signal.aborted) setItems(bodyOf<Item[]>(res))
    } catch (e) {
      if (controller.signal.aborted) return
      if (e instanceof ApiError) confirmAttempt.current = undefined
      setError(messageOf(e, '재고로 등록하지 못했어요. 다시 시도해 주세요.'))
    } finally {
      if (!controller.signal.aborted) setConfirming(false)
    }
  }, [draft, lines, canConfirm])

  return {
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
  }
}
