import { StrictMode } from 'react'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import {
  cancelReservation,
  getReservation,
  getReuseStats,
  listEvents,
  listReservations,
  resolveHandover,
  submitHandoverPhoto,
} from '../../api/generated/afterbooth'
import type { HandoverResult, Reservation } from '../../api/generated/model'
import { ApiError } from '../../api/http'
import { HandoverScreen } from './HandoverScreen'

vi.mock('../../api/generated/afterbooth', () => ({
  cancelReservation: vi.fn(),
  getReservation: vi.fn(),
  getReuseStats: vi.fn(),
  listEvents: vi.fn(),
  listReservations: vi.fn(),
  resolveHandover: vi.fn(),
  submitHandoverPhoto: vi.fn(),
}))

const reservation: Reservation = {
  id: 7,
  requestId: 2,
  organizationId: 1,
  itemId: 3,
  itemLabel: '진열대',
  quantity: 3,
  status: 'CONFIRMED',
  holdExpiresAt: '2099-11-01T10:00:00+09:00',
  pickupDeadline: '2099-11-01T23:00:00+09:00',
  createdAt: '2099-11-01T09:00:00+09:00',
}
const completed: Reservation = { ...reservation, status: 'HANDED_OVER', handedOverQty: 2 }
const boxes = [
  { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
  { x: 0.6, y: 0.1, width: 0.2, height: 0.3 },
]
const mismatch: HandoverResult = {
  reservation,
  status: 'MISMATCH',
  expectedQty: 3,
  countedQty: 2,
  boxes,
}
const ok = <T,>(data: T) => ({ data, status: 200 as const, headers: new Headers() })
const conflict = () => new ApiError(409, 'IDEMPOTENCY_CONFLICT', '처리 중이에요.')
const invalidTransition = () =>
  new ApiError(409, 'INVALID_TRANSITION', '예약 상태를 확인해 주세요.')
const createUrl = vi.fn<(photo: Blob) => string>()
const revokeUrl = vi.fn<(url: string) => void>()

beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  let serial = 0
  createUrl.mockImplementation(() => `blob:handover-${++serial}`)
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = createUrl
      static revokeObjectURL = revokeUrl
    },
  )
  vi.mocked(listReservations).mockResolvedValue(ok([reservation]))
  vi.mocked(listEvents).mockResolvedValue(ok([]))
  vi.mocked(getReuseStats).mockResolvedValue(ok({ totalReused: 0, byCategory: [], byEvent: [] }))
  vi.mocked(getReservation).mockResolvedValue(ok(reservation))
  vi.mocked(submitHandoverPhoto).mockResolvedValue(ok(mismatch))
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

async function mount() {
  const view = render(
    <StrictMode>
      <HandoverScreen />
    </StrictMode>,
  )
  await act(() => vi.advanceTimersByTimeAsync(0))
  fireEvent.change(screen.getByLabelText('확정된 예약'), { target: { value: '7' } })
  return view
}

async function upload(name = 'handover.jpg') {
  await act(async () => {
    fireEvent.change(screen.getByLabelText(/인계 사진 .*촬영|인계 사진 촬영 또는 업로드/), {
      target: { files: [new File(['photo'], name, { type: 'image/jpeg' })] },
    })
  })
}

async function exhaustConflicts() {
  await upload()
  await act(() => vi.advanceTimersByTimeAsync(1000))
  expect(submitHandoverPhoto).toHaveBeenCalledTimes(2)
  await act(() => vi.advanceTimersByTimeAsync(2000))
  expect(submitHandoverPhoto).toHaveBeenCalledTimes(3)
  await act(() => vi.advanceTimersByTimeAsync(4000))
  expect(submitHandoverPhoto).toHaveBeenCalledTimes(4)
}

