import { render, screen } from '@testing-library/react'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { JobDetailPage } from './JobDetailPage'
import { sampleOpportunity, sampleProfile } from '../../test/fixtures'
import { OPPORTUNITY_PAGE_SIZE } from './findListing'
import type { ApplicationReadiness, LiveOpportunityPageResponse, LiveOpportunityResponse } from '../../api/types'

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) }
}

function opportunityPage(
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

function mockJobApis(readiness: ApplicationReadiness, distant: LiveOpportunityResponse) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      const url = new URL(String(input), 'http://local')
      if (url.pathname === '/api/jobs/999') {
        return jsonResponse({
          id: 999,
          title: 'Distant Role',
          company: 'Example',
          location: 'Remote',
          jobUrl: 'https://example.com/jobs/999',
          description: 'Later page role',
          source: 'LEVER',
          externalJobId: 'ext-999',
          status: 'DISCOVERED',
          createdAt: '2026-09-01T00:00:00',
          updatedAt: '2026-09-01T00:00:00',
        })
      }
      if (url.pathname.endsWith('/requirements')) {
        return jsonResponse({ requiredSkills: ['Java'], preferredSkills: [], minYearsExperience: 3 })
      }
      if (url.pathname.endsWith('/assessment')) {
        return jsonResponse({ ...distant.match, jobId: 999 })
      }
      if (url.pathname.endsWith('/application-readiness')) {
        return jsonResponse({ ...distant.readiness, readiness, jobId: 999 })
      }
      if (url.pathname.endsWith('/opportunities')) {
        const pageNo = Number(url.searchParams.get('page') ?? '0')
        const body =
          pageNo === 0
            ? opportunityPage([], { page: 0, first: true, last: false, pageResultCount: 100 })
            : opportunityPage([distant], { page: 1, first: false, last: true, pageResultCount: 1 })
        return jsonResponse(body)
      }
      return { ok: false, status: 404, json: async () => ({ status: 404 }), text: async () => JSON.stringify({ status: 404, error: 'Not Found', message: 'missing' }) }
    }),
  )
}

describe('JobDetailPage apply URL recovery', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('recovers an apply URL beyond the first 100 only when readiness is READY', async () => {
    const distant = sampleOpportunity()
    distant.job.id = 999
    distant.job.title = 'Distant Role'
    distant.listing.applyUrl = 'https://example.com/apply/999'
    mockJobApis('READY', distant)
    render(
      <MemoryRouter initialEntries={['/profiles/1/jobs/999']}>
        <Routes>
          <Route
            path="/profiles/:profileId/jobs/:jobId"
            element={<Outlet context={{ profile: sampleProfile({ status: 'CONFIRMED' }), profileId: 1 }} />}
          >
            <Route index element={<JobDetailPage />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByRole('link', { name: 'Apply' })).toHaveAttribute('href', 'https://example.com/apply/999')
  })

  it('does not enable Apply when readiness is not READY', async () => {
    const distant = sampleOpportunity()
    distant.job.id = 999
    distant.listing.applyUrl = 'https://example.com/apply/999'
    distant.readiness = { ...distant.readiness, readiness: 'NOT_READY' }
    mockJobApis('NOT_READY', distant)
    render(
      <MemoryRouter initialEntries={['/profiles/1/jobs/999']}>
        <Routes>
          <Route
            path="/profiles/:profileId/jobs/:jobId"
            element={<Outlet context={{ profile: sampleProfile({ status: 'CONFIRMED' }), profileId: 1 }} />}
          >
            <Route index element={<JobDetailPage />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByRole('button', { name: 'Apply' })).toBeDisabled()
    expect(screen.queryByRole('link', { name: 'Apply' })).toBeNull()
  })
})
