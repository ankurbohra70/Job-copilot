import { useEffect, useState } from 'react'
import { Link, Navigate, useOutletContext, useParams } from 'react-router-dom'
import { api } from '../../api/client'
import { isApiError } from '../../api/errors'
import type {
  ApplicationReadinessResult,
  CandidateProfileResponse,
  JobRequirementsResponse,
  JobResponse,
  MatchResponse,
  OpportunityListingSummary,
} from '../../api/types'
import { findOpportunityListing } from './findListing'
import { canApply, isSafeHttpUrl } from '../../api/urls'
import { Banner, Loading } from '../../shared/ui/Feedback'
import { ExternalLink } from '../../shared/ui/ExternalLink'

interface Outlet {
  profile: CandidateProfileResponse | null
  profileId: number
}

export function JobDetailPage() {
  const { jobId: jobIdParam } = useParams()
  const { profile, profileId } = useOutletContext<Outlet>()
  const jobId = Number(jobIdParam)
  const [job, setJob] = useState<JobResponse | null>(null)
  const [requirements, setRequirements] = useState<JobRequirementsResponse | null>(null)
  const [match, setMatch] = useState<MatchResponse | null>(null)
  const [readiness, setReadiness] = useState<ApplicationReadinessResult | null>(null)
  const [listing, setListing] = useState<OpportunityListingSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [unconfirmed, setUnconfirmed] = useState(false)
  const [unassessed, setUnassessed] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (profile?.status === 'DRAFT') {
      setUnconfirmed(true)
      setLoading(false)
      return
    }
    if (!Number.isInteger(jobId) || jobId < 1) {
      setError('Invalid job identifier.')
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    Promise.allSettled([
      api.getJob(jobId),
      api.getJobRequirements(jobId),
      api.assessJob(jobId, profileId),
      api.getReadiness(jobId, profileId),
      findOpportunityListing(profileId, jobId),
    ]).then((results) => {
      if (cancelled) return
      const [jobResult, reqResult, matchResult, readyResult, oppResult] = results
      if (jobResult.status === 'rejected') {
        const reason = jobResult.reason
        if (isApiError(reason) && reason.status === 409) setUnconfirmed(true)
        else setError(isApiError(reason) ? reason.userMessage : 'Something went wrong. Please try again.')
        setLoading(false)
        return
      }
      setJob(jobResult.value)
      if (reqResult.status === 'fulfilled') setRequirements(reqResult.value)
      if (matchResult.status === 'fulfilled') setMatch(matchResult.value)
      else if (isApiError(matchResult.reason) && matchResult.reason.status === 409) setUnconfirmed(true)
      else if (isApiError(matchResult.reason) && matchResult.reason.status === 422) setUnassessed(matchResult.reason.userMessage)
      if (readyResult.status === 'fulfilled') setReadiness(readyResult.value)
      else if (isApiError(readyResult.reason) && readyResult.reason.status === 409) setUnconfirmed(true)
      if (oppResult.status === 'fulfilled') {
        setListing(oppResult.value)
      }
      setLoading(false)
    })
    return () => {
      cancelled = true
    }
  }, [jobId, profileId, profile?.status])

  if (unconfirmed) return <Navigate to={`/profiles/${profileId}`} replace />
  if (loading) return <Loading label="Loading job" />
  if (error) return <Banner kind="error">{error}</Banner>
  if (!job) return <Banner kind="error">Job was not found.</Banner>

  const decision = readiness?.decision
  const applyUrl = listing?.applyUrl ?? null
  const applyEnabled = canApply(readiness?.readiness, applyUrl)
  const postingUrl = listing?.hostedJobUrl ?? job.jobUrl

  return (
    <article className="stack">
      <p>
        <Link to={`/profiles/${profileId}/opportunities`}>Back to opportunities</Link>
      </p>
      <h1>
        {job.title} · {job.company}
      </h1>
      <p className="muted">{job.location}</p>

      <section className="card stack">
        <h2>Job</h2>
        {requirements ? (
          <p>
            Required skills: {requirements.requiredSkills.join(', ') || 'none listed'}
            <br />
            Preferred skills: {requirements.preferredSkills.join(', ') || 'none listed'}
          </p>
        ) : null}
        <div className="pre-wrap">{job.description ?? 'No description.'}</div>
      </section>

      <section className="card stack">
        <h2>Assessment</h2>
        {unassessed ? (
          <Banner kind="warn">{unassessed}</Banner>
        ) : match ? (
          <>
            <p>
              Score <strong>{match.overallScore}</strong>{' '}
              <span className="chip">{match.recommendation}</span>
            </p>
            <p>Matched required: {match.matchedRequiredSkills.join(', ') || 'none'}</p>
            <p>Missing required: {match.missingRequiredSkills.join(', ') || 'none'}</p>
            <ul>
              {match.strengths.map((item) => (
                <li key={item.code}>{item.message}</li>
              ))}
            </ul>
            <ul>
              {match.gaps.map((item) => (
                <li key={item.code}>{item.message}</li>
              ))}
            </ul>
            {match.warnings.length > 0 ? <p>Warnings: {match.warnings.join('; ')}</p> : null}
          </>
        ) : (
          <p className="muted">Assessment is unavailable.</p>
        )}
      </section>

      <section className="card stack">
        <h2>Application decision</h2>
        {decision ? (
          <>
            <p>
              <span className={`chip ${decision.decision}`}>{decision.decision}</span>
              {decision.recommendedResumeStrategy ? ` · ${decision.recommendedResumeStrategy}` : ''}
            </p>
            <ul>
              {decision.reasons.map((reason) => (
                <li key={reason}>{reason}</li>
              ))}
            </ul>
          </>
        ) : (
          <p className="muted">Decision is unavailable.</p>
        )}
      </section>

      <section className="card stack">
        <h2>Application readiness</h2>
        {readiness ? (
          <>
            <p>
              <span className={`chip ${readiness.readiness}`}>{readiness.readiness}</span>
            </p>
            <ul className="check-list">
              {readiness.checks.map((check) => (
                <li key={check.code}>
                  <span className={`chip ${check.status}`}>{check.status}</span> {check.code}: {check.message}
                </li>
              ))}
            </ul>
          </>
        ) : (
          <p className="muted">Readiness is unavailable.</p>
        )}
      </section>

      <section className="card stack">
        <h2>Apply</h2>
        {postingUrl && isSafeHttpUrl(postingUrl) ? <ExternalLink href={postingUrl}>View posting</ExternalLink> : null}
        {applyEnabled && applyUrl ? (
          <ExternalLink href={applyUrl}>Apply</ExternalLink>
        ) : (
          <p>
            <button type="button" disabled>
              Apply
            </button>
            <span className="muted"> Apply stays disabled until the server reports READY with a validated URL.</span>
          </p>
        )}
      </section>
    </article>
  )
}
