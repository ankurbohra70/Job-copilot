import { ApiError, parseApiError } from './errors'

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? ''

export async function requestJson<T>(
  path: string,
  init: RequestInit = {},
  options: { allowNotFound?: boolean } = {},
): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  } catch {
    throw new ApiError(0, 'Unable to reach the server. Please try again.')
  }
  if (options.allowNotFound && response.status === 404) {
    await response.text()
    return null as T
  }
  if (!response.ok) {
    throw parseApiError(response.status, await readBody(response))
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export async function requestMultipart<T>(path: string, body: FormData): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, { method: 'POST', body })
  } catch {
    throw new ApiError(0, 'Unable to reach the server. Please try again.')
  }
  if (!response.ok) {
    throw parseApiError(response.status, await readBody(response))
  }
  return (await response.json()) as T
}

async function readBody(response: Response): Promise<unknown> {
  const text = await response.text()
  if (text === '') return null
  try {
    return JSON.parse(text) as unknown
  } catch {
    return null
  }
}
