// TEMPLATE: screen component. Owns layout and states, delegates data to the hook.
// Presentational pieces go to src/components when a second screen needs them.
import { useIntake } from './useIntake'

export function IntakeScreen() {
  const { state, upload, confirm } = useIntake()

  return (
    <main className="mx-auto min-h-dvh max-w-md px-4 py-6">
      <h1 className="text-xl font-bold">Register leftover equipment</h1>

      <label className="mt-6 block rounded-xl bg-slate-900 py-4 text-center text-white">
        Take booth photo
        <input
          type="file"
          accept="image/*"
          capture="environment"
          className="sr-only"
          disabled={state.status === 'loading'}
          onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])}
        />
      </label>

      {state.status === 'loading' && <p className="mt-6">Counting items in the photo…</p>}
      {state.status === 'error' && (
        <p role="alert" className="mt-6 text-red-600">
          {state.message}
        </p>
      )}
      {state.status === 'confirmed' && <p className="mt-6">Inventory registered.</p>}
      {state.status === 'ready' && (
        <section className="mt-6">
          <ul>
            {state.draft.items.map((item) => (
              <li key={item.category}>
                {item.label} {item.count} items{item.needsReview && ' · Needs review'}
              </li>
            ))}
          </ul>
          <button
            className="mt-4 w-full rounded-xl bg-emerald-600 py-4 text-white"
            onClick={() => confirm(state.draft)}
          >
            Register inventory
          </button>
        </section>
      )}
    </main>
  )
}