test('recovers an earlier completion before the next new-key photo request', async () => {
  vi.mocked(submitHandoverPhoto).mockRejectedValue(conflict())
  await mount()
  await exhaustConflicts()
  expect(screen.queryByText('인계를 완료했어요')).not.toBeInTheDocument()
  const keys = vi.mocked(submitHandoverPhoto).mock.calls.map((call) => call[2]['Idempotency-Key'])
  expect(new Set(keys).size).toBe(1)
  vi.mocked(getReservation).mockResolvedValue(ok(completed))
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  })
  expect(screen.getByText('진열대 2개가 다시 사용돼요.')).toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(submitHandoverPhoto).toHaveBeenCalledTimes(4)
  expect(resolveHandover).not.toHaveBeenCalled()
  expect(cancelReservation).not.toHaveBeenCalled()
})

test('recovers completion directly when the final conflict retry ends', async () => {
  vi.mocked(submitHandoverPhoto).mockRejectedValue(conflict())
  vi.mocked(getReservation).mockResolvedValue(ok(completed))
  await mount()
  await exhaustConflicts()
  expect(screen.getByText('진열대 2개가 다시 사용돼요.')).toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(getReservation).toHaveBeenCalledTimes(1)
})

test.each(['RELEASED', 'EXPIRED'] as const)(
  'does not send a new photo mutation when retry preflight finds %s',
  async (status) => {
    vi.mocked(submitHandoverPhoto).mockRejectedValue(conflict())
    await mount()
    await exhaustConflicts()
    vi.mocked(getReservation).mockResolvedValue(ok({ ...reservation, status }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    })
    expect(submitHandoverPhoto).toHaveBeenCalledTimes(4)
    expect(screen.queryByText('인계를 완료했어요')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  },
)

test('keeps recovery retryable after a failed read without sending another mutation', async () => {
  vi.mocked(submitHandoverPhoto).mockRejectedValue(conflict())
  vi.mocked(getReservation).mockRejectedValue(new TypeError('Network failed'))
  await mount()
  await exhaustConflicts()
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  })
  expect(submitHandoverPhoto).toHaveBeenCalledTimes(4)
  expect(screen.getByRole('button', { name: '다시 시도' })).toBeEnabled()
  vi.mocked(getReservation).mockResolvedValue(ok(completed))
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  })
  expect(screen.getByText('진열대 2개가 다시 사용돼요.')).toBeInTheDocument()
  expect(submitHandoverPhoto).toHaveBeenCalledTimes(4)
})

test('recovers a completion racing the preflight and a new-key INVALID_TRANSITION', async () => {
  vi.mocked(submitHandoverPhoto).mockRejectedValue(conflict())
  await mount()
  await exhaustConflicts()
  vi.mocked(getReservation).mockResolvedValueOnce(ok(reservation)).mockResolvedValue(ok(completed))
  vi.mocked(submitHandoverPhoto).mockRejectedValue(invalidTransition())
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  })
  expect(screen.getByText('진열대 2개가 다시 사용돼요.')).toBeInTheDocument()
  const calls = vi.mocked(submitHandoverPhoto).mock.calls
  expect(calls).toHaveLength(5)
  expect(calls[4][2]['Idempotency-Key']).not.toBe(calls[0][2]['Idempotency-Key'])
})

test.each(['network', 500, 502, 504] as const)(
  'replays photo evidence with the retained key after conflict recovery and an ambiguous %s failure',
  async (failure) => {
    vi.mocked(submitHandoverPhoto).mockRejectedValue(conflict())
    await mount()
    await exhaustConflicts()
    const storedResult: HandoverResult = {
      ...mismatch,
      reservation: completed,
      status: 'COMPLETED',
    }
    vi.mocked(submitHandoverPhoto)
      .mockRejectedValueOnce(
        failure === 'network'
          ? new TypeError('Connection lost')
          : new ApiError(failure, 'INTERNAL_ERROR', '요청 결과를 확인하지 못했어요.'),
      )
      .mockResolvedValue(ok(storedResult))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    })
    expect(submitHandoverPhoto).toHaveBeenCalledTimes(5)
    expect(getReservation).toHaveBeenCalledTimes(2)
    vi.mocked(getReservation).mockResolvedValue(ok(completed))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    })
    expect(submitHandoverPhoto).toHaveBeenCalledTimes(6)
    const calls = vi.mocked(submitHandoverPhoto).mock.calls
    expect(calls[5][2]['Idempotency-Key']).toBe(calls[4][2]['Idempotency-Key'])
    expect(calls[4][2]['Idempotency-Key']).not.toBe(calls[0][2]['Idempotency-Key'])
    expect(getReservation).toHaveBeenCalledTimes(2)
    expect(screen.getByText('진열대 2개가 다시 사용돼요.')).toBeInTheDocument()
    expect(screen.getAllByTestId('handover-box')).toHaveLength(2)
  },
)

