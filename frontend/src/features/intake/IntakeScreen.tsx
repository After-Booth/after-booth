import type { Condition, DraftCategory, IntakeStage } from '../../api/generated/model'
import { AiProgress } from '../../components/AiProgress'
import { AI_EXPECTED_SECONDS } from '../aiLatency'
import { CATEGORIES, CATEGORY_COLOR, CATEGORY_LABEL } from './categories'
import { problemOf, useIntake, type EditableLine } from './useIntake'

const STAGE_TEXT: Record<IntakeStage, string> = {
  ESTIMATE: 'Muse Spark가 사진에서 물품을 세는 중이에요…',
  SEGMENT: 'SAM 3.1이 물품마다 윤곽을 확인하는 중이에요…',
}

const CONDITION_LABEL: Record<Condition, string> = { GOOD: '양호', FAIR: '보통', POOR: '불량' }

const SIZE_FIELDS = [
  ['width', '가로'],
  ['depth', '깊이'],
  ['height', '높이'],
] as const

const fieldClass = 'mt-1 h-12 w-full rounded-lg border border-slate-300 bg-white px-3 text-base'

function countsByCategory(lines: EditableLine[]) {
  const totals = new Map<DraftCategory, number>()
  for (const line of lines) {
    const qty = Number(line.quantity)
    if (Number.isInteger(qty) && qty > 0) {
      totals.set(line.category, (totals.get(line.category) ?? 0) + qty)
    }
  }
  return [...totals]
}

