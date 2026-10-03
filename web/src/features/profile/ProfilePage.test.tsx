import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ProfilePage } from './ProfilePage'
import { sampleProfile, sampleFactProfile } from '../../test/fixtures'
import type { CandidateProfileConfirmationRequest, CandidateProfileResponse } from '../../api/types'

function Host({ initial }: { initial: CandidateProfileResponse }) {
  const [profile, setProfile] = useState(initial)
  return <Outlet context={{ profile, setProfile, profileId: initial.id }} />
}

function jsonResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    text: async () => JSON.stringify(body),
  }
}

describe('ProfilePage confirmation', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('keeps submitted edits after 409 and retries with the new revision and hidden fields', async () => {
    const first = sampleFactProfile()
    const refetch = sampleProfile({
      revision: 2,
      facts: { ...first.facts, fullName: 'Server Copy' },
      profile: { ...first.profile, keywords: ['server-keyword'] },
    })
    const confirmed = sampleProfile({
      revision: 3,
      status: 'CONFIRMED',
      confirmedAt: '2026-09-21T01:00:00',
      facts: { ...first.facts, fullName: 'Edited_Name' },
    })
    const puts: CandidateProfileConfirmationRequest[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init?: RequestInit) => {
        if (init?.method === 'PUT') {
          puts.push(JSON.parse(String(init.body)) as CandidateProfileConfirmationRequest)
          if (puts.length === 1) {
            return jsonResponse(409, {
              status: 409,
              error: 'Conflict',
              message: 'Candidate profile 1 revision does not match',
            })
          }
          return jsonResponse(200, confirmed)
        }
        return jsonResponse(200, refetch)
      }),
    )
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route element={<Host initial={first} />}>
            <Route index element={<ProfilePage />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    const name = screen.getByRole('textbox', { name: /Full name/ })
    await user.clear(name)
    await user.type(name, 'Edited_Name')
    await user.clear(screen.getByRole('textbox', { name: 'Link URL 1' }))
    await user.type(screen.getByRole('textbox', { name: 'Link URL 1' }), 'https://github.com/grace')
    await user.click(screen.getByRole('button', { name: 'Confirm profile' }))
    expect(await screen.findByText(/The profile revision changed/)).toBeInTheDocument()
    expect(screen.queryByText('Profile confirmed.')).toBeNull()
    expect(name).toHaveValue('Edited_Name')
    await user.click(screen.getByRole('button', { name: 'Keep my edits and use the latest revision' }))
    expect(name).toHaveValue('Edited_Name')
    await user.click(screen.getByRole('button', { name: 'Confirm profile' }))
    expect(await screen.findByText('Profile confirmed.')).toBeInTheDocument()
    expect(puts).toHaveLength(2)
    expect(puts[1]?.expectedRevision).toBe(2)
    expect(puts[1]?.facts.fullName).toBe('Edited_Name')
    expect(puts[1]?.profile.evidence).toEqual(first.profile.evidence)
    expect(puts[1]?.profile.workExperience[0]?.sourceText).toBe('Engineer at Acme 2020-present')
    expect(puts[1]?.profile.keywords).toEqual(['server-keyword'])
    expect(puts[1]?.facts.professionalLinks?.[0]?.url).toBe('https://github.com/grace')
    expect(puts[1]).not.toHaveProperty('factExtraction')
  })

  it('replaces the form with the refreshed server profile when requested', async () => {
    const first = sampleProfile()
    const refetch = sampleProfile({
      revision: 2,
      facts: { ...first.facts, fullName: 'Server Copy' },
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init?: RequestInit) => {
        if (init?.method === 'PUT') {
          return jsonResponse(409, {
            status: 409,
            error: 'Conflict',
            message: 'Candidate profile 1 revision does not match',
          })
        }
        return jsonResponse(200, refetch)
      }),
    )
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route element={<Host initial={first} />}>
            <Route index element={<ProfilePage />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    const name = screen.getByRole('textbox', { name: /Full name/ })
    await user.clear(name)
    await user.type(name, 'Edited_Name')
    await user.click(screen.getByRole('button', { name: 'Confirm profile' }))
    expect(await screen.findByText(/The profile revision changed/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Replace the form with the latest server values' }))
    expect(name).toHaveValue('Server Copy')
    expect(screen.queryByText('Profile confirmed.')).toBeNull()
  })

  it('prefills extracted scalars and links, preserves raw link typing, and supports correction and clearing', async () => {
    const first = sampleFactProfile()
    const puts: CandidateProfileConfirmationRequest[] = []
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body)) as CandidateProfileConfirmationRequest
      puts.push(body)
      return jsonResponse(200, { ...first, facts: body.facts, profile: body.profile, status: 'CONFIRMED', revision: 2 })
    }))
    const user = userEvent.setup()
    const view = render(<MemoryRouter><Routes><Route element={<Host initial={first} />}><Route index element={<ProfilePage />} /></Route></Routes></MemoryRouter>)
    expect(screen.getByRole('textbox', { name: 'Full name' })).toHaveValue('Ada Lovelace')
    expect(screen.getByRole('textbox', { name: 'Phone' })).toHaveValue('+442079460018')
    expect(screen.getByRole('textbox', { name: 'Current title' })).toHaveValue('Engineer')
    expect(screen.getByRole('textbox', { name: 'Location' })).toHaveValue('London')
    expect(screen.getByRole('textbox', { name: 'Link URL 1' })).toHaveValue('https://github.com/ada')
    expect(screen.getByText(/Resume suggestions need your review/)).toBeInTheDocument()
    await user.click(screen.getByText('Original resume suggestions and evidence'))
    expect(screen.getByText('Email: ada@example.com')).toBeInTheDocument()
    await user.clear(screen.getByRole('textbox', { name: 'Email' }))
    await user.click(screen.getByRole('button', { name: 'Remove link 2' }))
    await user.click(screen.getByRole('button', { name: 'Add professional link' }))
    const url = screen.getByRole('textbox', { name: 'Link URL 2' })
    await user.type(url, ' https://example.org ')
    expect(url).toHaveValue(' https://example.org ')
    await user.click(screen.getByRole('button', { name: 'Confirm profile' }))
    expect(await screen.findByText('Profile confirmed.')).toBeInTheDocument()
    expect(puts[0]?.facts.email).toBeNull()
    expect(puts[0]?.facts.professionalLinks?.[1]?.url).toBe('https://example.org')
    expect(puts[0]).not.toHaveProperty('factExtraction')
    expect(screen.getByText('Email: ada@example.com')).toBeInTheDocument()
    view.unmount()
    render(<MemoryRouter><Routes><Route element={<Host initial={first} />}><Route index element={<ProfilePage />} /></Route></Routes></MemoryRouter>)
    expect(screen.getByRole('textbox', { name: 'Email' })).toHaveValue('ada@example.com')
    expect(screen.getByRole('textbox', { name: 'Link URL 1' })).toHaveValue('https://github.com/ada')
  })
})
