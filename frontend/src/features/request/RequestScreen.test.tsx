import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import {
  getAfterBoothAPIMock,
  getInterpretConditionsMockHandler,
  getListOrganizationsMockHandler,
} from '../../api/generated/afterbooth.msw'
import type { CreatePickupRequest } from '../../api/generated/model'
import { RequestScreen } from './RequestScreen'

const transcript = '주말 행사에 쓸 진열대 세 개가 필요해요. 승용차로 가져갈게요.'
const server = setupServer(
  getListOrganizationsMockHandler([
    { id: 7, name: '주말 행사팀' },
    { id: 8, name: '이웃 행사팀' },
  ]),
  getInterpretConditionsMockHandler({
    context: 'RECEIVER',
    transcript,
    conditions: {
      category: 'DISPLAY_RACK',
      quantity: 3,
      vehicleType: 'SEDAN',
      purpose: '주말 행사 진열용',
      pickupDeadline: '2026-11-01T09:00:00Z',
    },
    missingFields: [],
  }),
  ...getAfterBoothAPIMock(),
)
beforeAll(() => server.listen())
afterEach(() => {
  cleanup()
  server.resetHandlers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
afterAll(() => server.close())

async function enterText() {
  const user = userEvent.setup()
  render(<RequestScreen />)
  await screen.findByRole('option', { name: '주말 행사팀' })
  await user.selectOptions(screen.getByLabelText('1. 받는 단체'), '7')
  await user.type(screen.getByLabelText('글로 입력하기'), transcript)
  await user.click(screen.getByRole('button', { name: '입력한 내용 해석하기' }))
  return user
}

test('interprets receiver text, edits all fields and saves the edited body with one idempotency key', async () => {
  let sent: CreatePickupRequest | undefined
  let key: string | null = null
  server.use(
    http.post('*/api/conditions/interpret', async ({ request }) => {
      const data = await request.formData()
      expect(data.get('context')).toBe('RECEIVER')
      expect(data.get('text')).toBe(transcript)
      expect(data.has('audio')).toBe(false)
      return HttpResponse.json({
        context: 'RECEIVER',
        transcript,
        conditions: {
          category: 'DISPLAY_RACK',
          quantity: 3,
          vehicleType: 'SEDAN',
          purpose: '주말 행사 진열용',
          pickupDeadline: '2026-11-01T09:00:00Z',
        },
        missingFields: [],
      })
    }),
    http.post('*/api/pickup-requests', async ({ request }) => {
      sent = (await request.json()) as CreatePickupRequest
      key = request.headers.get('Idempotency-Key')
      return HttpResponse.json(
        { id: 42, ...sent, createdAt: '2026-10-31T15:00:00+09:00' },
        { status: 201 },
      )
    }),
  )
  const user = await enterText()
  expect(await screen.findByText('입력 원문')).toBeInTheDocument()
  expect(screen.getByText(transcript, { selector: 'p' })).toBeInTheDocument()
  expect(screen.getByLabelText(/픽업 가능 시각/)).toHaveValue('2026-11-01T18:00')
  expect(screen.getByLabelText('1. 받는 단체')).toHaveValue('7')
  await user.selectOptions(screen.getByLabelText('1. 받는 단체'), '8')
  await user.selectOptions(screen.getByLabelText('물품 종류'), 'STORAGE_BIN')
  await user.clear(screen.getByLabelText('수량'))
  await user.type(screen.getByLabelText('수량'), '5')
  await user.selectOptions(screen.getByLabelText('가져갈 차량'), 'VAN')
  await user.clear(screen.getByLabelText('사용 목적'))
  await user.type(screen.getByLabelText('사용 목적'), '축제 물품 보관용')
  const time = screen.getByLabelText(/픽업 가능 시각/)
  // datetime-local has no cross-browser userEvent typing support.
  const { fireEvent } = await import('@testing-library/react')
  fireEvent.change(time, { target: { value: '2026-11-02T10:30' } })
  await user.click(screen.getByRole('button', { name: '픽업 요청 저장' }))
  expect(await screen.findByText('픽업 요청을 저장했어요')).toBeInTheDocument()
  expect(sent).toEqual({
    organizationId: 8,
    category: 'STORAGE_BIN',
    quantity: 5,
    vehicleType: 'VAN',
    purpose: '축제 물품 보관용',
    pickupDeadline: '2026-11-02T10:30:00+09:00',
    transcript,
  })
  expect(key).toMatch(/^[0-9a-f-]{36}$/)
  expect(screen.getByText('수납함 5개')).toBeInTheDocument()
  expect(screen.getByLabelText('녹음 파일 올리기 (WAV)')).toBeDisabled()
})

test('highlights missing fields and defaults quantity to one before allowing manual completion', async () => {
  server.use(
    getInterpretConditionsMockHandler({
      context: 'RECEIVER',
      transcript,
      conditions: {},
      missingFields: ['category', 'vehicleType'],
    }),
  )
  const user = await enterText()
  const category = await screen.findByLabelText(/물품 종류/)
  expect(category).toHaveAccessibleDescription('확인 필요 · 직접 입력해 주세요')
  expect(screen.getByLabelText(/가져갈 차량/)).toHaveAccessibleDescription(
    '확인 필요 · 직접 입력해 주세요',
  )
  expect(category).toHaveClass('border-amber-600')
  expect(screen.getByLabelText('수량')).toHaveValue(1)
  expect(screen.getByRole('button', { name: '픽업 요청 저장' })).toBeDisabled()
  await user.selectOptions(category, 'FOLDING_TABLE')
  await user.selectOptions(screen.getByLabelText(/가져갈 차량/), 'SEDAN')
  expect(screen.getByRole('button', { name: '픽업 요청 저장' })).toBeEnabled()
})

test.each([400, 415, 503])(
  'shows the Korean API error on %s and allows another attempt',
  async (status) => {
    const messages: Record<number, string> = {
      400: '입력한 내용을 확인해 주세요.',
      415: 'WAV 형식으로 다시 녹음해 주세요.',
      503: '음성 해석을 잠시 사용할 수 없어요. 다시 시도해 주세요.',
    }
    server.use(
      http.post('*/api/conditions/interpret', () =>
        HttpResponse.json({ code: 'AI_UNAVAILABLE', message: messages[status] }, { status }),
      ),
    )
    await enterText()
    expect(await screen.findByRole('alert')).toHaveTextContent(messages[status])
    expect(screen.getByRole('button', { name: '입력한 내용 해석하기' })).toBeEnabled()
  },
)

test('offers text entry when microphone permission is denied', async () => {
  vi.stubGlobal('navigator', {
    ...navigator,
    mediaDevices: {
      getUserMedia: vi.fn().mockRejectedValue(new DOMException('Denied', 'NotAllowedError')),
    },
  })
  render(<RequestScreen />)
  await userEvent.click(screen.getByRole('button', { name: '마이크로 말하기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('마이크 권한을 허용하거나')
  expect(screen.getByLabelText('글로 입력하기')).toBeEnabled()
})

test('shows an empty interpretation and permits text retry', async () => {
  server.use(
    getInterpretConditionsMockHandler({
      context: 'RECEIVER',
      transcript: '',
      conditions: {},
      missingFields: ['category', 'vehicleType'],
    }),
  )
  await enterText()
  expect(await screen.findByText(/들을 수 있는 내용이 없었어요/)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '픽업 요청 저장' })).not.toBeInTheDocument()
})

test('omits blank optional fields and prevents duplicate submissions while saving', async () => {
  server.use(
    getInterpretConditionsMockHandler({
      context: 'RECEIVER',
      transcript,
      conditions: { category: 'DISPLAY_RACK', vehicleType: 'SEDAN' },
      missingFields: [],
    }),
  )
  let calls = 0
  let sent: unknown
  let complete: (() => void) | undefined
  server.use(
    http.post('*/api/pickup-requests', async ({ request }) => {
      calls++
      sent = await request.json()
      await new Promise<void>((resolve) => {
        complete = resolve
      })
      return HttpResponse.json(
        { id: 1, ...(sent as CreatePickupRequest), createdAt: '2026-11-01T10:00:00+09:00' },
        { status: 201 },
      )
    }),
  )
  const user = await enterText()
  const save = await screen.findByRole('button', { name: '픽업 요청 저장' })
  await user.dblClick(save)
  await waitFor(() => expect(calls).toBe(1))
  expect(save).toBeDisabled()
  expect(sent).toEqual({
    organizationId: 7,
    category: 'DISPLAY_RACK',
    quantity: 1,
    vehicleType: 'SEDAN',
    transcript,
  })
  expect(screen.getByLabelText('녹음 파일 올리기 (WAV)')).toBeDisabled()
  complete?.()
  expect(await screen.findByText('픽업 요청을 저장했어요')).toBeInTheDocument()
})

test.each(['audio/wav', 'audio/x-wav', ''])(
  'uploads WAV bytes unchanged with voice timing when the file MIME is %s',
  async (type) => {
    const bytes = new Uint8Array([82, 73, 70, 70, 0, 255, 128, 0, 87, 65, 86, 69])
    // MSW cannot read a jsdom File from a multipart body, so the request is inspected before it leaves
    const send = globalThis.fetch
    let sent: FormData | undefined
    vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
      if (!url.endsWith('/api/conditions/interpret')) return send(url, init)
      sent = init?.body as FormData
      return Promise.resolve(
        Response.json({ context: 'RECEIVER', transcript, conditions: {}, missingFields: [] }),
      )
    })
    render(<RequestScreen />)
    const input = screen.getByLabelText('녹음 파일 올리기 (WAV)')
    expect(input).toHaveAttribute('accept', 'audio/wav,.wav')
    expect(input).toHaveAccessibleDescription('16 kHz 모노 WAV 파일을 올려 주세요 · 최대 60초')
    // Checked synchronously after the change, before the upload can answer
    fireEvent.change(input, { target: { files: [new File([bytes], 'sample.WAV', { type })] } })
    expect(screen.getByText('말씀하신 조건을 정리하고 있어요…')).toBeInTheDocument()
    expect(screen.getByText('0초 경과 · 보통 약 5초 걸려요')).toBeInTheDocument()
    expect(input).toBeDisabled()
    expect(screen.getByRole('button', { name: '마이크로 말하기' })).toBeDisabled()
    expect(await screen.findByText('입력 원문')).toBeInTheDocument()
    expect(sent?.get('context')).toBe('RECEIVER')
    expect(sent?.has('text')).toBe(false)
    const audio = sent?.get('audio') as File
    expect(audio.name).toBe('sample.WAV')
    expect(audio.type).toBe('audio/wav')
    expect(new Uint8Array(await audio.arrayBuffer())).toEqual(bytes)
    expect(input).toBeEnabled()
  },
)

test('rejects non-WAV uploads before calling the interpretation API', async () => {
  let calls = 0
  server.use(
    http.post('*/api/conditions/interpret', () => {
      calls++
      return HttpResponse.error()
    }),
  )
  render(<RequestScreen />)
  const user = userEvent.setup({ applyAccept: false })
  await user.upload(
    screen.getByLabelText('녹음 파일 올리기 (WAV)'),
    new File(['audio'], 'recording.mp3', { type: 'audio/wav' }),
  )
  expect(await screen.findByRole('alert')).toHaveTextContent('WAV 파일만 올릴 수 있어요.')
  expect(calls).toBe(0)
  expect(screen.getByLabelText('녹음 파일 올리기 (WAV)')).toBeEnabled()
})

test('uses the shorter text interpretation estimate', async () => {
  let complete: (() => void) | undefined
  server.use(
    http.post('*/api/conditions/interpret', async () => {
      await new Promise<void>((resolve) => {
        complete = resolve
      })
      return HttpResponse.json({
        context: 'RECEIVER',
        transcript,
        conditions: {},
        missingFields: [],
      })
    }),
  )
  await enterText()
  expect(screen.getByText('0초 경과 · 보통 약 3초 걸려요')).toBeInTheDocument()
  expect(screen.getByLabelText('녹음 파일 올리기 (WAV)')).toBeDisabled()
  await waitFor(() => expect(complete).toBeTypeOf('function'))
  complete?.()
  await screen.findByText('입력 원문')
})
