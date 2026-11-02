import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, beforeAll, expect, test } from 'vitest'
import App from './App'

const server = setupServer(
  http.get('*/api/health', () => HttpResponse.json({ status: 'UP', aiMode: 'replay' })),
)

beforeAll(() => server.listen())
afterAll(() => server.close())

test('shows the server status in Korean', async () => {
  render(<App />)
  expect(await screen.findByText('서버 UP · AI replay')).toBeInTheDocument()
})

test('puts the photo capture first, links the screens and keeps one main landmark', async () => {
  render(<App />)
  const capture = screen.getByLabelText('부스 사진 찍기')
  const health = await screen.findByText('서버 UP · AI replay')
  expect(capture.compareDocumentPosition(health) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(screen.getAllByRole('main')).toHaveLength(1)
  expect(screen.getByRole('link', { name: '물품 픽업 요청하기' })).toHaveAttribute(
    'href',
    '#/request',
  )
  expect(screen.getByRole('link', { name: '배정 보드' })).toHaveAttribute('href', '#/allocation')
  expect(screen.getByRole('link', { name: '물품 인계 및 재사용 통계' })).toHaveAttribute(
    'href',
    '#/handover',
  )
})
