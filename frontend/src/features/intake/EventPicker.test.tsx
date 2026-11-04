import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import { EventPicker } from './EventPicker'

const created = {
  id: 9,
  name: 'Spring Expo',
  organizationId: 3,
  venue: 'COEX',
  teardownDeadline: '2026-11-07T18:00:00+09:00',
}
const keys: string[] = []

const server = setupServer(http.get('*/api/events', () => HttpResponse.json([])))
beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  keys.length = 0
})
afterAll(() => server.close())

async function fillForm() {
  await userEvent.click(screen.getByText('Create a new event'))
  await userEvent.type(screen.getByLabelText('Event name'), 'Spring Expo')
  await userEvent.type(screen.getByLabelText('Venue'), 'COEX')
  await userEvent.type(
    screen.getByLabelText('Teardown deadline (Asia/Seoul, KST)'),
    '2026-11-07T18:00',
  )
}

test('creates an event, selects it and reports the new id', async () => {
  let posted: { teardownDeadline: string } | undefined
  server.use(
    http.post('*/api/events', async ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key') ?? '')
      posted = (await request.json()) as { teardownDeadline: string }
      return HttpResponse.json(created, { status: 201 })
    }),
  )
  const onSelect = vi.fn()
  render(<EventPicker onSelect={onSelect} />)
  await fillForm()
  await userEvent.click(screen.getByRole('button', { name: 'Create event' }))
  expect(await screen.findByText('Event created. Add your photo now.')).toBeVisible()
  expect(onSelect).toHaveBeenCalledWith(9)
  expect(screen.getByLabelText('Event for registration')).toHaveValue('9')
  expect(keys).toHaveLength(1)
  expect(keys[0]).not.toBe('')
  // The deadline is typed in Korea time regardless of the browser's time zone.
  expect(posted?.teardownDeadline).toBe('2026-11-07T09:00:00.000Z')
})

test('keeps the typed event and reuses the idempotency key when a failed create is retried', async () => {
  let attempts = 0
  server.use(
    http.post('*/api/events', ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key') ?? '')
      attempts += 1
      return attempts === 1
        ? HttpResponse.json(
            { code: 'INTERNAL', message: 'Please try again shortly.' },
            { status: 500 },
          )
        : HttpResponse.json(created, { status: 201 })
    }),
  )
  const onSelect = vi.fn()
  render(<EventPicker onSelect={onSelect} />)
  await fillForm()
  await userEvent.click(screen.getByRole('button', { name: 'Create event' }))
  expect(await screen.findByText('Please try again shortly.')).toBeVisible()
  expect(onSelect).not.toHaveBeenCalled()
  expect(screen.getByLabelText('Event name')).toHaveValue('Spring Expo')

  await userEvent.click(screen.getByRole('button', { name: 'Create event' }))
  expect(await screen.findByText('Event created. Add your photo now.')).toBeVisible()
  expect(keys).toHaveLength(2)
  expect(keys[1]).toBe(keys[0])
})

test('shows a message when the event list cannot be loaded', async () => {
  server.use(http.get('*/api/events', () => HttpResponse.error()))
  render(<EventPicker onSelect={vi.fn()} />)
  expect(await screen.findByText('Unable to load events.')).toBeVisible()
})
