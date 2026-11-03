import {
  getCsrfToken,
  loginMember,
  refreshSession,
  getCurrentMember,
  logoutSession,
} from '../../api/generated/afterbooth'
import type { MemberView } from '../../api/generated/model'
import { ApiError, getAccessToken, installRefresh, setAccessToken } from '../../api/http'

let epoch = 0
let pendingRefresh: Promise<boolean> | undefined
let queue: Promise<unknown> = Promise.resolve()
const listeners = new Set<() => void>()

export function onSessionEnd(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function clearSession() {
  epoch += 1
  setAccessToken()
  listeners.forEach((listener) => listener())
}

export async function authOperation<T>(
  action: (csrf: { 'X-CSRF-Token': string }) => Promise<T>,
  retryOn401 = false,
): Promise<T> {
  const operationEpoch = epoch
  const startedToken = getAccessToken()
  const run = async () => {
    const response = await getCsrfToken()
    if (response.status !== 200) throw new Error('보안 정보를 가져오지 못했어요.')
    return action({ 'X-CSRF-Token': response.data.csrfToken })
  }
  const result = queue.then(run, run)
  queue = result.then(
    () => undefined,
    () => undefined,
  )
  try {
    return await result
  } catch (error) {
    if (
      retryOn401 &&
      error instanceof ApiError &&
      error.status === 401 &&
      operationEpoch === epoch
    ) {
      const renewed = getAccessToken() !== startedToken || (await renewSession())
      if (renewed && operationEpoch === epoch) return authOperation(action)
    }
    throw error
  }
}

export async function signIn(email: string, password: string): Promise<MemberView> {
  const currentEpoch = ++epoch
  setAccessToken()
  const result = await authOperation((csrf) => loginMember({ email, password }, csrf))
  if (result.status !== 200 || epoch !== currentEpoch)
    throw new Error('로그인을 다시 시도해 주세요.')
  setAccessToken(result.data.accessToken, true)
  try {
    const member = await getCurrentMember()
    if (member.status !== 200 || epoch !== currentEpoch)
      throw new Error('계정 정보를 불러오지 못했어요.')
    return member.data
  } catch (error) {
    if (epoch === currentEpoch) clearSession()
    throw error
  }
}

export function renewSession(): Promise<boolean> {
  if (pendingRefresh) return pendingRefresh
  const currentEpoch = epoch
  pendingRefresh = authOperation((csrf) => refreshSession(csrf))
    .then((result) => {
      if (result.status !== 200 || currentEpoch !== epoch) return false
      setAccessToken(result.data.accessToken, true)
      return true
    })
    .catch((error: unknown) => {
      if (error instanceof ApiError && error.status === 401) {
        if (currentEpoch === epoch) clearSession()
        return false
      }
      throw error
    })
    .finally(() => {
      pendingRefresh = undefined
    })
  return pendingRefresh
}

export async function restoreSession(): Promise<MemberView | undefined> {
  if (!(await renewSession())) return undefined
  try {
    const result = await getCurrentMember()
    return result.status === 200 ? result.data : undefined
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      clearSession()
      return undefined
    }
    throw error
  }
}

export async function signOut() {
  try {
    await authOperation((csrf) => logoutSession(csrf))
  } finally {
    clearSession()
  }
}

installRefresh(renewSession, clearSession)
