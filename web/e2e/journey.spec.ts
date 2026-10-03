import { expect, test, type Page, type Route } from '@playwright/test'
import { sampleOpportunity, sampleProfile, sampleFactProfile } from '../src/test/fixtures'
import type { CandidateProfileConfirmationRequest } from '../src/api/types'

const profile = sampleProfile()
const confirmed = sampleProfile({ status: 'CONFIRMED', revision: 2, confirmedAt: '2026-09-21T01:00:00' })
const opportunity = sampleOpportunity()

function json(route: Route, status: number, body: unknown) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

async function mockApi(page: Page, applyUrl = 'https://example.com/apply/44') {
  await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    const method = request.method()

    if (method === 'POST' && pathname === '/api/resumes') {
      return json(route, 201, {
        id: 9,
        fileName: 'resume.pdf',
        sizeBytes: 12,
        pageCount: 1,
        extractorVersion: 'pdfbox-v1',
        createdAt: '2026-09-21T00:00:00',
        candidateProfile: profile,
      })
    }
    if (method === 'GET' && pathname === '/api/candidate-profiles/1') {
      return json(route, 200, confirmed)
    }
    if (method === 'PUT' && pathname === '/api/candidate-profiles/1/confirmation') {
      return json(route, 200, confirmed)
    }
    if (pathname === '/api/candidate-profiles/1/job-search-preference') {
      if (method === 'GET') {
        return json(route, 404, {
          status: 404,
          error: 'Not Found',
          message: 'Candidate profile 1 has no job search preference',
        })
      }
      return json(route, 200, {
        id: 5,
        candidateProfileId: 1,
        preference: {
          defaultResumeStrategy: 'VOLUME',
          targetRoles: ['Backend'],
          excludedRoles: [],
          preferredLocations: ['Remote'],
          acceptableWorkArrangements: ['REMOTE'],
          minimumExperienceToleranceYears: null,
          maximumExperienceToleranceYears: null,
          freshnessDays: 14,
          relocationWilling: 'UNKNOWN',
          compensationCurrency: null,
          minimumCompensation: null,
          desiredCompensation: null,
        },
        createdAt: '2026-09-21T00:00:00',
        updatedAt: '2026-09-21T00:00:00',
      })
    }
    if (pathname === '/api/candidate-profiles/1/resume-routes') {
      if (method === 'GET') return json(route, 200, [])
      const body = request.postDataJSON() as { strategy: string }
      return json(route, 201, {
        id: body.strategy === 'VOLUME' ? 1 : 2,
        candidateProfileId: 1,
        resumeId: 9,
        strategy: body.strategy,
        roleFamily: null,
        defaultRoute: true,
        variantLabel: `Default ${body.strategy} resume`,
        approved: true,
        sourceFileName: 'resume.pdf',
        sourceVersion: 'pdfbox-v1',
        createdAt: '2026-09-21T00:00:00',
      })
    }
    if (pathname === '/api/candidate-profiles/1/opportunities') {
      const item = {
        ...opportunity,
        listing: { ...opportunity.listing, applyUrl },
        readiness: { ...opportunity.readiness, readiness: opportunity.readiness.readiness },
      }
      return json(route, 200, {
        candidateProfileId: 1,
        profileRevision: 2,
        algorithmVersion: 'v1',
        vocabularyVersion: 'v1',
        profileParserVersion: 'deterministic-v1',
        profileAssessedOn: '2026-09-21',
        preferenceId: 5,
        preferenceUpdatedAt: '2026-09-21T00:00:00',
        liveListingCount: 1,
        eligibleListingCount: 1,
        ineligibleListingCount: 0,
        computableJobCount: 1,
        unassessedJobCount: 0,
        excludedJobCount: 0,
        filteredJobCount: 1,
        pageResultCount: 1,
        page: 0,
        size: 20,
        totalPages: 1,
        first: true,
        last: true,
        opportunities: [item],
        unassessedJobs: [],
      })
    }
    if (method === 'GET' && pathname === '/api/jobs/44/requirements') {
      return json(route, 200, { requiredSkills: ['Java'], preferredSkills: [], minYearsExperience: 3 })
    }
    if (method === 'POST' && pathname === '/api/jobs/44/assessment') {
      return json(route, 200, opportunity.match)
    }
    if (method === 'GET' && pathname === '/api/jobs/44/application-readiness') {
      return json(route, 200, opportunity.readiness)
    }
    if (method === 'GET' && pathname === '/api/jobs/44') {
      return json(route, 200, {
        id: 44,
        title: 'Backend Engineer',
        company: 'Example',
        location: 'Remote',
        jobUrl: 'https://example.com/jobs/44',
        description: 'Build APIs',
        source: 'LEVER',
        externalJobId: 'ext-44',
        status: 'DISCOVERED',
        createdAt: '2026-09-01T00:00:00',
        updatedAt: '2026-09-01T00:00:00',
      })
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: '{"status":404,"error":"Not Found","message":"unmocked"}' })
  })
}

