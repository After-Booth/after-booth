import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { AiProgress } from './AiProgress'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

test('shows elapsed time and a bounded estimate without claiming completion', () => {
  vi.useFakeTimers()
  render(<AiProgress label="Counting items in the photo…" expectedSeconds={8} />)
  expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
  expect(screen.getByText('0s elapsed · Usually about 8s')).toBeInTheDocument()
  expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '0')

  act(() => vi.advanceTimersByTime(8000))
  expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '95')
  expect(screen.queryByText('Taking longer than usual…')).not.toBeInTheDocument()

  act(() => vi.advanceTimersByTime(1000))
  expect(screen.getByText('Taking longer than usual…')).toBeInTheDocument()
  act(() => vi.advanceTimersByTime(60_000))
  expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '95')
})

test('keeps elapsed time when the stage label changes and releases its timer on unmount', () => {
  vi.useFakeTimers()
  const { rerender, unmount } = render(<AiProgress label="Uploading photo…" expectedSeconds={8} />)
  act(() => vi.advanceTimersByTime(2000))
  rerender(<AiProgress label="Refining item outlines…" expectedSeconds={8} />)
  expect(screen.getByRole('status')).toHaveTextContent('Refining item outlines…')
  expect(screen.getByText('2s elapsed · Usually about 8s')).toBeInTheDocument()
  unmount()
  expect(vi.getTimerCount()).toBe(0)
})

test('disables progress transitions for reduced motion', () => {
  render(<AiProgress label="Structuring requirements…" expectedSeconds={3} />)
  expect(screen.getByRole('progressbar').firstElementChild).toHaveClass(
    'motion-reduce:transition-none',
  )
})
