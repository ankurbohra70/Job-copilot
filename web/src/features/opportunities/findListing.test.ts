import { describe, expect, it, vi, afterEach } from 'vitest'
import { findOpportunityListing, OPPORTUNITY_PAGE_SIZE } from './findListing'
import { sampleOpportunity } from '../../test/fixtures'
import type { LiveOpportunityPageResponse, LiveOpportunityResponse } from '../../api/types'

function page(
  opportunities: LiveOpportunityResponse[],
  extras: Partial<LiveOpportunityPageResponse>,
): LiveOpportunityPageResponse {
  return {
    candidateProfileId: 1,
    profileRevision: 2,
    algorithmVersion: 'v1',
    vocabularyVersion: 'v1',
    profileParserVersion: 'v1',
    profileAssessedOn: '2026-09-21',
    preferenceId: 5,
    preferenceUpdatedAt: '2026-09-21T00:00:00',
    liveListingCount: 101,
    eligibleListingCount: 101,
    ineligibleListingCount: 0,
    computableJobCount: 101,
    unassessedJobCount: 0,
    excludedJobCount: 0,
    filteredJobCount: 101,
    pageResultCount: opportunities.length,
    page: 0,
    size: OPPORTUNITY_PAGE_SIZE,
    totalPages: 2,
    first: true,
    last: false,
    opportunities,
    unassessedJobs: [],
    ...extras,
  }
}

describe('findOpportunityListing', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('pages past the first 100 results to recover the listing apply URL', async () => {
    const distant = sampleOpportunity()
    distant.job.id = 999
    distant.listing.applyUrl = 'https://example.com/apply/999'
    const fetches: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: string) => {
        fetches.push(String(input))
        const url = new URL(String(input), 'http://local')
        const pageNo = Number(url.searchParams.get('page') ?? '0')
        const body =
          pageNo === 0
            ? page([], { page: 0, first: true, last: false, pageResultCount: 100 })
            : page([distant], { page: 1, first: false, last: true, pageResultCount: 1 })
        return { ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) }
      }),
    )
    const listing = await findOpportunityListing(1, 999)
    expect(listing?.applyUrl).toBe('https://example.com/apply/999')
    expect(fetches.some((item) => item.includes('page=1'))).toBe(true)
    expect(fetches.some((item) => item.includes(`size=${OPPORTUNITY_PAGE_SIZE}`))).toBe(true)
  })
})