export function IntakeScreen() {
  const {
    photoUrl,
    draft,
    lines,
    uploading,
    confirming,
    error,
    items,
    canConfirm,
    upload,
    editLine,
    confirm,
  } = useIntake()
  const processing = draft?.status === 'PROCESSING'
  const busy = uploading || processing || confirming
  const ready = draft?.status === 'READY' && !items

  return (
    <main className="mx-auto min-h-dvh max-w-md bg-white px-4 py-6 text-slate-900">
      <h1 className="text-xl font-bold">남은 물품 등록</h1>
      <p className="mt-1 text-sm text-slate-600">부스를 찍으면 물품을 자동으로 세어 드려요.</p>

      <label
        className={`mt-5 block rounded-xl py-4 text-center text-lg font-semibold text-white ${
          busy ? 'bg-slate-400' : 'bg-slate-900'
        }`}
      >
        {photoUrl ? '다시 찍기' : '부스 사진 찍기'}
        <input
          type="file"
          accept="image/jpeg,image/png"
          capture="environment"
          className="sr-only"
          disabled={busy}
          onChange={(e) => {
            const photo = e.currentTarget.files?.[0]
            e.currentTarget.value = ''
            if (photo) void upload(photo)
          }}
        />
      </label>

      {photoUrl && (
        <div className="relative mt-4 overflow-hidden rounded-xl bg-slate-100">
          <img src={photoUrl} alt="부스 사진" className="block w-full" />
          {ready &&
            lines.flatMap((line) =>
              line.boxes.map((box, i) => (
                <div
                  key={`${line.lineId}-${i}`}
                  data-testid="box"
                  className={`absolute border-2 ${CATEGORY_COLOR[line.category].box}`}
                  style={{
                    left: `${box.x * 100}%`,
                    top: `${box.y * 100}%`,
                    width: `${box.width * 100}%`,
                    height: `${box.height * 100}%`,
                  }}
                />
              )),
            )}
        </div>
      )}

      {(uploading || processing) && (
        <div className="mt-4">
          <AiProgress
            label={uploading ? '사진을 올리는 중이에요…' : STAGE_TEXT[draft?.stage ?? 'ESTIMATE']}
            expectedSeconds={AI_EXPECTED_SECONDS.intake}
          />
        </div>
      )}
      {error && (
        <p role="alert" className="mt-4 rounded-lg bg-red-50 p-3 text-red-700">
          {error}
        </p>
      )}
      {draft?.status === 'FAILED' && (
        <p role="alert" className="mt-4 rounded-lg bg-red-50 p-3 text-red-700">
          {draft.failureMessage ?? '사진을 분석하지 못했어요. 다시 찍어 주세요.'}
        </p>
      )}

      {ready && lines.length === 0 && (
        <p className="mt-4 rounded-lg bg-slate-100 p-3">
          물품을 찾지 못했어요. 부스 전체가 보이게 다시 찍어 주세요.
        </p>
      )}

      {ready && lines.length > 0 && (
        <section className="mt-4">
          <ul className="flex flex-wrap gap-2" aria-label="분류별 수량">
            {countsByCategory(lines).map(([category, total]) => (
              <li
                key={category}
                className="flex items-center gap-2 rounded-full bg-slate-100 px-3 py-1"
              >
                <span className={`size-3 rounded-full ${CATEGORY_COLOR[category].dot}`} />
                {CATEGORY_LABEL[category]} {total}개
              </li>
            ))}
          </ul>

          <ul className="mt-4 space-y-3">
            {lines.map((line) => {
              const problem = problemOf(line)
              return (
                <li
                  key={line.lineId}
                  className={`rounded-xl border p-3 ${
                    line.needsReview ? 'border-amber-500 bg-amber-50' : 'border-slate-200'
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="flex items-center gap-2 font-semibold">
                      <span
                        className={`size-3 rounded-full ${CATEGORY_COLOR[line.category].dot}`}
                      />
                      {line.originalLabel}
                    </span>
                    {line.needsReview && (
                      <span className="rounded bg-amber-500 px-2 py-0.5 text-sm font-semibold text-white">
                        확인 필요
                      </span>
                    )}
                  </div>

                  <div className="mt-2 grid grid-cols-2 gap-3">
                    <label className="text-sm">
                      {line.originalLabel} 분류
                      <select
                        className={fieldClass}
                        value={line.category}
                        onChange={(e) =>
                          editLine(line.lineId, { category: e.target.value as DraftCategory })
                        }
                      >
                        {CATEGORIES.map((c) => (
                          <option key={c} value={c}>
                            {CATEGORY_LABEL[c]}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="text-sm">
                      {line.originalLabel} 수량
                      <input
                        type="number"
                        inputMode="numeric"
                        min={1}
                        className={fieldClass}
                        value={line.quantity}
                        onChange={(e) => editLine(line.lineId, { quantity: e.target.value })}
                      />
                    </label>
                  </div>

                  <div className="mt-3 grid grid-cols-3 gap-3">
                    {SIZE_FIELDS.map(([key, name]) => (
                      <label key={key} className="text-sm">
                        {line.originalLabel} {name}(cm)
                        <input
                          type="number"
                          inputMode="numeric"
                          min={1}
                          className={fieldClass}
                          value={line[key]}
                          onChange={(e) => editLine(line.lineId, { [key]: e.target.value })}
                        />
                      </label>
                    ))}
                  </div>

                  <label className="mt-3 block text-sm">
                    {line.originalLabel} 상태
                    <select
                      className={fieldClass}
                      value={line.condition}
                      onChange={(e) =>
                        editLine(line.lineId, { condition: e.target.value as Condition })
                      }
                    >
                      {(Object.keys(CONDITION_LABEL) as Condition[]).map((c) => (
                        <option key={c} value={c}>
                          {CONDITION_LABEL[c]}
                        </option>
                      ))}
                    </select>
                  </label>

                  {problem && <p className="mt-2 text-sm font-semibold text-red-700">{problem}</p>}
                </li>
              )
            })}
          </ul>

          <button
            className="mt-5 h-14 w-full rounded-xl bg-emerald-600 text-lg font-semibold text-white disabled:bg-slate-300"
            disabled={!canConfirm || confirming}
            onClick={() => void confirm()}
          >
            {confirming ? '등록하는 중이에요…' : '재고로 등록'}
          </button>
        </section>
      )}

      {items && (
        <section className="mt-4 rounded-xl bg-emerald-50 p-4">
          <p className="font-semibold text-emerald-800">재고로 등록했어요.</p>
          <ul className="mt-2 space-y-1">
            {items.map((item) => (
              <li key={item.id}>
                {item.label} {item.totalQty}개
              </li>
            ))}
          </ul>
        </section>
      )}
    </main>
  )
}
