export function isSafeHttpUrl(value: string | null | undefined): boolean {
  if (!value) return false
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

export function canApply(
  readiness: string | undefined,
  applyUrl: string | null | undefined,
): boolean {
  return readiness === 'READY' && isSafeHttpUrl(applyUrl)
}
