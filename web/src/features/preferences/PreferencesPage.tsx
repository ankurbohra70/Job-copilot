import { useEffect, useState, type FormEvent } from 'react'
import { Link, useOutletContext } from 'react-router-dom'
import { api } from '../../api/client'
import { isApiError } from '../../api/errors'
import type {
  CandidateFactState,
  CandidateProfileResponse,
  JobSearchPreferenceRequest,
  ResumeStrategy,
  WorkArrangement,
} from '../../api/types'
import { Banner, Field, Loading } from '../../shared/ui/Feedback'

interface Outlet {
  profile: CandidateProfileResponse | null
  profileId: number
}

const STRATEGIES: ResumeStrategy[] = ['VOLUME', 'PRECISION']
const ARRANGEMENTS: WorkArrangement[] = ['REMOTE', 'HYBRID', 'ONSITE']
const FACT_STATES: CandidateFactState[] = ['UNKNOWN', 'YES', 'NO']

const EMPTY: JobSearchPreferenceRequest = {
  defaultResumeStrategy: 'VOLUME',
  targetRoles: [],
  excludedRoles: [],
  preferredLocations: [],
  acceptableWorkArrangements: [],
  minimumExperienceToleranceYears: null,
  maximumExperienceToleranceYears: null,
  freshnessDays: null,
  relocationWilling: 'UNKNOWN',
  compensationCurrency: null,
  minimumCompensation: null,
  desiredCompensation: null,
}

