import { Link } from 'react-router-dom'
import type { LiveOpportunityResponse } from '../../api/types'

export function OpportunityCard({
  profileId,
  opportunity,
}: {
  profileId: number
  opportunity: LiveOpportunityResponse
}) {
  return (
    <Link className="card stack" to={`/profiles/${profileId}/jobs/${opportunity.job.id}`}>
      <h2 style={{ margin: 0 }}>
        {opportunity.job.title} · {opportunity.job.company}
      </h2>
      <p className="muted">{opportunity.job.location ?? 'Location not provided'}</p>
      <p>
        Score <strong>{opportunity.match.overallScore}</strong>{' '}
        <span className="chip">{opportunity.match.recommendation}</span>
      </p>
      <p>
        Decision <span className={`chip ${opportunity.decision.decision}`}>{opportunity.decision.decision}</span>
        {' · '}
        Readiness <span className={`chip ${opportunity.readiness.readiness}`}>{opportunity.readiness.readiness}</span>
      </p>
      {opportunity.listing.lastVerifiedAt ? (
        <p className="muted">Verified {opportunity.listing.lastVerifiedAt}</p>
      ) : null}
    </Link>
  )
}
