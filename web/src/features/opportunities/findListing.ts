import { api } from '../../api/client'
import type { OpportunityListingSummary } from '../../api/types'

export const OPPORTUNITY_PAGE_SIZE = 100

export async function findOpportunityListing(
  profileId: number,
  jobId: number,
): Promise<OpportunityListingSummary | null> {
  let page = 0
  for (;;) {
    const result = await api.getOpportunities(profileId, { page, size: OPPORTUNITY_PAGE_SIZE })
    const found = result.opportunities.find((item) => item.job.id === jobId)
    if (found) return found.listing
    if (result.last || result.pageResultCount === 0) return null
    const next = page + 1
    if (result.totalPages > 0 && next >= result.totalPages) return null
    page = next
  }
}
