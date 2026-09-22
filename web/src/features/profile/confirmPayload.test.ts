import { describe, expect, it } from 'vitest'
import { buildConfirmation, factsToRequest } from './confirmPayload'
import { sampleProfile } from '../../test/fixtures'

describe('buildConfirmation', () => {
  it('keeps hidden nested fields while merging visible edits', () => {
    const authority = sampleProfile()
    const payload = buildConfirmation(authority, {
      facts: { ...factsToRequest(authority.facts), fullName: 'Ada L.' },
      skills: ['Java', 'Kotlin'],
      totalExperienceMonths: 60,
      workExperience: [
        { title: 'Staff Engineer', company: 'Acme', ongoing: true, bullets: ['Built APIs'] },
      ],
      education: [{ institution: 'State University', qualification: 'BSc' }],
      projects: [{ title: 'Copilot', bullets: ['Matching engine'], technologies: ['Java'] }],
    })

    expect(payload.expectedRevision).toBe(1)
    expect(payload.facts.fullName).toBe('Ada L.')
    expect(payload.profile.skills).toEqual(['Java', 'Kotlin'])
    expect(payload.profile.keywords).toEqual(['backend'])
    expect(payload.profile.roleCategories).toEqual(['software-engineering'])
    expect(payload.profile.warnings).toEqual(['Phone not found'])
    expect(payload.profile.evidence).toEqual(authority.profile.evidence)
    expect(payload.profile.workExperience[0]?.sourceText).toBe('Engineer at Acme 2020-present')
    expect(payload.profile.education[0]?.sourceText).toBe('BSc State University')
    expect(payload.profile.projects[0]?.sourceText).toBe('Project Copilot')
    expect(payload.profile.workExperience[0]?.start).toEqual({ year: 2020, month: 1, precision: 'MONTH' })
  })
})
