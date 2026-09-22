import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ProfileWorkspace } from './AppLayout'
import { ProfilePage } from '../features/profile/ProfilePage'
import { sampleProfile } from '../test/fixtures'
import type { CandidateProfileConfirmationRequest } from '../api/types'

function jsonResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    text: async () => JSON.stringify(body),
  }
}

describe('profile identity binding', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('does not keep profile A mutable on route B or accept stale A responses', async () => {
    const profileA = sampleProfile({ id: 1, facts: { ...sampleProfile().facts, fullName: 'Ada One' } })
    const profileB = sampleProfile({ id: 2, resumeId: 8, facts: { ...sampleProfile().facts, fullName: 'Bob Two' } })
    let releaseA: ((value: unknown) => void) | undefined
    const delayedA = new Promise((resolve) => {
      releaseA = resolve
    })
    const puts: Array<{ url: string; body: CandidateProfileConfirmationRequest }> = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: string, init?: RequestInit) => {
        const url = String(input)
        if (url.includes('/confirmation')) {
          puts.push({ url, body: JSON.parse(String(init?.body)) as CandidateProfileConfirmationRequest })
          return jsonResponse(200, { ...profileB, status: 'CONFIRMED', revision: 2 })
        }
        if (url.endsWith('/candidate-profiles/1')) {
          await delayedA
          return jsonResponse(200, profileA)
        }
        if (url.endsWith('/candidate-profiles/2')) {
          return jsonResponse(200, profileB)
        }
        return jsonResponse(404, { status: 404, error: 'Not Found', message: 'missing' })
      }),
    )
    const router = createMemoryRouter(
      [
        {
          path: '/profiles/:profileId',
          element: <ProfileWorkspace />,
          children: [{ index: true, element: <ProfilePage /> }],
        },
      ],
      { initialEntries: ['/profiles/1'] },
    )
    render(<RouterProvider router={router} />)
    expect(screen.queryByDisplayValue('Ada One')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Confirm profile' })).toBeNull()
    await router.navigate('/profiles/2')
    expect(await screen.findByDisplayValue('Bob Two')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('Ada One')).toBeNull()
    releaseA?.(true)
    await waitFor(() => {
      expect(screen.getByDisplayValue('Bob Two')).toBeInTheDocument()
    })
    expect(screen.queryByDisplayValue('Ada One')).toBeNull()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Confirm profile' }))
    await waitFor(() => expect(puts.length).toBe(1))
    expect(puts[0]?.url).toContain('/api/candidate-profiles/2/confirmation')
    expect(puts[0]?.body.facts.fullName).toBe('Bob Two')
    expect(JSON.stringify(puts[0]?.body)).not.toContain('Ada One')
  })
})
