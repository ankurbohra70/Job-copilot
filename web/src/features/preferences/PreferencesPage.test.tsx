import { render, screen } from '@testing-library/react'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PreferencesPage } from './PreferencesPage'
import { sampleProfile } from '../../test/fixtures'

describe('PreferencesPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('treats a missing preference as an empty form', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        text: async () =>
          JSON.stringify({ status: 404, error: 'Not Found', message: 'Candidate profile 1 has no job search preference' }),
      }),
    )
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route element={<Outlet context={{ profile: sampleProfile(), profileId: 1 }} />}>
            <Route index element={<PreferencesPage />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByText(/No preference is saved yet/)).toBeInTheDocument()
    expect(screen.getByLabelText('Default resume strategy')).toHaveValue('VOLUME')
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
