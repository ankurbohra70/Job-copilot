import type { CandidateProfileResponse, LiveOpportunityResponse } from '../api/types'

export function sampleProfile(overrides: Partial<CandidateProfileResponse> = {}): CandidateProfileResponse {
  return {
    id: 1,
    resumeId: 9,
    profile: {
      skills: ['Java', 'Spring'],
      totalExperienceMonths: 48,
      observedExperienceMonths: 48,
      experienceAssessment: 'KNOWN',
      workExperience: [
        {
          title: 'Engineer',
          company: 'Acme',
          start: { year: 2020, month: 1, precision: 'MONTH' },
          end: null,
          ongoing: true,
          bullets: ['Built APIs'],
          sourceText: 'Engineer at Acme 2020-present',
        },
      ],
      education: [
        {
          institution: 'State University',
          qualification: 'BSc',
          start: { year: 2014, month: null, precision: 'YEAR' },
          end: { year: 2018, month: null, precision: 'YEAR' },
          sourceText: 'BSc State University',
        },
      ],
      projects: [
        {
          title: 'Copilot',
          bullets: ['Matching engine'],
          technologies: ['Java'],
          sourceText: 'Project Copilot',
        },
      ],
      keywords: ['backend'],
      roleCategories: ['software-engineering'],
      evidence: [{ kind: 'SKILL', value: 'Java', sourceText: 'Java, Spring Boot' }],
      warnings: ['Phone not found'],
    },
    facts: {
      fullName: 'Ada Lovelace',
      email: 'ada@example.com',
      phone: null,
      location: 'London',
      currentTitle: 'Engineer',
      totalRelevantExperienceMonths: 48,
      workAuthorization: 'UNKNOWN',
      sponsorshipRequired: 'UNKNOWN',
      noticePeriodDays: null,
      professionalLinks: [],
    },
    schemaVersion: 'v1',
    parserVersion: 'deterministic-v1',
    vocabularyVersion: 'v1',
    assessedOn: '2026-09-21',
    status: 'DRAFT',
    revision: 1,
    confirmedAt: null,
    createdAt: '2026-09-21T00:00:00',
    updatedAt: '2026-09-21T00:00:00',
    ...overrides,
  }
}

export function sampleFactProfile(overrides: Partial<CandidateProfileResponse> = {}): CandidateProfileResponse {
  const base = sampleProfile()
  const facts: CandidateProfileResponse['facts'] = {
    ...base.facts,
    phone: '+442079460018',
    professionalLinks: [{ type: 'GITHUB', url: 'https://github.com/ada' }, { type: 'LINKEDIN', url: 'https://linkedin.com/in/ada' }],
  }
  return {
    ...base, facts, parserVersion: 'rules-v5', factExtractorVersion: 'facts-v1',
    factExtraction: {
      schemaVersion: 'v1', suggestions: structuredClone(facts), diagnostics: [],
      evidence: [{ field: 'email', suggestedValue: 'ada@example.com', rawValue: 'ada@example.com',
        confidence: 'HIGH', derivation: 'HEADER_TEXT', sourceText: 'Email: ada@example.com',
        page: 1, lineReference: 'p1-l2', bounds: { x: 40, y: 70, width: 150, height: 12 }, workReference: null }],
    },
    ...overrides,
  }
}

export function sampleOpportunity(): LiveOpportunityResponse {
  return {
    rank: 1,
    job: {
      id: 44,
      title: 'Backend Engineer',
      company: 'Example',
      location: 'Remote',
      jobUrl: 'https://example.com/jobs/44',
      status: 'DISCOVERED',
      createdAt: '2026-09-01T00:00:00',
      updatedAt: '2026-09-01T00:00:00',
    },
    listing: {
      listingId: 7,
      sourceId: 3,
      externalJobId: 'ext-44',
      hostedJobUrl: 'https://example.com/jobs/44',
      applyUrl: 'https://example.com/apply/44',
      lastVerifiedAt: '2026-09-21T00:00:00',
    },
    match: {
      candidateProfileId: 1,
      jobId: 44,
      algorithmVersion: 'v1',
      vocabularyVersion: 'v1',
      profileParserVersion: 'deterministic-v1',
      profileAssessedOn: '2026-09-21',
      profileRevision: 2,
      jobUpdatedAt: '2026-09-01T00:00:00',
      overallScore: 81,
      recommendation: 'GOOD_MATCH',
      matchedRequiredSkills: ['Java'],
      missingRequiredSkills: [],
      matchedPreferredSkills: [],
      unmatchedPreferredSkills: [],
      experienceComparison: { requiredYears: 3, candidateYears: 4, status: 'MEETS_REQUIREMENT', score: 1 },
      roleRelevance: { status: 'ASSESSED', score: 1, matchedTerms: ['backend'] },
      keywordRelevance: { status: 'ASSESSED', score: 1, matchedTerms: [] },
      breakdown: [],
      appliedCaps: [],
      strengths: [{ code: 'SKILLS', message: 'Required skills matched', evidence: ['Java'] }],
      gaps: [],
      warnings: [],
      unassessedFactors: [],
    },
    decision: {
      jobId: 44,
      candidateProfileId: 1,
      decision: 'APPLY_VOLUME',
      reasons: ['GOOD_MATCH_VOLUME_ROUTE'],
      rankingScore: 81,
      assessmentRecommendation: 'GOOD_MATCH',
      candidateDataMissing: false,
      recommendedResumeStrategy: 'VOLUME',
      policyVersion: 'application-decision-v1',
    },
    readiness: {
      jobId: 44,
      candidateProfileId: 1,
      readiness: 'READY',
      decision: {
        jobId: 44,
        candidateProfileId: 1,
        decision: 'APPLY_VOLUME',
        reasons: ['GOOD_MATCH_VOLUME_ROUTE'],
        rankingScore: 81,
        assessmentRecommendation: 'GOOD_MATCH',
        candidateDataMissing: false,
        recommendedResumeStrategy: 'VOLUME',
        policyVersion: 'application-decision-v1',
      },
      resumeRoute: {
        id: 1,
        candidateProfileId: 1,
        resumeId: 9,
        strategy: 'VOLUME',
        roleFamily: null,
        defaultRoute: true,
        variantLabel: 'Default VOLUME resume',
        approved: true,
        sourceFileName: 'resume.pdf',
        sourceVersion: 'pdfbox-v1',
        createdAt: '2026-09-21T00:00:00',
      },
      checks: [{ code: 'APPLICATION_URL', status: 'PASS', message: 'A valid verified application URL is available' }],
    },
  }
}
