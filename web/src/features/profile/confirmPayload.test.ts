import { describe, expect, it } from 'vitest'
import { buildConfirmation, factsToRequest } from './confirmPayload'
import { sampleProfile, sampleFactProfile } from '../../test/fixtures'

describe('buildConfirmation', () => {
  it('copies links without mutating authority and excludes server-owned extraction metadata', () => {
    const authority = sampleFactProfile()
    const facts = factsToRequest(authority.facts)
    facts.professionalLinks![0]!.url = ' https://github.com/grace '
    expect(authority.facts.professionalLinks![0]!.url).toBe('https://github.com/ada')
    const payload = buildConfirmation(authority, {
      facts, skills: authority.profile.skills, totalExperienceMonths: authority.profile.totalExperienceMonths,
      workExperience: authority.profile.workExperience, education: authority.profile.education, projects: authority.profile.projects,
    })
    expect(payload.facts.professionalLinks![0]!.url).toBe('https://github.com/grace')
    expect(payload).not.toHaveProperty('factExtraction')
    expect(payload).not.toHaveProperty('factExtractorVersion')
    expect(payload.facts).not.toHaveProperty('factExtraction')
    expect(payload.profile.evidence).toEqual(authority.profile.evidence)
  })
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
