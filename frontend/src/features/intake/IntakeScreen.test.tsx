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
  label: 'Display rack',
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
    screen.getByLabelText('Take booth photo'),
    new File(['x'], 'booth.jpg', { type: 'image/jpeg' }),
  )
}

test('keeps the selected event fixed once a photo draft has started', async () => {
  createReturns(draft())
  render(<IntakeScreen showEvents />)
  await screen.findByRole('option', { name: 'Demo' })
  const selector = screen.getByLabelText('Event for registration')
  await userEvent.selectOptions(selector, '7')
  await uploadPhoto()
  expect(await screen.findByText('Display rack 4 items')).toBeInTheDocument()
  expect(selector).toBeDisabled()
  expect(selector).toHaveValue('7')
})

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

  expect(await screen.findByText('Muse Spark is counting items in the photo…')).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('Usually about 8s')
  expect(
    await screen.findByText('SAM 3.1 is checking each item outline…', undefined, {
      timeout: 3000,
    }),
  ).toBeInTheDocument()
  expect(screen.getByRole('status')).not.toHaveTextContent('0s elapsed')
  expect(
    await screen.findByText('Display rack 4 items', undefined, { timeout: 3000 }),
  ).toBeInTheDocument()
  expect(screen.getAllByTestId('box')).toHaveLength(1)
})

test('shows the server failure message and lets the user retake the photo', async () => {
  createReturns(draft({ status: 'FAILED', lines: [], failureMessage: 'The photo is too dark.' }))
  render(<IntakeScreen />)

  await uploadPhoto()

  expect(await screen.findByRole('alert')).toHaveTextContent('The photo is too dark.')
  expect(screen.getByLabelText('Retake photo')).toBeEnabled()
})

test('starts a second upload when the same photo is selected after a failed draft', async () => {
  let createRequests = 0
  server.use(
    http.post('*/api/intake/drafts', () => {
      createRequests += 1
      return HttpResponse.json(
        draft({ status: 'FAILED', lines: [], failureMessage: 'The photo is too dark.' }),
        { status: 202 },
      )
    }),
  )
  const photo = new File(['x'], 'booth.jpg', { type: 'image/jpeg' })
  render(<IntakeScreen />)

  await userEvent.upload(screen.getByLabelText('Take booth photo'), photo)
  expect(await screen.findByRole('alert')).toHaveTextContent('The photo is too dark.')
  expect(createRequests).toBe(1)

  await userEvent.upload(screen.getByLabelText('Retake photo'), photo)

  await waitFor(() => expect(createRequests).toBe(2))
})

test('highlights lines that need review', async () => {
  createReturns(draft({ lines: [line({ needsReview: true })] }))
  render(<IntakeScreen />)

  await uploadPhoto()

  expect(await screen.findByText('Needs review')).toBeInTheDocument()
})

test('sends the edited quantity and category when confirming', async () => {
  createReturns(draft())
  let body: unknown
  let idempotencyKey: string | null = null
  server.use(
    http.post('*/api/intake/drafts/5/confirm', async ({ request }) => {
      body = await request.json()
      idempotencyKey = request.headers.get('Idempotency-Key')
      return HttpResponse.json([{ id: 1, label: 'Storage bin', totalQty: 6 }], { status: 201 })
    }),
  )
  render(<IntakeScreen />)
  await uploadPhoto()
  await screen.findByText('Display rack 4 items')

  const quantity = screen.getByLabelText('Display rack Quantity')
  await userEvent.clear(quantity)
  await userEvent.type(quantity, '6')
  await userEvent.selectOptions(screen.getByLabelText('Display rack Category'), 'Storage bin')
  await userEvent.click(screen.getByRole('button', { name: 'Register inventory' }))

  expect(await screen.findByText('Inventory registered.')).toBeInTheDocument()
  expect(screen.getByText('Storage bin 6 items')).toBeInTheDocument()
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
    draft({ lines: [line({ category: 'UNKNOWN', label: 'Unclassified', needsReview: true })] }),
  )
  render(<IntakeScreen />)

  await uploadPhoto()

  expect(await screen.findByText('Choose a category')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Register inventory' })).toBeDisabled()
  await userEvent.selectOptions(screen.getByLabelText('Unclassified Category'), 'Display rack')
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Register inventory' })).toBeEnabled(),
  )
})

test('shows an empty state when nothing was found', async () => {
  createReturns(draft({ lines: [] }))
  render(<IntakeScreen />)

  await uploadPhoto()

  expect(await screen.findByText(/No items found/)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Register inventory' })).not.toBeInTheDocument()
})

test('shows the server validation error when confirming is rejected', async () => {
  createReturns(draft())
  server.use(
    http.post('*/api/intake/drafts/5/confirm', () =>
      HttpResponse.json({ code: 'BAD_REQUEST', message: 'Invalid quantity.' }, { status: 400 }),
    ),
  )
  render(<IntakeScreen />)
  await uploadPhoto()
  await screen.findByText('Display rack 4 items')

  await userEvent.click(screen.getByRole('button', { name: 'Register inventory' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('Invalid quantity.')
})
