import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { AiProgress } from './AiProgress'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

test('shows elapsed time and a bounded estimate without claiming completion', () => {
  vi.useFakeTimers()
  render(<AiProgress label="사진에서 물품을 세는 중이에요…" expectedSeconds={8} />)
  expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
  expect(screen.getByText('0초 경과 · 보통 약 8초 걸려요')).toBeInTheDocument()
  expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '0')

  act(() => vi.advanceTimersByTime(8000))
  expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '95')
  expect(screen.queryByText('평소보다 조금 더 걸리고 있어요…')).not.toBeInTheDocument()

  act(() => vi.advanceTimersByTime(1000))
  expect(screen.getByText('평소보다 조금 더 걸리고 있어요…')).toBeInTheDocument()
  act(() => vi.advanceTimersByTime(60_000))
  expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '95')
})

test('keeps elapsed time when the stage label changes and releases its timer on unmount', () => {
  vi.useFakeTimers()
  const { rerender, unmount } = render(
    <AiProgress label="사진을 올리는 중이에요…" expectedSeconds={8} />,
  )
  act(() => vi.advanceTimersByTime(2000))
  rerender(<AiProgress label="물품 윤곽을 다듬는 중이에요…" expectedSeconds={8} />)
  expect(screen.getByRole('status')).toHaveTextContent('물품 윤곽을 다듬는 중이에요…')
  expect(screen.getByText('2초 경과 · 보통 약 8초 걸려요')).toBeInTheDocument()
  unmount()
  expect(vi.getTimerCount()).toBe(0)
})

test('disables progress transitions for reduced motion', () => {
  render(<AiProgress label="조건을 정리하고 있어요…" expectedSeconds={3} />)
  expect(screen.getByRole('progressbar').firstElementChild).toHaveClass(
    'motion-reduce:transition-none',
  )
})
