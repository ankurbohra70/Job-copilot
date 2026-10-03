import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link, useOutletContext } from 'react-router-dom'
import { api } from '../../api/client'
import { isApiError } from '../../api/errors'
import type { CandidateProfileResponse, CandidateFactState, ProfessionalLinkType } from '../../api/types'
import { Banner, Field, Loading } from '../../shared/ui/Feedback'
import { buildConfirmation, cloneEdits, fromProfile, type VisibleProfileEdits } from './confirmPayload'

interface Outlet {
  profile: CandidateProfileResponse | null
  setProfile: (value: CandidateProfileResponse) => void
  profileId: number
}

const FACT_STATES: CandidateFactState[] = ['UNKNOWN', 'YES', 'NO']
const LINK_TYPES: ProfessionalLinkType[] = ['LINKEDIN', 'GITHUB', 'LEETCODE', 'WEBSITE', 'OTHER']

export function ProfilePage() {
  const { profile, setProfile, profileId } = useOutletContext<Outlet>()
  const [edits, setEdits] = useState<VisibleProfileEdits | null>(null)
  const [submittedEdits, setSubmittedEdits] = useState<VisibleProfileEdits | null>(null)
  const [authority, setAuthority] = useState<CandidateProfileResponse | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [stale, setStale] = useState(false)
  const [needsReconcile, setNeedsReconcile] = useState(false)
  const [status, setStatus] = useState<'idle' | 'success'>('idle')

  useEffect(() => {
    if (!profile || profile.id !== profileId) {
      setAuthority(null)
      setEdits(null)
      setSubmittedEdits(null)
      setNeedsReconcile(false)
      setStale(false)
      setStatus('idle')
      setError(null)
      setFieldErrors({})
      return
    }
    setAuthority(profile)
    if (!needsReconcile && submittedEdits === null) {
      setEdits(fromProfile(profile))
    }
  }, [profile, profileId, needsReconcile, submittedEdits])

  const warnings = authority?.profile.warnings ?? []

  const nextHref = `/profiles/${profileId}/preferences`
  const confirmed = authority?.status === 'CONFIRMED'

  const payloadPreview = useMemo(
    () => (authority && edits ? buildConfirmation(authority, edits) : null),
    [authority, edits],
  )

  async function onConfirm(event: FormEvent) {
    event.preventDefault()
    if (!authority || !edits || pending || needsReconcile) return
    if (authority.id !== profileId) return
    const submitted = cloneEdits(edits)
    setSubmittedEdits(submitted)
    setPending(true)
    setError(null)
    setFieldErrors({})
    setStatus('idle')
    try {
      const next = await api.confirmProfile(profileId, buildConfirmation(authority, submitted))
      if (next.id !== profileId) return
      setAuthority(next)
      setEdits(fromProfile(next))
      setSubmittedEdits(null)
      setProfile(next)
      setStale(false)
      setNeedsReconcile(false)
      setStatus('success')
    } catch (caught) {
      if (isApiError(caught) && caught.status === 409) {
        setStale(true)
        setNeedsReconcile(true)
        setStatus('idle')
        setEdits(cloneEdits(submitted))
        try {
          const fresh = await api.getProfile(profileId)
          if (fresh.id !== profileId) return
          setAuthority(fresh)
          setProfile(fresh)
        } catch {
          /* keep stale banner */
        }
      } else if (isApiError(caught)) {
        setFieldErrors(caught.fieldErrors)
        setError(caught.userMessage)
      } else {
        setError('Something went wrong. Please try again.')
      }
    } finally {
      setPending(false)
    }
  }

  function keepSubmittedEdits() {
    if (!submittedEdits) return
    setEdits(cloneEdits(submittedEdits))
    setNeedsReconcile(false)
    setStale(false)
  }

  function acceptServerCopy() {
    if (!authority || authority.id !== profileId) return
    setEdits(fromProfile(authority))
    setSubmittedEdits(null)
    setNeedsReconcile(false)
    setStale(false)
  }

  if (!profile || profile.id !== profileId || !edits || !authority || authority.id !== profileId) {
    return <Loading label="Loading profile" />
  }

  return (
    <section className="stack">
      <h1>Review candidate profile</h1>
      {authority.status === 'DRAFT' ? (
        <Banner kind="warn">This profile is a draft. Resume suggestions need your review and confirmation before ranking or applying.</Banner>
      ) : (
        <Banner kind="ok">This profile is confirmed. You can correct it and confirm again.</Banner>
      )}
      {stale ? (
        <Banner kind="warn">
          The profile revision changed. Review the latest server copy, then confirm again. The previous save did not
          succeed.
        </Banner>
      ) : null}
      {needsReconcile ? (
        <div className="cluster">
          <button type="button" className="secondary" onClick={keepSubmittedEdits}>
            Keep my edits and use the latest revision
          </button>
          <button type="button" className="secondary" onClick={acceptServerCopy}>
            Replace the form with the latest server values
          </button>
        </div>
      ) : null}
      {error ? <Banner kind="error">{error}</Banner> : null}
      {status === 'success' ? <Banner kind="ok">Profile confirmed.</Banner> : null}
      {warnings.length > 0 ? (
        <Banner kind="info">
          Extraction warnings: {warnings.join('; ')}
        </Banner>
      ) : null}

      {authority.factExtraction ? (
        <details className="card">
          <summary>Original resume suggestions and evidence</summary>
          <p>These are the original extracted suggestions. Corrections you make are yours; this evidence does not claim that corrected values appeared in your resume.</p>
          {authority.factExtraction.evidence.length === 0 ? <p>No supported contact suggestions were found.</p> : (
            <ul>
              {authority.factExtraction.evidence.map((item, index) => (
                <li key={index}>
                  <strong>{item.field}</strong>: {item.suggestedValue} ({item.confidence}, {item.derivation.replaceAll('_', ' ').toLowerCase()})
                  {item.page !== null ? ` — page ${item.page}` : null}
                  <blockquote>{item.sourceText}</blockquote>
                </li>
              ))}
            </ul>
          )}
          {authority.factExtraction.diagnostics.length > 0 ? (
            <p>Some suggestions were unavailable or ambiguous: {authority.factExtraction.diagnostics.map((code) => code.replaceAll('_', ' ').toLowerCase()).join('; ')}.</p>
          ) : null}
        </details>
      ) : null}

      <form className="stack" onSubmit={onConfirm}>
        <fieldset className="card stack" disabled={pending}>
          <legend>Candidate facts</legend>
          <Field id="fullName" label="Full name" error={fieldErrors.fullName}>
            <input
              id="fullName"
              value={edits.facts.fullName ?? ''}
              onChange={(event) => patchFacts(edits, setEdits, { fullName: emptyToNull(event.target.value) })}
            />
          </Field>
          <Field id="email" label="Email" error={fieldErrors.email}>
            <input
              id="email"
              type="email"
              value={edits.facts.email ?? ''}
              onChange={(event) => patchFacts(edits, setEdits, { email: emptyToNull(event.target.value) })}
            />
          </Field>
          <Field id="phone" label="Phone" error={fieldErrors.phone}>
            <input
              id="phone"
              value={edits.facts.phone ?? ''}
              onChange={(event) => patchFacts(edits, setEdits, { phone: emptyToNull(event.target.value) })}
            />
          </Field>
          <Field id="location" label="Location" error={fieldErrors.location}>
            <input
              id="location"
              value={edits.facts.location ?? ''}
              onChange={(event) => patchFacts(edits, setEdits, { location: emptyToNull(event.target.value) })}
            />
          </Field>
          <Field id="currentTitle" label="Current title" error={fieldErrors.currentTitle}>
            <input
              id="currentTitle"
              value={edits.facts.currentTitle ?? ''}
              onChange={(event) => patchFacts(edits, setEdits, { currentTitle: emptyToNull(event.target.value) })}
            />
          </Field>
          <div className="stack" aria-label="Professional links">
            <h2>Professional links</h2>
            {(edits.facts.professionalLinks ?? []).map((link, index) => (
              <div key={index} className="stack">
                <Field id={`link-type-${index}`} label={`Link type ${index + 1}`}>
                  <select id={`link-type-${index}`} value={link.type} onChange={(event) => {
                    const professionalLinks = (edits.facts.professionalLinks ?? []).map((item, i) => i === index ? { ...item, type: event.target.value as ProfessionalLinkType } : item)
                    patchFacts(edits, setEdits, { professionalLinks })
                  }}>
                    {LINK_TYPES.map((type) => <option key={type} value={type}>{type}</option>)}
                  </select>
                </Field>
                <Field id={`link-url-${index}`} label={`Link URL ${index + 1}`}>
                  <input id={`link-url-${index}`} value={link.url} maxLength={2048} required onChange={(event) => {
                    const professionalLinks = (edits.facts.professionalLinks ?? []).map((item, i) => i === index ? { ...item, url: event.target.value } : item)
                    patchFacts(edits, setEdits, { professionalLinks })
                  }} />
                </Field>
                <button type="button" className="secondary" onClick={() => patchFacts(edits, setEdits, {
                  professionalLinks: (edits.facts.professionalLinks ?? []).filter((_, i) => i !== index),
                })}>Remove link {index + 1}</button>
              </div>
            ))}
            <button type="button" className="secondary" disabled={(edits.facts.professionalLinks?.length ?? 0) >= 20}
              onClick={() => patchFacts(edits, setEdits, {
                professionalLinks: [...(edits.facts.professionalLinks ?? []), { type: 'WEBSITE', url: '' }],
              })}>Add professional link</button>
          </div>
          <Field id="totalRelevantExperienceMonths" label="Relevant experience (months)">
            <input
              id="totalRelevantExperienceMonths"
              type="number"
              min={0}
              max={960}
              value={edits.facts.totalRelevantExperienceMonths ?? ''}
              onChange={(event) =>
                patchFacts(edits, setEdits, {
                  totalRelevantExperienceMonths: emptyToInt(event.target.value),
                })
              }
            />
          </Field>
          <Field id="workAuthorization" label="Work authorization">
            <select
              id="workAuthorization"
              value={edits.facts.workAuthorization}
              onChange={(event) =>
                patchFacts(edits, setEdits, { workAuthorization: event.target.value as CandidateFactState })
              }
            >
              {FACT_STATES.map((state) => (
                <option key={state} value={state}>
                  {state}
                </option>
              ))}
            </select>
          </Field>
          <Field id="sponsorshipRequired" label="Sponsorship required">
            <select
              id="sponsorshipRequired"
              value={edits.facts.sponsorshipRequired}
              onChange={(event) =>
                patchFacts(edits, setEdits, { sponsorshipRequired: event.target.value as CandidateFactState })
              }
            >
              {FACT_STATES.map((state) => (
                <option key={state} value={state}>
                  {state}
                </option>
              ))}
            </select>
          </Field>
          <Field id="noticePeriodDays" label="Notice period (days)">
            <input
              id="noticePeriodDays"
              type="number"
              min={0}
              max={730}
              value={edits.facts.noticePeriodDays ?? ''}
              onChange={(event) => patchFacts(edits, setEdits, { noticePeriodDays: emptyToInt(event.target.value) })}
            />
          </Field>
        </fieldset>

        <fieldset className="card stack" disabled={pending}>
          <legend>Structured profile</legend>
          <Field id="skills" label="Skills (one per line)">
            <textarea
              id="skills"
              value={edits.skills.join('\n')}
              onChange={(event) => setEdits({ ...edits, skills: lines(event.target.value) })}
            />
          </Field>
          <Field id="totalExperienceMonths" label="Total experience (months)">
            <input
              id="totalExperienceMonths"
              type="number"
              min={0}
              max={960}
              value={edits.totalExperienceMonths ?? ''}
              onChange={(event) =>
                setEdits({ ...edits, totalExperienceMonths: emptyToInt(event.target.value) })
              }
            />
          </Field>
          {edits.workExperience.map((job, index) => (
            <div key={index} className="stack">
              <h2>Work {index + 1}</h2>
              <Field id={`work-title-${index}`} label="Title">
                <input
                  id={`work-title-${index}`}
                  value={job.title ?? ''}
                  onChange={(event) => {
                    const workExperience = edits.workExperience.map((item, i) =>
                      i === index ? { ...item, title: emptyToNull(event.target.value) } : item,
                    )
                    setEdits({ ...edits, workExperience })
                  }}
                />
              </Field>
              <Field id={`work-company-${index}`} label="Company">
                <input
                  id={`work-company-${index}`}
                  value={job.company ?? ''}
                  onChange={(event) => {
                    const workExperience = edits.workExperience.map((item, i) =>
                      i === index ? { ...item, company: emptyToNull(event.target.value) } : item,
                    )
                    setEdits({ ...edits, workExperience })
                  }}
                />
              </Field>
              <label htmlFor={`work-ongoing-${index}`}>
                <input
                  id={`work-ongoing-${index}`}
                  type="checkbox"
                  checked={job.ongoing}
                  onChange={(event) => {
                    const workExperience = edits.workExperience.map((item, i) =>
                      i === index ? { ...item, ongoing: event.target.checked } : item,
                    )
                    setEdits({ ...edits, workExperience })
                  }}
                />{' '}
                Ongoing
              </label>
              <Field id={`work-bullets-${index}`} label="Bullets (one per line)">
                <textarea
                  id={`work-bullets-${index}`}
                  value={job.bullets.join('\n')}
                  onChange={(event) => {
                    const workExperience = edits.workExperience.map((item, i) =>
                      i === index ? { ...item, bullets: lines(event.target.value) } : item,
                    )
                    setEdits({ ...edits, workExperience })
                  }}
                />
              </Field>
            </div>
          ))}
          {edits.education.map((item, index) => (
            <div key={index} className="stack">
              <h2>Education {index + 1}</h2>
              <Field id={`edu-inst-${index}`} label="Institution">
                <input
                  id={`edu-inst-${index}`}
                  value={item.institution ?? ''}
                  onChange={(event) => {
                    const education = edits.education.map((row, i) =>
                      i === index ? { ...row, institution: emptyToNull(event.target.value) } : row,
                    )
                    setEdits({ ...edits, education })
                  }}
                />
              </Field>
              <Field id={`edu-qual-${index}`} label="Qualification">
                <input
                  id={`edu-qual-${index}`}
                  value={item.qualification ?? ''}
                  onChange={(event) => {
                    const education = edits.education.map((row, i) =>
                      i === index ? { ...row, qualification: emptyToNull(event.target.value) } : row,
                    )
                    setEdits({ ...edits, education })
                  }}
                />
              </Field>
            </div>
          ))}
          {edits.projects.map((item, index) => (
            <div key={index} className="stack">
              <h2>Project {index + 1}</h2>
              <Field id={`proj-title-${index}`} label="Title">
                <input
                  id={`proj-title-${index}`}
                  value={item.title ?? ''}
                  onChange={(event) => {
                    const projects = edits.projects.map((row, i) =>
                      i === index ? { ...row, title: emptyToNull(event.target.value) } : row,
                    )
                    setEdits({ ...edits, projects })
                  }}
                />
              </Field>
              <Field id={`proj-bullets-${index}`} label="Bullets (one per line)">
                <textarea
                  id={`proj-bullets-${index}`}
                  value={item.bullets.join('\n')}
                  onChange={(event) => {
                    const projects = edits.projects.map((row, i) =>
                      i === index ? { ...row, bullets: lines(event.target.value) } : row,
                    )
                    setEdits({ ...edits, projects })
                  }}
                />
              </Field>
            </div>
          ))}
        </fieldset>

        <p className="muted">
          Confirmation sends the complete structured profile, including fields not shown here. Revision{' '}
          {payloadPreview?.expectedRevision}.
        </p>
        <div className="cluster">
          <button type="submit" disabled={pending || needsReconcile}>
            {pending ? 'Confirming…' : 'Confirm profile'}
          </button>
          {confirmed ? <Link to={nextHref}>Continue to preferences</Link> : null}
        </div>
      </form>
    </section>
  )
}

function patchFacts(
  edits: VisibleProfileEdits,
  setEdits: (next: VisibleProfileEdits) => void,
  patch: Partial<VisibleProfileEdits['facts']>,
) {
  setEdits({ ...edits, facts: { ...edits.facts, ...patch } })
}

function lines(value: string): string[] {
  return value.split('\n').map((item) => item.trim()).filter(Boolean)
}

function emptyToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

function emptyToInt(value: string): number | null {
  if (value.trim() === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}
