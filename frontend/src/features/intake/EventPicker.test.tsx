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
  await userEvent.click(screen.getByText('새 행사 만들기'))
  await userEvent.type(screen.getByLabelText('행사 이름'), 'Spring Expo')
  await userEvent.type(screen.getByLabelText('장소'), 'COEX')
  await userEvent.type(screen.getByLabelText('철수 마감 시간'), '2026-11-07T18:00')
}

test('creates an event, selects it and reports the new id', async () => {
  server.use(
    http.post('*/api/events', ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key') ?? '')
      return HttpResponse.json(created, { status: 201 })
    }),
  )
  const onSelect = vi.fn()
  render(<EventPicker onSelect={onSelect} />)
  await fillForm()
  await userEvent.click(screen.getByRole('button', { name: '행사 만들기' }))
  expect(await screen.findByText('행사를 만들었어요. 이제 사진을 등록해 주세요.')).toBeVisible()
  expect(onSelect).toHaveBeenCalledWith(9)
  expect(screen.getByLabelText('등록할 행사')).toHaveValue('9')
  expect(keys).toHaveLength(1)
  expect(keys[0]).not.toBe('')
})

test('keeps the typed event and reuses the idempotency key when a failed create is retried', async () => {
  let attempts = 0
  server.use(
    http.post('*/api/events', ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key') ?? '')
      attempts += 1
      return attempts === 1
        ? HttpResponse.json(
            { code: 'INTERNAL', message: '잠시 후 다시 시도해 주세요.' },
            { status: 500 },
          )
        : HttpResponse.json(created, { status: 201 })
    }),
  )
  const onSelect = vi.fn()
  render(<EventPicker onSelect={onSelect} />)
  await fillForm()
  await userEvent.click(screen.getByRole('button', { name: '행사 만들기' }))
  expect(await screen.findByText('잠시 후 다시 시도해 주세요.')).toBeVisible()
  expect(onSelect).not.toHaveBeenCalled()
  expect(screen.getByLabelText('행사 이름')).toHaveValue('Spring Expo')

  await userEvent.click(screen.getByRole('button', { name: '행사 만들기' }))
  expect(await screen.findByText('행사를 만들었어요. 이제 사진을 등록해 주세요.')).toBeVisible()
  expect(keys).toHaveLength(2)
  expect(keys[1]).toBe(keys[0])
})

test('shows a message when the event list cannot be loaded', async () => {
  server.use(http.get('*/api/events', () => HttpResponse.error()))
  render(<EventPicker onSelect={vi.fn()} />)
  expect(await screen.findByText('행사 목록을 불러오지 못했어요.')).toBeVisible()
})
