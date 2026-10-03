import { describe, expect, it } from 'vitest'
import { canApply, isSafeHttpUrl } from './urls'

describe('external URL guards', () => {
  it('allows only http and https', () => {
    expect(isSafeHttpUrl('https://example.com/apply')).toBe(true)
    expect(isSafeHttpUrl('http://example.com/apply')).toBe(true)
    expect(isSafeHttpUrl('javascript:alert(1)')).toBe(false)
    expect(isSafeHttpUrl('ftp://example.com/file')).toBe(false)
    expect(isSafeHttpUrl(null)).toBe(false)
  })

  it('enables apply only when readiness is READY and the URL is http(s)', () => {
    expect(canApply('READY', 'https://example.com/apply')).toBe(true)
    expect(canApply('NEEDS_USER', 'https://example.com/apply')).toBe(false)
    expect(canApply('NOT_READY', 'https://example.com/apply')).toBe(false)
    expect(canApply('READY', 'javascript:alert(1)')).toBe(false)
    expect(canApply('READY', null)).toBe(false)
  })
})
