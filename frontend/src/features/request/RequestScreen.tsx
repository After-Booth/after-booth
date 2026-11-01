import { useState } from 'react'
import { AllocationProposal } from '../allocation/AllocationProposal'
import type { ItemCategory, VehicleType } from '../../api/generated/model'
import { seoulInput, useRequest } from './useRequest'

const categories: Record<ItemCategory, string> = {
  DISPLAY_RACK: '진열대',
  STORAGE_BIN: '수납함',
  FOLDING_TABLE: '접이식 테이블',
}
const vehicles: Record<VehicleType, string> = {
  SEDAN: '승용차',
  SUV: 'SUV',
  VAN: '밴',
  TRUCK_1T: '1톤 트럭',
}
const inputClass =
  'mt-2 block min-h-12 w-full rounded-xl border border-slate-300 bg-white px-3 py-2 text-slate-900 focus:outline-2 focus:outline-emerald-700 disabled:opacity-50'
const buttonClass =
  'min-h-12 rounded-xl bg-emerald-800 px-5 py-3 font-semibold text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700 disabled:opacity-40'

export function RequestScreen() {
  const request = useRequest()
  const [text, setText] = useState('')
  const { status, fields, interpretation, saved } = request
  const busy = ['starting', 'recording', 'interpreting', 'saving'].includes(status)
  const editable = !!interpretation && status !== 'empty' && status !== 'saved'
  const missing = (field: string) =>
    interpretation?.missingFields.some((name) => name === field) ?? false
  const mark = (field: string) =>
    missing(field) ? (
      <span id={`${field}-hint`} className="ml-2 text-sm font-medium text-amber-800">
        확인 필요 · 직접 입력해 주세요
      </span>
    ) : null
  const attributes = (field: string) => ({
    'aria-describedby': missing(field) ? `${field}-hint` : undefined,
    className: `${inputClass} ${missing(field) ? 'border-amber-600 bg-amber-50' : ''}`,
  })

  return (
    <main className="min-h-dvh bg-stone-50 px-4 py-8 text-slate-900 sm:py-12">
      <div className="mx-auto max-w-xl space-y-7">
        <header>
          <p className="text-sm font-semibold tracking-widest text-emerald-800">
            애프터부스 · 물품 이어받기
          </p>
          <h1 className="mt-3 text-3xl font-bold tracking-tight">다음 행사에 필요한 물품</h1>
          <p className="mt-3 leading-relaxed text-slate-600">
            필요한 물품과 가져갈 차량을 알려 주세요.
            <br />
            말씀하신 내용을 확인한 뒤 요청을 저장해요.
          </p>
        </header>

        <section className="rounded-2xl border border-stone-200 bg-white p-5">
          <label htmlFor="organization" className="font-semibold">
            1. 받는 단체
          </label>
          <select
            id="organization"
            className={inputClass}
            value={request.organizationId}
            disabled={busy || status === 'saved' || request.catalogStatus !== 'ready'}
            onChange={(event) => request.setOrganizationId(event.target.value)}
          >
            <option value="">단체를 직접 선택해 주세요</option>
            {request.organizations.map((organization) => (
              <option key={organization.id} value={organization.id}>
                {organization.name}
              </option>
            ))}
          </select>
          <p className="mt-2 text-sm text-slate-500">
            로그인 없이 선택해요. 음성으로는 단체가 바뀌지 않아요.
          </p>
          {request.catalogStatus === 'loading' && (
            <p role="status">단체 목록을 불러오는 중이에요…</p>
          )}
          {request.catalogStatus === 'error' && (
            <p role="alert" className="mt-2 text-red-700">
              단체 목록을 불러오지 못했어요. 페이지를 새로고침해 주세요.
            </p>
          )}
          {request.catalogStatus === 'ready' && !request.organizations.length && (
            <p className="mt-2 text-amber-800">선택할 단체가 아직 없어요.</p>
          )}
        </section>

        <section
          aria-labelledby="describe-heading"
          className="rounded-2xl border border-stone-200 bg-white p-5"
        >
          <h2 id="describe-heading" className="font-semibold">
            2. 어떤 물품이 필요한가요?
          </h2>
          <p className="mt-2 text-sm leading-relaxed text-slate-600">
            예: 주말 행사에 쓸 진열대 세 개가 필요해요. 승용차로 가져갈게요.
          </p>
          <button
            type="button"
            aria-pressed={status === 'recording'}
            className={`${buttonClass} mt-5 w-full ${status === 'recording' ? 'bg-red-700' : ''}`}
            disabled={(busy && status !== 'recording') || status === 'saved'}
            onClick={() => void request.toggleRecording()}
          >
            {status === 'recording'
              ? '녹음 끝내고 해석하기'
              : status === 'starting'
                ? '마이크 연결 중…'
                : '마이크로 말하기'}
          </button>
          <p className="mt-2 text-center text-sm text-slate-500">
            한 번 눌러 시작하고 다시 눌러 끝내요 · 최대 60초
          </p>
          {status === 'recording' && (
            <p role="status" className="mt-3 text-center font-semibold text-red-700">
              녹음 중 {request.elapsed} / 60초
            </p>
          )}
          <form
            className="mt-6 border-t border-stone-200 pt-5"
            onSubmit={(event) => {
              event.preventDefault()
              request.interpretText(text)
            }}
          >
            <label htmlFor="request-text" className="text-sm font-semibold">
              글로 입력하기
            </label>
            <textarea
              id="request-text"
              className={inputClass}
              rows={3}
              maxLength={500}
              value={text}
              disabled={busy || status === 'saved'}
              onChange={(event) => setText(event.target.value)}
              placeholder="물품, 수량, 차량, 픽업 시간, 사용 목적을 알려 주세요"
            />
            <p className="mt-1 text-right text-xs text-slate-500">{text.length} / 500자</p>
            <button
              className={`${buttonClass} mt-3 w-full`}
              disabled={busy || !text.trim() || status === 'saved'}
            >
              입력한 내용 해석하기
            </button>
          </form>
        </section>

        <div aria-live="polite">
          {status === 'interpreting' && (
            <p role="status" className="rounded-xl bg-emerald-50 p-5 text-emerald-900">
              말씀하신 조건을 정리하고 있어요…
            </p>
          )}
          {status === 'saving' && <p role="status">요청을 저장하고 있어요…</p>}
          {status === 'idle' && (
            <p className="text-center text-sm text-slate-500">
              아직 요청 내용이 없어요. 음성이나 글로 시작해 주세요.
            </p>
          )}
          {status === 'empty' && (
            <p className="rounded-xl bg-amber-50 p-5 text-amber-900">
              들을 수 있는 내용이 없었어요. 다시 말하거나 글로 입력해 주세요.
            </p>
          )}
        </div>
        {request.message && (
          <p role="alert" className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-800">
            {request.message}
          </p>
        )}

        {editable && (
          <section
            className="rounded-2xl border border-emerald-200 bg-white p-5"
            aria-labelledby="review-heading"
          >
            <h2 id="review-heading" className="text-xl font-bold">
              3. 조건을 확인해 주세요
            </h2>
            <p className="mt-2 text-sm text-slate-600">
              모든 조건을 수정할 수 있어요. 확인 후 저장해 주세요.
            </p>
            <div className="my-5 rounded-xl bg-stone-50 p-4">
              <h3 className="text-xs font-semibold text-slate-500">입력 원문</h3>
              <p className="mt-2 whitespace-pre-wrap">
                {interpretation.transcript || '인식된 말이 없어요.'}
              </p>
            </div>
            <form
              className="space-y-5"
              onSubmit={(event) => {
                event.preventDefault()
                void request.submit()
              }}
            >
              <fieldset disabled={busy} className="space-y-5">
                <div>
                  <label htmlFor="category">물품 종류{mark('category')}</label>
                  <select
                    id="category"
                    required
                    {...attributes('category')}
                    value={fields.category}
                    onChange={(event) =>
                      request.setFields({ ...fields, category: event.target.value as ItemCategory })
                    }
                  >
                    <option value="">선택해 주세요</option>
                    {Object.entries(categories).map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label htmlFor="quantity">수량{mark('quantity')}</label>
                  <input
                    id="quantity"
                    type="number"
                    required
                    min={1}
                    step={1}
                    {...attributes('quantity')}
                    value={Number.isNaN(fields.quantity) ? '' : fields.quantity}
                    onChange={(event) =>
                      request.setFields({ ...fields, quantity: event.target.valueAsNumber })
                    }
                  />
                </div>
                <div>
                  <label htmlFor="vehicleType">가져갈 차량{mark('vehicleType')}</label>
                  <select
                    id="vehicleType"
                    required
                    {...attributes('vehicleType')}
                    value={fields.vehicleType}
                    onChange={(event) =>
                      request.setFields({
                        ...fields,
                        vehicleType: event.target.value as VehicleType,
                      })
                    }
                  >
                    <option value="">선택해 주세요</option>
                    {Object.entries(vehicles).map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label htmlFor="pickupDeadline">
                    픽업 가능 시각 (한국 시간, Asia/Seoul){mark('pickupDeadline')}
                  </label>
                  <input
                    id="pickupDeadline"
                    type="datetime-local"
                    {...attributes('pickupDeadline')}
                    value={fields.pickupDeadline}
                    onChange={(event) =>
                      request.setFields({ ...fields, pickupDeadline: event.target.value })
                    }
                  />
                </div>
                <div>
                  <label htmlFor="purpose">사용 목적{mark('purpose')}</label>
                  <input
                    id="purpose"
                    {...attributes('purpose')}
                    value={fields.purpose}
                    onChange={(event) =>
                      request.setFields({ ...fields, purpose: event.target.value })
                    }
                  />
                </div>
                <button
                  className={`${buttonClass} w-full`}
                  disabled={
                    !request.organizationId ||
                    !fields.category ||
                    !fields.vehicleType ||
                    !Number.isInteger(fields.quantity) ||
                    fields.quantity < 1
                  }
                >
                  픽업 요청 저장
                </button>
              </fieldset>
            </form>
          </section>
        )}

        {saved && (
          <section
            role="status"
            className="rounded-2xl border border-emerald-300 bg-emerald-50 p-6"
          >
            <h2 className="text-xl font-bold text-emerald-900">픽업 요청을 저장했어요</h2>
            <p className="mt-2 text-sm">
              요청 번호 #{saved.id} ·{' '}
              {
                request.organizations.find(
                  (organization) => organization.id === saved.organizationId,
                )?.name
              }
            </p>
            <dl className="mt-4 grid grid-cols-[auto_1fr] gap-x-5 gap-y-3">
              <dt>물품</dt>
              <dd>
                {categories[saved.category]} {saved.quantity}개
              </dd>
              <dt>차량</dt>
              <dd>{vehicles[saved.vehicleType]}</dd>
              <dt>픽업 시각</dt>
              <dd>
                {saved.pickupDeadline
                  ? `${seoulInput(saved.pickupDeadline).replace('T', ' ')} (한국 시간)`
                  : '미정'}
              </dd>
              <dt>사용 목적</dt>
              <dd>{saved.purpose || '미입력'}</dd>
            </dl>
            {saved.transcript && (
              <p className="mt-4 whitespace-pre-wrap text-sm text-slate-600">
                입력 원문: {saved.transcript}
              </p>
            )}
          </section>
        )}
        {saved && <AllocationProposal key={saved.id} request={saved} />}
      </div>
    </main>
  )
}
