import { useEffect, useState } from 'react'
import { Link, useOutletContext } from 'react-router-dom'
import { api } from '../../api/client'
import { isApiError } from '../../api/errors'
import type { CandidateProfileResponse, ResumeRouteSnapshot, ResumeStrategy } from '../../api/types'
import { Banner, Empty, Loading } from '../../shared/ui/Feedback'

interface Outlet {
  profile: CandidateProfileResponse | null
  profileId: number
}

const REQUIRED: ResumeStrategy[] = ['VOLUME', 'PRECISION']

export function ResumeRoutesPage() {
  const { profileId } = useOutletContext<Outlet>()
  const [routes, setRoutes] = useState<ResumeRouteSnapshot[]>([])
  const [resumeId, setResumeId] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    Promise.all([api.getProfile(profileId), api.listResumeRoutes(profileId)])
      .then(([profile, list]) => {
        if (cancelled) return
        setResumeId(profile.resumeId)
        setRoutes(list)
      })
      .catch((caught) => {
        if (!cancelled) setError(isApiError(caught) ? caught.userMessage : 'Something went wrong. Please try again.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [profileId])

  async function createDefaults() {
    if (pending || resumeId == null) return
    setPending(true)
    setError(null)
    setInfo(null)
    try {
      const created: ResumeRouteSnapshot[] = []
      for (const strategy of REQUIRED) {
        const existing = routes.find((route) => route.strategy === strategy && route.defaultRoute && route.approved)
        if (existing) continue
        try {
          created.push(
            await api.createResumeRoute(profileId, {
              resumeId,
              strategy,
              roleFamily: null,
              defaultRoute: true,
              variantLabel: `Default ${strategy} resume`,
              approved: true,
            }),
          )
        } catch (caught) {
          if (isApiError(caught) && caught.status === 409) {
            setInfo('A default route already exists for this strategy. You can continue.')
          } else {
            throw caught
          }
        }
      }
      const list = await api.listResumeRoutes(profileId)
      setRoutes(list)
      if (created.length > 0) setInfo('Approved default resume routes are in place.')
    } catch (caught) {
      setError(isApiError(caught) ? caught.userMessage : 'Something went wrong. Please try again.')
    } finally {
      setPending(false)
    }
  }

  if (loading) return <Loading label="Loading resume routes" />

  return (
    <section className="stack">
      <h1>Resume routes</h1>
      <p>
        Application readiness requires an approved route to the linked resume. This page creates default VOLUME and
        PRECISION routes.
      </p>
      {error ? <Banner kind="error">{error}</Banner> : null}
      {info ? <Banner kind="ok">{info}</Banner> : null}
      {resumeId == null ? (
        <Banner kind="error">This profile has no linked resume. Cannot create resume routes.</Banner>
      ) : (
        <p className="muted">Linked resume #{resumeId}</p>
      )}
      {routes.length === 0 ? <Empty>No resume routes yet.</Empty> : (
        <ul className="check-list">
          {routes.map((route) => (
            <li key={route.id} className="card">
              {route.strategy} {route.defaultRoute ? 'default' : ''} {route.approved ? 'approved' : 'not approved'} —{' '}
              {route.variantLabel}
            </li>
          ))}
        </ul>
      )}
      <div className="cluster">
        <button type="button" disabled={pending || resumeId == null} onClick={createDefaults}>
          {pending ? 'Saving…' : 'Create approved default routes'}
        </button>
        <Link to={`/profiles/${profileId}/opportunities`}>Continue to opportunities</Link>
      </div>
    </section>
  )
}
