export const GENERIC_ERROR = 'Something went wrong. Please try again.'

const DOMAIN_STATUSES = new Set([400, 404, 409, 413, 415, 422])

export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors: Record<string, string>
  readonly userMessage: string

  constructor(status: number, userMessage: string, fieldErrors: Record<string, string> = {}) {
    super(userMessage)
    this.name = 'ApiError'
    this.status = status
    this.userMessage = userMessage
    this.fieldErrors = fieldErrors
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

export function parseApiError(status: number, body: unknown): ApiError {
  if (status >= 500 || !DOMAIN_STATUSES.has(status)) {
    return new ApiError(status, GENERIC_ERROR)
  }
  if (isRecord(body) && isFieldErrorMap(body.fieldErrors)) {
    return new ApiError(status, 'Validation failed', body.fieldErrors)
  }
  if (isRecord(body) && typeof body.message === 'string' && body.message.trim() !== '') {
    return new ApiError(status, body.message)
  }
  return new ApiError(status, GENERIC_ERROR)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isFieldErrorMap(value: unknown): value is Record<string, string> {
  if (!isRecord(value)) return false
  return Object.values(value).every((item) => typeof item === 'string')
}