test.each<Reservation>([
  reservation,
  { ...reservation, status: 'RELEASED' },
  { ...reservation, status: 'EXPIRED' },
  { ...reservation, status: 'HANDED_OVER' },
])(
  'does not invent completion when the server returns $status without completion evidence',
  async (current) => {
    vi.mocked(submitHandoverPhoto).mockRejectedValue(invalidTransition())
    vi.mocked(getReservation).mockResolvedValue(ok(current))
    await mount()
    await upload()
    expect(getReservation).toHaveBeenCalledWith(7)
    expect(screen.queryByText('인계를 완료했어요')).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('예약 상태를 확인해 주세요.')
    expect(screen.getByText('총 0개 재사용')).toBeInTheDocument()
    expect(submitHandoverPhoto).toHaveBeenCalledTimes(1)
  },
)

test('keeps an uncompleted reservation uncompleted after exhausted conflict retries', async () => {
  vi.mocked(submitHandoverPhoto).mockRejectedValue(conflict())
  await mount()
  await exhaustConflicts()
  expect(getReservation).toHaveBeenCalledWith(7)
  expect(screen.queryByText('인계를 완료했어요')).not.toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('처리 중이에요.')
  expect(screen.getByText('예약 수량 3개', { exact: false })).toBeInTheDocument()
  expect(screen.getByText('총 0개 재사용')).toBeInTheDocument()
})

test('stops scheduled conflict retries when unmounted during backoff', async () => {
  vi.mocked(submitHandoverPhoto).mockRejectedValue(conflict())
  const view = await mount()
  await upload()
  expect(submitHandoverPhoto).toHaveBeenCalledTimes(1)
  view.unmount()
  await act(() => vi.advanceTimersByTimeAsync(15000))
  expect(submitHandoverPhoto).toHaveBeenCalledTimes(1)
  expect(getReservation).not.toHaveBeenCalled()
})

test('ignores an old mutation response after leaving and reentering the screen', async () => {
  let answer!: (response: ReturnType<typeof ok<HandoverResult>>) => void
  vi.mocked(submitHandoverPhoto).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        answer = resolve
      }),
  )
  const first = await mount()
  await upload()
  first.unmount()
  await mount()
  const reads = vi.mocked(listReservations).mock.calls.length
  await act(async () => answer(ok({ ...mismatch, reservation: completed, status: 'COMPLETED' })))
  expect(screen.queryByText('인계를 완료했어요')).not.toBeInTheDocument()
  expect(screen.queryByAltText('인계 사진')).not.toBeInTheDocument()
  expect(listReservations).toHaveBeenCalledTimes(reads)
})

test('shows a handed-over reservation from the server when reentering', async () => {
  vi.mocked(listReservations).mockResolvedValue(ok([completed]))
  await mount()
  expect(screen.getByText('진열대 2개가 다시 사용돼요.')).toBeInTheDocument()
  expect(screen.queryByLabelText('인계 사진 촬영 또는 업로드')).not.toBeInTheDocument()
  expect(submitHandoverPhoto).not.toHaveBeenCalled()
})

