// TEMPLATE: screen test. Fix the API with MSW, act like a user, assert what the user sees.
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, expect, test } from 'vitest'
import { IntakeScreen } from './IntakeScreen'

const server = setupServer()
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

test('shows counts per category after a photo upload', async () => {
  server.use(
    http.post('*/api/intake/drafts', () =>
      HttpResponse.json({
        id: 1,
        items: [{ category: 'DISPLAY_RACK', label: 'Display rack', count: 4, needsReview: false }],
      }),
    ),
  )
  render(<IntakeScreen />)

  await userEvent.upload(
    screen.getByLabelText('Take booth photo'),
    new File(['x'], 'booth.jpg', { type: 'image/jpeg' }),
  )

  expect(await screen.findByText('Display rack 4 items')).toBeInTheDocument()
})

test('disables photo selection while analyzing', async () => {
  server.use(http.post('*/api/intake/drafts', () => new Promise<never>(() => {})))
  render(<IntakeScreen />)
  const input = screen.getByLabelText('Take booth photo')

  await userEvent.upload(input, new File(['x'], 'booth.jpg', { type: 'image/jpeg' }))

  expect(input).toBeDisabled()
})

test('asks to retake the photo when analysis fails', async () => {
  server.use(
    http.post('*/api/intake/drafts', () =>
      HttpResponse.json(
        { code: 'INTERNAL_ERROR', message: 'Unable to analyze the photo. Please retake it.' },
        { status: 500 },
      ),
    ),
  )
  render(<IntakeScreen />)

  await userEvent.upload(
    screen.getByLabelText('Take booth photo'),
    new File(['x'], 'booth.jpg', { type: 'image/jpeg' }),
  )

  expect(await screen.findByRole('alert')).toHaveTextContent('Please retake it')
})
