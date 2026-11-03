import { useEffect, useRef, useState } from 'react'
import {
  createPickupRequest,
  interpretConditions,
  listOrganizations,
} from '../../api/generated/afterbooth'
import type {
  ConditionInterpretation,
  CreatePickupRequest,
  Organization,
  PickupRequest,
} from '../../api/generated/model'
import { ApiError } from '../../api/http'
import { startRecording } from './recorder'

type Status =
  | 'idle'
  | 'starting'
  | 'recording'
  | 'interpreting'
  | 'ready'
  | 'saving'
  | 'saved'
  | 'error'
  | 'empty'
export type RequestFields = {
  category: CreatePickupRequest['category'] | ''
  quantity: number
  vehicleType: CreatePickupRequest['vehicleType'] | ''
  pickupDeadline: string
  purpose: string
}

export function seoulInput(value?: string) {
  if (!value) return ''
  const date = new Date(value)
  return new Date(date.getTime() + 9 * 60 * 60 * 1000).toISOString().slice(0, 16)
}

function messageOf(error: unknown) {
  if (error instanceof ApiError) return error.message
  return '요청을 처리하지 못했어요. 연결을 확인하고 다시 시도해 주세요.'
}

export function useRequest(ownedOrganizationId?: number) {
  const [status, setStatus] = useState<Status>('idle')
  const [message, setMessage] = useState('')
  const [organizations, setOrganizations] = useState<Organization[]>([])
  const [catalogStatus, setCatalogStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [organizationId, setOrganizationId] = useState(
    ownedOrganizationId ? String(ownedOrganizationId) : '',
  )
  const [interpretation, setInterpretation] = useState<ConditionInterpretation>()
  const [saved, setSaved] = useState<PickupRequest>()
  const [fields, setFields] = useState<RequestFields>({
    category: '',
    quantity: 1,
    vehicleType: '',
    pickupDeadline: '',
    purpose: '',
  })
  const [elapsed, setElapsed] = useState(0)
  const [interpretationSource, setInterpretationSource] = useState<'voice' | 'text'>('text')
  const recorder = useRef<Awaited<ReturnType<typeof startRecording>> | undefined>(undefined)
  const active = useRef(true)
  const busy = useRef(false)
  const idempotencyKey = useRef<string | undefined>(undefined)

  useEffect(() => {
    idempotencyKey.current = undefined
  }, [fields, organizationId])

  useEffect(() => {
    active.current = true
    return () => {
      active.current = false
      recorder.current?.cancel()
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    listOrganizations()
      .then((res) => {
        if (!cancelled) {
          setOrganizations(res.data)
          setCatalogStatus('ready')
        }
      })
      .catch(() => {
        if (!cancelled) setCatalogStatus('error')
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    if (status !== 'recording') return
    const started = Date.now()
    const timer = setInterval(
      () => setElapsed(Math.min(60, Math.floor((Date.now() - started) / 1000))),
      250,
    )
    return () => clearInterval(timer)
  }, [status])

  async function interpret(input: { text: string } | { audio: File }) {
    setInterpretationSource('audio' in input ? 'voice' : 'text')
    setStatus('interpreting')
    setMessage('')
    setInterpretation(undefined)
    setSaved(undefined)
    try {
      const res = await interpretConditions({ context: 'RECEIVER', ...input })
      if (res.status !== 200) throw new ApiError(res.status, res.data.code, res.data.message)
      if (!active.current) return
      const result = res.data
      setInterpretation(result)
      setFields({
        category: result.conditions.category ?? '',
        quantity: result.conditions.quantity ?? 1,
        vehicleType: result.conditions.vehicleType ?? '',
        pickupDeadline: seoulInput(result.conditions.pickupDeadline),
        purpose: result.conditions.purpose ?? '',
      })
      setStatus(
        !result.transcript.trim() && !Object.keys(result.conditions).length ? 'empty' : 'ready',
      )
    } catch (error) {
      if (active.current) {
        setMessage(messageOf(error))
        setStatus('error')
      }
    } finally {
      busy.current = false
    }
  }

  function interpretText(text: string) {
    if (busy.current || !text.trim() || text.length > 500) return
    busy.current = true
    void interpret({ text: text.trim() })
  }

  function interpretAudio(file: File) {
    if (busy.current || status === 'saved') return
    if (!file.name.toLowerCase().endsWith('.wav')) {
      setMessage('WAV 파일만 올릴 수 있어요. 확장자가 .wav인 녹음 파일을 선택해 주세요.')
      return
    }
    busy.current = true
    void interpret({ audio: new File([file], file.name, { type: 'audio/wav' }) })
  }

  function stop() {
    const current = recorder.current
    if (!current) return
    recorder.current = undefined
    try {
      void interpret({ audio: current.stop() })
    } catch {
      busy.current = false
      setMessage('녹음된 소리가 없어요. 다시 녹음하거나 글로 입력해 주세요.')
      setStatus('error')
    }
  }

  async function toggleRecording() {
    if (recorder.current) {
      stop()
      return
    }
    if (busy.current) return
    busy.current = true
    setStatus('starting')
    setMessage('')
    try {
      const recording = await startRecording(stop)
      if (!active.current) {
        recording.cancel()
        return
      }
      recorder.current = recording
      setElapsed(0)
      setStatus('recording')
    } catch {
      busy.current = false
      if (active.current) {
        setMessage('마이크를 사용할 수 없어요. 마이크 권한을 허용하거나 아래에 글로 입력해 주세요.')
        setStatus('error')
      }
    }
  }

  async function submit() {
    if (
      busy.current ||
      !organizationId ||
      !fields.category ||
      !fields.vehicleType ||
      !Number.isInteger(fields.quantity) ||
      fields.quantity < 1 ||
      !interpretation
    )
      return
    busy.current = true
    setStatus('saving')
    setMessage('')
    const body: CreatePickupRequest = {
      organizationId: Number(organizationId),
      category: fields.category,
      quantity: fields.quantity,
      vehicleType: fields.vehicleType,
      transcript: interpretation.transcript,
      ...(fields.pickupDeadline ? { pickupDeadline: `${fields.pickupDeadline}:00+09:00` } : {}),
      ...(fields.purpose.trim() ? { purpose: fields.purpose.trim() } : {}),
    }
    const key = (idempotencyKey.current ??= crypto.randomUUID())
    try {
      for (let attempt = 0; ; attempt++) {
        try {
          const res = await createPickupRequest(body, { 'Idempotency-Key': key })
          if (res.status !== 201) throw new ApiError(res.status, res.data.code, res.data.message)
          idempotencyKey.current = undefined
          if (active.current) {
            setSaved(res.data)
            setStatus('saved')
          }
          break
        } catch (error) {
          if (!(error instanceof ApiError) || error.code !== 'IDEMPOTENCY_CONFLICT') throw error
          if (attempt === 3) {
            idempotencyKey.current = undefined
            throw error
          }
          await new Promise((resolve) => setTimeout(resolve, 1000 * 2 ** attempt))
          if (!active.current) return
        }
      }
    } catch (error) {
      if (active.current) {
        setMessage(messageOf(error))
        setStatus('error')
      }
    } finally {
      busy.current = false
    }
  }

  return {
    status,
    message,
    organizations,
    catalogStatus,
    organizationId,
    setOrganizationId,
    interpretation,
    fields,
    setFields,
    elapsed,
    interpretationSource,
    toggleRecording,
    interpretText,
    interpretAudio,
    submit,
    saved,
  }
}
