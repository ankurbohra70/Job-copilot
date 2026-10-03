import { describe, expect, it } from 'vitest'
import { GENERIC_ERROR, parseApiError } from './errors'

describe('parseApiError', () => {
  it('shows domain-safe messages for known client failures', () => {
    const error = parseApiError(422, { status: 422, error: 'Unprocessable Entity', message: 'No meaningful text could be extracted' })
    expect(error.userMessage).toBe('No meaningful text could be extracted')
    expect(error.status).toBe(422)
  })

  it('maps validation field errors', () => {
    const error = parseApiError(400, { status: 400, error: 'Validation failed', fieldErrors: { email: 'must be a well-formed email address' } })
    expect(error.fieldErrors.email).toBe('must be a well-formed email address')
  })

  it('hides unknown 5xx details', () => {
    const error = parseApiError(500, {
      status: 500,
      error: 'Internal Server Error',
      message: 'NullPointerException at ResumePersistenceService.java:66',
    })
    expect(error.userMessage).toBe(GENERIC_ERROR)
    expect(error.userMessage).not.toContain('NullPointerException')
  })
})
