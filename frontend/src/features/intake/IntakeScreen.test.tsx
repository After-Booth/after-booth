import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, expect, test, vi } from 'vitest'
import type { DraftLine, IntakeDraft } from '../../api/generated/model'
import { IntakeScreen } from './IntakeScreen'

const server = setupServer(
  http.get('*/api/events', () =>
    HttpResponse.json([
      {
        id: 7,
        name: 'Demo',
        organizationId: 1,
        venue: 'Seoul',
        teardownDeadline: '2026-11-07T18:00:00+09:00',
      },
    ]),
  ),
)
beforeAll(() => server.listen())
beforeEach(() => {
  URL.createObjectURL = vi.fn(() => 'blob:photo')
  URL.revokeObjectURL = vi.fn()
})
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const line = (overrides: Partial<DraftLine> = {}): DraftLine => ({
  lineId: 11,
  category: 'DISPLAY_RACK',
  label: '진열대',
  count: 4,
  boxes: [{ x: 0.1, y: 0.2, width: 0.3, height: 0.4 }],
  sizeCm: { width: 90, depth: 40, height: 180 },
  condition: 'GOOD',
  confidence: 0.9,
  needsReview: false,
  ...overrides,
})

const draft = (overrides: Partial<IntakeDraft> = {}): IntakeDraft => ({
  id: 5,
  eventId: 7,
  status: 'READY',
  lines: [line()],
  createdAt: '2026-11-01T10:00:00+09:00',
  ...overrides,
})

const createReturns = (body: IntakeDraft) =>
  server.use(http.post('*/api/intake/drafts', () => HttpResponse.json(body, { status: 202 })))

async function uploadPhoto() {
  await userEvent.upload(
    screen.getByLabelText('부스 사진 찍기'),
    new File(['x'], 'booth.jpg', { type: 'image/jpeg' }),
  )
}

test('polls while processing, showing the stage, until the draft is ready', async () => {
  createReturns(draft({ status: 'PROCESSING', stage: 'ESTIMATE', lines: [] }))
  let polls = 0
  server.use(
    http.get('*/api/intake/drafts/5', () => {
      polls += 1
      return HttpResponse.json(
        polls === 1 ? draft({ status: 'PROCESSING', stage: 'SEGMENT', lines: [] }) : draft(),
      )
    }),
  )
  render(<IntakeScreen />)

  await uploadPhoto()

  expect(await screen.findByText('사진에서 물품을 세는 중이에요…')).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('보통 약 8초 걸려요')
  expect(
    await screen.findByText('물품 윤곽을 다듬는 중이에요…', undefined, { timeout: 3000 }),
  ).toBeInTheDocument()
  expect(screen.getByRole('status')).not.toHaveTextContent('0초 경과')
  expect(await screen.findByText('진열대 4개', undefined, { timeout: 3000 })).toBeInTheDocument()
  expect(screen.getAllByTestId('box')).toHaveLength(1)
})

test('shows the server failure message and lets the user retake the photo', async () => {
  createReturns(draft({ status: 'FAILED', lines: [], failureMessage: '사진이 너무 어두워요.' }))
  render(<IntakeScreen />)

  await uploadPhoto()

  expect(await screen.findByRole('alert')).toHaveTextContent('사진이 너무 어두워요.')
  expect(screen.getByLabelText('다시 찍기')).toBeEnabled()
})

test('starts a second upload when the same photo is selected after a failed draft', async () => {
  let createRequests = 0
  server.use(
    http.post('*/api/intake/drafts', () => {
      createRequests += 1
      return HttpResponse.json(
        draft({ status: 'FAILED', lines: [], failureMessage: '사진이 너무 어두워요.' }),
        { status: 202 },
      )
    }),
  )
  const photo = new File(['x'], 'booth.jpg', { type: 'image/jpeg' })
  render(<IntakeScreen />)

  await userEvent.upload(screen.getByLabelText('부스 사진 찍기'), photo)
  expect(await screen.findByRole('alert')).toHaveTextContent('사진이 너무 어두워요.')
  expect(createRequests).toBe(1)

  await userEvent.upload(screen.getByLabelText('다시 찍기'), photo)

  await waitFor(() => expect(createRequests).toBe(2))
})

test('highlights lines that need review', async () => {
  createReturns(draft({ lines: [line({ needsReview: true })] }))
  render(<IntakeScreen />)

  await uploadPhoto()

  expect(await screen.findByText('확인 필요')).toBeInTheDocument()
})

test('sends the edited quantity and category when confirming', async () => {
  createReturns(draft())
  let body: unknown
  let idempotencyKey: string | null = null
  server.use(
    http.post('*/api/intake/drafts/5/confirm', async ({ request }) => {
      body = await request.json()
      idempotencyKey = request.headers.get('Idempotency-Key')
      return HttpResponse.json([{ id: 1, label: '수납함', totalQty: 6 }], { status: 201 })
    }),
  )
  render(<IntakeScreen />)
  await uploadPhoto()
  await screen.findByText('진열대 4개')

  const quantity = screen.getByLabelText('진열대 수량')
  await userEvent.clear(quantity)
  await userEvent.type(quantity, '6')
  await userEvent.selectOptions(screen.getByLabelText('진열대 분류'), '수납함')
  await userEvent.click(screen.getByRole('button', { name: '재고로 등록' }))

  expect(await screen.findByText('재고로 등록했어요.')).toBeInTheDocument()
  expect(screen.getByText('수납함 6개')).toBeInTheDocument()
  expect(body).toEqual({
    lines: [
      {
        lineId: 11,
        category: 'STORAGE_BIN',
        quantity: 6,
        sizeCm: { width: 90, depth: 40, height: 180 },
        condition: 'GOOD',
      },
    ],
  })
  expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
})

test('blocks confirming while a line is unclassified', async () => {
  createReturns(
    draft({ lines: [line({ category: 'UNKNOWN', label: '미분류', needsReview: true })] }),
  )
  render(<IntakeScreen />)

  await uploadPhoto()

  expect(await screen.findByText('분류를 골라 주세요')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '재고로 등록' })).toBeDisabled()
  await userEvent.selectOptions(screen.getByLabelText('미분류 분류'), '진열대')
  await waitFor(() => expect(screen.getByRole('button', { name: '재고로 등록' })).toBeEnabled())
})

test('shows an empty state when nothing was found', async () => {
  createReturns(draft({ lines: [] }))
  render(<IntakeScreen />)

  await uploadPhoto()

  expect(await screen.findByText(/물품을 찾지 못했어요/)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '재고로 등록' })).not.toBeInTheDocument()
})

test('shows the server validation error when confirming is rejected', async () => {
  createReturns(draft())
  server.use(
    http.post('*/api/intake/drafts/5/confirm', () =>
      HttpResponse.json(
        { code: 'BAD_REQUEST', message: '수량이 올바르지 않아요.' },
        { status: 400 },
      ),
    ),
  )
  render(<IntakeScreen />)
  await uploadPhoto()
  await screen.findByText('진열대 4개')

  await userEvent.click(screen.getByRole('button', { name: '재고로 등록' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('수량이 올바르지 않아요.')
})