test.each(['MISMATCH', 'COMPLETED'] as const)(
  'shows the selected photo and normalized boxes for %s',
  async (status) => {
    vi.mocked(submitHandoverPhoto).mockResolvedValue(
      ok({
        ...mismatch,
        status,
        reservation: status === 'COMPLETED' ? completed : reservation,
      }),
    )
    await mount()
    await upload()
    expect(screen.getByAltText('인계 사진')).toHaveAttribute('src', 'blob:handover-1')
    const overlays = screen.getAllByTestId('handover-box')
    expect(overlays).toHaveLength(2)
    expect(overlays[0]).toHaveStyle({ left: '10%', top: '20%', width: '30%', height: '40%' })
    expect(overlays[1]).toHaveStyle({ left: '60%', top: '10%', width: '20%', height: '30%' })
  },
)

test('shows a zero-box photo while preserving manual resolution and retake', async () => {
  vi.mocked(submitHandoverPhoto).mockResolvedValue(ok({ ...mismatch, boxes: [], countedQty: 0 }))
  await mount()
  await upload()
  expect(screen.getByAltText('인계 사진')).toBeInTheDocument()
  expect(screen.queryByTestId('handover-box')).not.toBeInTheDocument()
  expect(screen.getByText('사진에 표시할 수량 박스가 없어요.')).toBeInTheDocument()
  expect(screen.getByLabelText('실제 받은 수량')).toBeInTheDocument()
  expect(screen.getByLabelText('인계 사진 다시 촬영')).toBeInTheDocument()
})

test.each([{ localBoxes: boxes }, { localBoxes: [] }])(
  'retains only local upload evidence when resolution returns boxes from another photo: $localBoxes',
  async ({ localBoxes }) => {
    vi.mocked(submitHandoverPhoto).mockResolvedValue(ok({ ...mismatch, boxes: localBoxes }))
    vi.mocked(resolveHandover).mockResolvedValue(
      ok({
        ...mismatch,
        status: 'COMPLETED',
        reservation: completed,
        boxes: [{ x: 0.7, y: 0.6, width: 0.1, height: 0.2 }],
      }),
    )
    await mount()
    await upload()
    fireEvent.change(screen.getByLabelText('실제 받은 수량'), { target: { value: '2' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '이 수량으로 인계 완료' }))
    })
    expect(screen.getByText('진열대 2개가 다시 사용돼요.')).toBeInTheDocument()
    expect(screen.getByAltText('인계 사진')).toHaveAttribute('src', 'blob:handover-1')
    expect(screen.queryAllByTestId('handover-box')).toHaveLength(localBoxes.length)
    if (localBoxes.length) {
      expect(screen.getAllByTestId('handover-box')[0]).toHaveStyle({
        left: '10%',
        top: '20%',
        width: '30%',
        height: '40%',
      })
    } else {
      expect(screen.getByText('사진에 표시할 수량 박스가 없어요.')).toBeInTheDocument()
    }
  },
)

test('revokes replaced and unmounted photos and clears old results immediately on retake', async () => {
  const view = await mount()
  await upload()
  let answer!: (response: ReturnType<typeof ok<HandoverResult>>) => void
  vi.mocked(submitHandoverPhoto).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        answer = resolve
      }),
  )
  await upload('retake.jpg')
  expect(revokeUrl).toHaveBeenCalledWith('blob:handover-1')
  expect(screen.getByAltText('인계 사진')).toHaveAttribute('src', 'blob:handover-2')
  expect(screen.queryByTestId('handover-box')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('실제 받은 수량')).not.toBeInTheDocument()
  await act(async () => answer(ok({ ...mismatch, boxes: [], countedQty: 0 })))
  view.unmount()
  expect(revokeUrl).toHaveBeenCalledWith('blob:handover-2')
})

test('clears the photo and old result when choosing a different reservation', async () => {
  vi.mocked(listReservations).mockResolvedValue(ok([reservation, { ...reservation, id: 8 }]))
  await mount()
  await upload()
  fireEvent.change(screen.getByLabelText('확정된 예약'), { target: { value: '8' } })
  expect(revokeUrl).toHaveBeenCalledWith('blob:handover-1')
  expect(screen.queryByAltText('인계 사진')).not.toBeInTheDocument()
  expect(screen.queryByTestId('handover-box')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('실제 받은 수량')).not.toBeInTheDocument()
})