export function PreferencesPage() {
  const { profileId } = useOutletContext<Outlet>()
  const [form, setForm] = useState<JobSearchPreferenceRequest>(EMPTY)
  const [loading, setLoading] = useState(true)
  const [unset, setUnset] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    api
      .getPreference(profileId)
      .then((value) => {
        if (cancelled) return
        if (value === null) {
          setUnset(true)
          setForm(EMPTY)
        } else {
          setUnset(false)
          setForm({
            defaultResumeStrategy: value.preference.defaultResumeStrategy,
            targetRoles: value.preference.targetRoles,
            excludedRoles: value.preference.excludedRoles,
            preferredLocations: value.preference.preferredLocations,
            acceptableWorkArrangements: value.preference.acceptableWorkArrangements,
            minimumExperienceToleranceYears: value.preference.minimumExperienceToleranceYears,
            maximumExperienceToleranceYears: value.preference.maximumExperienceToleranceYears,
            freshnessDays: value.preference.freshnessDays,
            relocationWilling: value.preference.relocationWilling,
            compensationCurrency: value.preference.compensationCurrency,
            minimumCompensation: value.preference.minimumCompensation,
            desiredCompensation: value.preference.desiredCompensation,
          })
        }
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

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (pending) return
    setPending(true)
    setError(null)
    setFieldErrors({})
    setSaved(false)
    try {
      await api.replacePreference(profileId, form)
      setUnset(false)
      setSaved(true)
    } catch (caught) {
      if (isApiError(caught)) {
        setFieldErrors(caught.fieldErrors)
        setError(caught.userMessage)
      } else {
        setError('Something went wrong. Please try again.')
      }
    } finally {
      setPending(false)
    }
  }

  if (loading) return <Loading label="Loading preferences" />

  return (
    <section className="stack">
      <h1>Job search preferences</h1>
      {unset ? <Banner kind="info">No preference is saved yet. Search intent is required for a complete apply path.</Banner> : null}
      {error ? <Banner kind="error">{error}</Banner> : null}
      {saved ? <Banner kind="ok">Preferences saved.</Banner> : null}
      <form className="card stack" onSubmit={onSubmit}>
        <Field id="defaultResumeStrategy" label="Default resume strategy" error={fieldErrors.defaultResumeStrategy}>
          <select
            id="defaultResumeStrategy"
            required
            value={form.defaultResumeStrategy}
            onChange={(event) => setForm({ ...form, defaultResumeStrategy: event.target.value as ResumeStrategy })}
          >
            {STRATEGIES.map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </select>
        </Field>
        <Field id="targetRoles" label="Target roles (one per line)">
          <textarea
            id="targetRoles"
            value={form.targetRoles.join('\n')}
            onChange={(event) => setForm({ ...form, targetRoles: lines(event.target.value) })}
          />
        </Field>
        <Field id="excludedRoles" label="Excluded roles (one per line)">
          <textarea
            id="excludedRoles"
            value={form.excludedRoles.join('\n')}
            onChange={(event) => setForm({ ...form, excludedRoles: lines(event.target.value) })}
          />
        </Field>
        <Field id="preferredLocations" label="Preferred locations (one per line)">
          <textarea
            id="preferredLocations"
            value={form.preferredLocations.join('\n')}
            onChange={(event) => setForm({ ...form, preferredLocations: lines(event.target.value) })}
          />
        </Field>
        <fieldset>
          <legend>Acceptable work arrangements</legend>
          {ARRANGEMENTS.map((item) => (
            <label key={item} htmlFor={`arr-${item}`} style={{ fontWeight: 500 }}>
              <input
                id={`arr-${item}`}
                type="checkbox"
                checked={form.acceptableWorkArrangements.includes(item)}
                onChange={(event) => {
                  const next = event.target.checked
                    ? [...form.acceptableWorkArrangements, item]
                    : form.acceptableWorkArrangements.filter((value) => value !== item)
                  setForm({ ...form, acceptableWorkArrangements: next })
                }}
              />{' '}
              {item}
            </label>
          ))}
        </fieldset>
        <Field id="freshnessDays" label="Listing freshness (days)">
          <input
            id="freshnessDays"
            type="number"
            min={1}
            max={365}
            value={form.freshnessDays ?? ''}
            onChange={(event) => setForm({ ...form, freshnessDays: emptyToInt(event.target.value) })}
          />
        </Field>
        <Field id="relocationWilling" label="Willing to relocate">
          <select
            id="relocationWilling"
            value={form.relocationWilling}
            onChange={(event) => setForm({ ...form, relocationWilling: event.target.value as CandidateFactState })}
          >
            {FACT_STATES.map((state) => (
              <option key={state} value={state}>
                {state}
              </option>
            ))}
          </select>
        </Field>
        <Field id="compensationCurrency" label="Compensation currency (ISO 3-letter)">
          <input
            id="compensationCurrency"
            maxLength={3}
            value={form.compensationCurrency ?? ''}
            onChange={(event) =>
              setForm({ ...form, compensationCurrency: event.target.value.trim() === '' ? null : event.target.value })
            }
          />
        </Field>
        <Field id="minimumCompensation" label="Minimum compensation">
          <input
            id="minimumCompensation"
            type="number"
            min={0}
            value={form.minimumCompensation ?? ''}
            onChange={(event) => setForm({ ...form, minimumCompensation: emptyToNumber(event.target.value) })}
          />
        </Field>
        <Field id="desiredCompensation" label="Desired compensation">
          <input
            id="desiredCompensation"
            type="number"
            min={0}
            value={form.desiredCompensation ?? ''}
            onChange={(event) => setForm({ ...form, desiredCompensation: emptyToNumber(event.target.value) })}
          />
        </Field>
        <div className="cluster">
          <button type="submit" disabled={pending}>
            {pending ? 'Saving…' : 'Save preferences'}
          </button>
          <Link to={`/profiles/${profileId}/resume-routes`}>Continue to resume routes</Link>
        </div>
      </form>
    </section>
  )
}

function lines(value: string): string[] {
  return value.split('\n').map((item) => item.trim()).filter(Boolean)
}
function emptyToInt(value: string): number | null {
  if (value.trim() === '') return null
  const parsed = Number(value)
  return Number.isInteger(parsed) ? parsed : null
}
function emptyToNumber(value: string): number | null {
  if (value.trim() === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}
