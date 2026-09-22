import { NavLink, Outlet, useParams } from 'react-router-dom'
import { useEffect, useState, type ReactNode } from 'react'
import { api } from '../api/client'
import { isApiError } from '../api/errors'
import type { CandidateProfileResponse } from '../api/types'
import { rememberProfileId } from './session'

const STEPS = [
  { to: (id: string) => `/profiles/${id}`, label: 'Profile', id: 'profile' },
  { to: (id: string) => `/profiles/${id}/preferences`, label: 'Preferences', id: 'preferences' },
  { to: (id: string) => `/profiles/${id}/resume-routes`, label: 'Resume routes', id: 'routes' },
  { to: (id: string) => `/profiles/${id}/opportunities`, label: 'Opportunities', id: 'opportunities' },
]

export function AppLayout({ children }: { children?: ReactNode }) {
  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="app-header">
        <strong>Job Copilot</strong>
        <p className="muted" style={{ margin: '0.25rem 0 0' }}>
          Private V1 workspace. This API is unauthenticated.
        </p>
      </header>
      <main id="main" className="app-main">
        {children ?? <Outlet />}
      </main>
      <footer className="app-footer">Serve this UI same-origin with the API in production. HTTPS only outside local development.</footer>
    </>
  )
}

export function ProfileWorkspace() {
  const { profileId } = useParams()
  const id = Number(profileId)
  const [profile, setProfile] = useState<CandidateProfileResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!Number.isInteger(id) || id < 1) {
      setProfile(null)
      setError('Invalid profile identifier.')
      return
    }
    setProfile(null)
    setError(null)
    rememberProfileId(id)
    const requestedId = id
    let cancelled = false
    api
      .getProfile(requestedId)
      .then((value) => {
        if (cancelled || value.id !== requestedId) return
        setProfile(value)
      })
      .catch((caught) => {
        if (!cancelled) setError(isApiError(caught) ? caught.userMessage : 'Something went wrong. Please try again.')
      })
    return () => {
      cancelled = true
    }
  }, [id])

  const boundProfile = profile?.id === id ? profile : null
  const confirmed = boundProfile?.status === 'CONFIRMED'

  function adoptProfile(value: CandidateProfileResponse) {
    if (value.id === id) setProfile(value)
  }

  return (
    <div className="stack">
      <nav aria-label="Workflow">
        <ul className="stepper">
          {STEPS.map((step) => {
            const blocked = !confirmed && step.id === 'opportunities'
            return (
              <li key={step.id}>
                {blocked ? (
                  <span className="chip" aria-disabled="true">
                    {step.label}
                  </span>
                ) : (
                  <NavLink to={step.to(String(id))} end={step.id === 'profile'}>
                    {step.label}
                  </NavLink>
                )}
              </li>
            )
          })}
        </ul>
      </nav>
      {boundProfile ? (
        <p>
          Profile status <span className={`chip ${boundProfile.status}`}>{boundProfile.status}</span>
          <span className="muted"> revision {boundProfile.revision}</span>
        </p>
      ) : null}
      {error ? <p className="banner error" role="alert">{error}</p> : null}
      <Outlet context={{ profile: boundProfile, setProfile: adoptProfile, profileId: id }} />
    </div>
  )
}
