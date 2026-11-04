import { useEffect, useState } from 'react'

export function AiProgress({ label, expectedSeconds }: { label: string; expectedSeconds: number }) {
  const [startedAt] = useState(() => Date.now())
  const [elapsed, setElapsed] = useState(0)

  useEffect(() => {
    const timer = setInterval(() => setElapsed(Math.floor((Date.now() - startedAt) / 1000)), 250)
    return () => clearInterval(timer)
  }, [startedAt])

  const overdue = elapsed > expectedSeconds
  const progress = Math.min(95, Math.round(100 * (1 - Math.exp((-3 * elapsed) / expectedSeconds))))

  return (
    <div role="status" aria-live="polite" className="rounded-xl bg-emerald-50 p-4 text-emerald-900">
      <p className="font-semibold">{label}</p>
      <p className="mt-2 text-sm">
        {elapsed}s elapsed · Usually about {expectedSeconds}s
      </p>
      <div
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={progress}
        aria-valuetext={overdue ? 'Taking longer than usual…' : undefined}
        className="mt-3 h-2 overflow-hidden rounded-full bg-emerald-100"
      >
        <div
          className="h-full rounded-full bg-emerald-700 transition-[width] duration-300 motion-reduce:transition-none"
          style={{ width: `${progress}%` }}
        />
      </div>
      {overdue && <p className="mt-2 text-sm">Taking longer than usual…</p>}
    </div>
  )
}
