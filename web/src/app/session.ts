const KEY = 'job-copilot.profileId'

export function rememberProfileId(profileId: number): void {
  try {
    sessionStorage.setItem(KEY, String(profileId))
  } catch {
    /* ignore quota / private mode */
  }
}

export function rememberedProfileId(): number | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return null
    const id = Number(raw)
    return Number.isInteger(id) && id > 0 ? id : null
  } catch {
    return null
  }
}
