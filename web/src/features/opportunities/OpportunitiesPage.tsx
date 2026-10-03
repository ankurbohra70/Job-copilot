import { useEffect, useState, type FormEvent } from 'react'
import { Link, Navigate, useOutletContext, useSearchParams } from 'react-router-dom'
import { api } from '../../api/client'
import { isApiError } from '../../api/errors'
import type {
  ApplicationReadiness,
  CandidateProfileResponse,
  LiveOpportunityPageResponse,
  Recommendation,
} from '../../api/types'
import { Banner, Empty, Loading } from '../../shared/ui/Feedback'
import { OpportunityCard } from './OpportunityCard'

interface Outlet {
  profile: CandidateProfileResponse | null
  profileId: number
}

const RECS: Array<Recommendation | ''> = ['', 'STRONG_MATCH', 'GOOD_MATCH', 'WEAK_MATCH', 'NOT_RECOMMENDED']
const READY: Array<ApplicationReadiness | ''> = ['', 'READY', 'NEEDS_USER', 'NOT_READY']

export function OpportunitiesPage() {
  const { profile, profileId } = useOutletContext<Outlet>()
  const [searchParams, setSearchParams] = useSearchParams()
  const [page, setPage] = useState<LiveOpportunityPageResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [unconfirmed, setUnconfirmed] = useState(false)
  const [loading, setLoading] = useState(true)

  const query = {
    page: Number(searchParams.get('page') ?? 0) || 0,
    size: 20,
    minScore: searchParams.get('minScore') ? Number(searchParams.get('minScore')) : undefined,
    recommendation: (searchParams.get('recommendation') as Recommendation | null) || undefined,
    readiness: (searchParams.get('readiness') as ApplicationReadiness | null) || undefined,
  }

  useEffect(() => {
    if (profile?.status === 'DRAFT') {
      setUnconfirmed(true)
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    api
      .getOpportunities(profileId, query)
      .then((value) => {
        if (!cancelled) setPage(value)
      })
      .catch((caught) => {
        if (cancelled) return
        if (isApiError(caught) && caught.status === 409) {
          setUnconfirmed(true)
        } else {
          setError(isApiError(caught) ? caught.userMessage : 'Something went wrong. Please try again.')
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [profileId, profile?.status, searchParams])

  if (unconfirmed) {
    return <Navigate to={`/profiles/${profileId}`} replace />
  }

  function onFilter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const next = new URLSearchParams()
    const minScore = String(data.get('minScore') ?? '').trim()
    const recommendation = String(data.get('recommendation') ?? '')
    const readiness = String(data.get('readiness') ?? '')
    if (minScore) next.set('minScore', minScore)
    if (recommendation) next.set('recommendation', recommendation)
    if (readiness) next.set('readiness', readiness)
    next.set('page', '0')
    setSearchParams(next)
  }

  return (
    <section className="stack">
      <h1>Live opportunities</h1>
      <p>Scores, decisions, and readiness come from the server. This list is not stored.</p>
      {error ? <Banner kind="error">{error}</Banner> : null}
      <form className="card cluster" onSubmit={onFilter}>
        <label htmlFor="minScore">
          Minimum score
          <input id="minScore" name="minScore" type="number" min={0} max={100} defaultValue={query.minScore ?? ''} />
        </label>
        <label htmlFor="recommendation">
          Recommendation
          <select id="recommendation" name="recommendation" defaultValue={query.recommendation ?? ''}>
            {RECS.map((item) => (
              <option key={item || 'any'} value={item}>
                {item || 'Any'}
              </option>
            ))}
          </select>
        </label>
        <label htmlFor="readiness">
          Readiness
          <select id="readiness" name="readiness" defaultValue={query.readiness ?? ''}>
            {READY.map((item) => (
              <option key={item || 'any'} value={item}>
                {item || 'Any'}
              </option>
            ))}
          </select>
        </label>
        <button type="submit">Filter</button>
      </form>
      {loading ? <Loading label="Loading opportunities" /> : null}
      {page && !loading ? (
        <>
          <p className="muted">
            Live listings {page.liveListingCount} · eligible {page.eligibleListingCount} · unassessed{' '}
            {page.unassessedJobCount} · page {page.page + 1} of {Math.max(page.totalPages, 1)}
          </p>
          {page.opportunities.length === 0 ? (
            <Empty>
              No live opportunities to show. If the database has not been synchronized, this list stays empty.
            </Empty>
          ) : (
            <ul className="opportunity-list">
              {page.opportunities.map((item) => (
                <li key={item.job.id}>
                  <OpportunityCard profileId={profileId} opportunity={item} />
                </li>
              ))}
            </ul>
          )}
          {page.unassessedJobs.length > 0 ? (
            <section className="stack">
              <h2>Unassessed jobs</h2>
              <p className="muted">These jobs are not scored and cannot be applied to from this list.</p>
              <ul className="check-list">
                {page.unassessedJobs.map((item) => (
                  <li key={item.job.id} className="card">
                    {item.job.title} · {item.job.company} — {item.message}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
          <div className="cluster">
            <button
              type="button"
              className="secondary"
              disabled={page.first}
              onClick={() => {
                const next = new URLSearchParams(searchParams)
                next.set('page', String(Math.max(page.page - 1, 0)))
                setSearchParams(next)
              }}
            >
              Previous
            </button>
            <button
              type="button"
              className="secondary"
              disabled={page.last || page.totalPages === 0}
              onClick={() => {
                const next = new URLSearchParams(searchParams)
                next.set('page', String(page.page + 1))
                setSearchParams(next)
              }}
            >
              Next
            </button>
            <Link to={`/profiles/${profileId}/preferences`}>Back to preferences</Link>
          </div>
        </>
      ) : null}
    </section>
  )
}
