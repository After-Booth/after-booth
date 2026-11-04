// The orval fetch client resolves non-2xx responses too, so this turns them into a thrown ApiError.
// Screen code then handles every failure with a single try/catch.
export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

let accessToken: string | undefined
let generation = 0
let refresh: (() => Promise<boolean>) | undefined
let invalidate: (() => void) | undefined

export function setAccessToken(token?: string, sameSession = false) {
  accessToken = token
  if (!sameSession) generation += 1
}

export function getAccessToken() {
  return accessToken
}

export function installRefresh(handler: () => Promise<boolean>, onInvalid: () => void) {
  refresh = handler
  invalidate = onInvalid
}

export async function http<T>(url: string, options?: RequestInit): Promise<T> {
  const publicRequest =
    !url.startsWith('/api/') || url.startsWith('/api/auth/') || url === '/api/health'
  const authMutation =
    url === '/api/members/me/password' ||
    (url === '/api/members/me' && options?.method === 'DELETE')
  const send = () => {
    const headers = new Headers(options?.headers)
    if (!publicRequest && accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
    return fetch(url, { ...options, headers, credentials: 'same-origin', cache: 'no-store' })
  }
  const startedGeneration = generation
  const startedToken = accessToken
  let res = await send()
  if (
    res.status === 401 &&
    !publicRequest &&
    !authMutation &&
    accessToken &&
    refresh &&
    generation === startedGeneration
  ) {
    const renewed = accessToken !== startedToken ? true : await refresh()
    if (renewed && accessToken && generation === startedGeneration) {
      res = await send()
      if (res.status === 401 && generation === startedGeneration) invalidate?.()
    }
  }
  const body = res.status === 204 ? undefined : await res.json().catch(() => undefined)
  if (!res.ok) {
    throw new ApiError(
      res.status,
      body?.code ?? 'UNKNOWN',
      body?.message ?? 'Unable to process the request. Please try again shortly.',
    )
  }
  return { data: body, status: res.status, headers: res.headers } as T
}