test('critical V1 journey from upload to apply', async ({ page }) => {
  await mockApi(page)
  await page.goto('/')
  await page.setInputFiles('#resume-file', {
    name: 'resume.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('%PDF-1.4'),
  })
  await page.getByRole('button', { name: 'Upload' }).click()
  await expect(page.getByRole('heading', { name: 'Review candidate profile' })).toBeVisible()
  await page.getByRole('button', { name: 'Confirm profile' }).click()
  await expect(page.getByText('Profile confirmed.')).toBeVisible()
  await page.getByRole('link', { name: 'Continue to preferences' }).click()
  await page.getByRole('button', { name: 'Save preferences' }).click()
  await expect(page.getByText('Preferences saved.')).toBeVisible()
  await page.getByRole('link', { name: 'Continue to resume routes' }).click()
  await expect(page.getByText('Linked resume #9')).toBeVisible()
  await page.getByRole('button', { name: 'Create approved default routes' }).click()
  await page.getByRole('link', { name: 'Continue to opportunities' }).click()
  await expect(page.getByRole('link', { name: /Backend Engineer/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /GOOD_MATCH/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /APPLY_VOLUME/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /READY/ })).toBeVisible()
  await page.getByRole('link', { name: /Backend Engineer/ }).click()
  await expect(page.getByRole('link', { name: 'Apply' })).toHaveAttribute('href', 'https://example.com/apply/44')
})

test('resumeId is recovered from GET profile after direct navigation', async ({ page }) => {
  await mockApi(page)
  await page.goto('/profiles/1/resume-routes')
  await expect(page.getByText('Linked resume #9')).toBeVisible()
})

test('non-http apply URLs are not used as links', async ({ page }) => {
  await mockApi(page, 'javascript:alert(1)')
  await page.goto('/profiles/1/jobs/44')
  await expect(page.getByRole('button', { name: 'Apply' })).toBeDisabled()
  await expect(page.getByRole('link', { name: 'Apply' })).toHaveCount(0)
})

test('extracted draft facts and links survive reload, correction, and confirmation', async ({ page }) => {
  let stored = sampleFactProfile()
  let submitted: CandidateProfileConfirmationRequest | undefined
  await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route) => {
    const { pathname } = new URL(route.request().url())
    if (pathname === '/api/resumes') return json(route, 201, {
      id: 9, fileName: 'synthetic.pdf', sizeBytes: 12, pageCount: 1, extractorVersion: 'pdfbox-3.0.8',
      layoutVersion: 'layout-v2', createdAt: stored.createdAt, candidateProfile: stored,
    })
    if (pathname.endsWith('/confirmation')) {
      submitted = route.request().postDataJSON() as CandidateProfileConfirmationRequest
      stored = { ...stored, facts: submitted.facts, profile: submitted.profile, status: 'CONFIRMED', revision: 2,
        confirmedAt: '2026-10-03T12:00:00' }
      return json(route, 200, stored)
    }
    return json(route, 200, stored)
  })
  await page.goto('/')
  await page.setInputFiles('#resume-file', { name: 'synthetic.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4') })
  await page.getByRole('button', { name: 'Upload' }).click()
  await expect(page.getByLabel('Full name')).toHaveValue('Ada Lovelace')
  await expect(page.getByLabel('Link URL 1')).toHaveValue('https://github.com/ada')
  await page.reload()
  await expect(page.getByLabel('Phone')).toHaveValue('+442079460018')
  await expect(page.getByText(/Resume suggestions need your review/)).toBeVisible()
  await page.getByText('Original resume suggestions and evidence').click()
  await expect(page.getByText('Email: ada@example.com')).toBeVisible()
  await page.getByLabel('Full name').fill('Grace Hopper')
  await page.getByLabel('Email', { exact: true }).fill('')
  await page.getByRole('button', { name: 'Remove link 2' }).click()
  await page.getByLabel('Link URL 1').fill('https://github.com/grace')
  await page.getByRole('button', { name: 'Confirm profile' }).click()
  await expect(page.getByText('Profile confirmed.')).toBeVisible()
  expect(submitted?.facts.email).toBeNull()
  expect(submitted?.facts.professionalLinks?.[0]?.url).toBe('https://github.com/grace')
  expect(submitted).not.toHaveProperty('factExtraction')
  expect(submitted?.profile.evidence).toEqual(sampleFactProfile().profile.evidence)
  await page.reload()
  await expect(page.getByLabel('Full name')).toHaveValue('Grace Hopper')
  await expect(page.getByLabel('Email', { exact: true })).toHaveValue('')
  await page.getByText('Original resume suggestions and evidence').click()
  await expect(page.getByText('Email: ada@example.com')).toBeVisible()
})
