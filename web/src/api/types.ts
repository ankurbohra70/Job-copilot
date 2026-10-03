export type CandidateProfileStatus = 'DRAFT' | 'CONFIRMED'
export type CandidateFactState = 'YES' | 'NO' | 'UNKNOWN'
export type ResumeStrategy = 'VOLUME' | 'PRECISION'
export type WorkArrangement = 'REMOTE' | 'HYBRID' | 'ONSITE'
export type Recommendation = 'STRONG_MATCH' | 'GOOD_MATCH' | 'WEAK_MATCH' | 'NOT_RECOMMENDED'
export type ApplicationDecision =
  | 'SKIP'
  | 'SAVE'
  | 'APPLY_VOLUME'
  | 'APPLY_PRECISION'
  | 'NEEDS_USER'
export type ApplicationReadiness = 'READY' | 'NEEDS_USER' | 'NOT_READY'
export type ReadinessCheckStatus = 'PASS' | 'FAIL' | 'NEEDS_USER' | 'NOT_APPLICABLE'
export type ExperienceAssessment = 'KNOWN' | 'UNKNOWN'
export type MatchStatus = 'ASSESSED' | 'UNKNOWN' | 'NOT_APPLICABLE' | 'MEETS_REQUIREMENT' | 'BELOW_REQUIREMENT'
export type JobStatus = 'DISCOVERED' | 'SHORTLISTED' | 'APPLIED' | 'INTERVIEWING' | 'OFFER' | 'REJECTED' | 'WITHDRAWN'

export interface PartialDate {
  year: number
  month: number | null
  precision: 'MONTH' | 'YEAR'
}

export interface Evidence {
  kind: string
  value: string
  sourceText: string
}

export interface WorkExperience {
  title: string | null
  company: string | null
  start: PartialDate | null
  end: PartialDate | null
  ongoing: boolean
  bullets: string[]
  sourceText: string | null
}

export interface Education {
  institution: string | null
  qualification: string | null
  start: PartialDate | null
  end: PartialDate | null
  sourceText: string | null
}

export interface Project {
  title: string | null
  bullets: string[]
  technologies: string[]
  sourceText: string | null
}

export interface CandidateProfileData {
  skills: string[]
  totalExperienceMonths: number | null
  observedExperienceMonths: number | null
  experienceAssessment: ExperienceAssessment
  workExperience: WorkExperience[]
  education: Education[]
  projects: Project[]
  keywords: string[]
  roleCategories: string[]
  evidence: Evidence[]
  warnings: string[]
}

export interface CandidateProfileFacts {
  fullName: string | null
  email: string | null
  phone: string | null
  location: string | null
  currentTitle: string | null
  totalRelevantExperienceMonths: number | null
  workAuthorization: CandidateFactState
  sponsorshipRequired: CandidateFactState
  noticePeriodDays: number | null
  professionalLinks?: ProfessionalLink[]
}

export type ProfessionalLinkType = 'LINKEDIN' | 'GITHUB' | 'LEETCODE' | 'WEBSITE' | 'OTHER'
export interface ProfessionalLink { type: ProfessionalLinkType; url: string }
export interface CandidateFactEvidence {
  field: string
  suggestedValue: string
  rawValue: string | null
  confidence: 'HIGH' | 'MEDIUM'
  derivation: 'HEADER_TEXT' | 'PDF_URI_ANNOTATION' | 'STRUCTURED_WORK'
  sourceText: string
  page: number | null
  lineReference: string | null
  bounds: { x: number; y: number; width: number; height: number } | null
  workReference: number | null
}
export interface CandidateFactExtractionData {
  schemaVersion: string
  suggestions: CandidateProfileFacts
  evidence: CandidateFactEvidence[]
  diagnostics: string[]
}

export interface CandidateProfileResponse {
  id: number
  resumeId: number | null
  profile: CandidateProfileData
  facts: CandidateProfileFacts
  schemaVersion: string
  parserVersion: string
  vocabularyVersion: string
  assessedOn: string
  status: CandidateProfileStatus
  revision: number
  confirmedAt: string | null
  createdAt: string
  updatedAt: string
  totalYearsExperience?: number | null
  factExtractorVersion?: string | null
  factExtraction?: CandidateFactExtractionData | null
}

export interface CandidateProfileRequest {
  fullName: string | null
  email: string | null
  phone: string | null
  location: string | null
  currentTitle: string | null
  totalRelevantExperienceMonths: number | null
  workAuthorization: CandidateFactState
  sponsorshipRequired: CandidateFactState
  noticePeriodDays: number | null
  professionalLinks?: ProfessionalLink[]
}

export interface CandidateProfileConfirmationRequest {
  expectedRevision: number
  profile: CandidateProfileData
  facts: CandidateProfileRequest
}

export interface ResumeResponse {
  id: number
  fileName: string
  sizeBytes: number
  pageCount: number
  extractorVersion: string
  createdAt: string
  candidateProfile: CandidateProfileResponse
  layoutVersion?: string | null
}

export interface JobSearchPreferenceData {
  defaultResumeStrategy: ResumeStrategy
  targetRoles: string[]
  excludedRoles: string[]
  preferredLocations: string[]
  acceptableWorkArrangements: WorkArrangement[]
  minimumExperienceToleranceYears: number | null
  maximumExperienceToleranceYears: number | null
  freshnessDays: number | null
  relocationWilling: CandidateFactState
  compensationCurrency: string | null
  minimumCompensation: number | null
  desiredCompensation: number | null
}

export interface JobSearchPreferenceRequest {
  defaultResumeStrategy: ResumeStrategy
  targetRoles: string[]
  excludedRoles: string[]
  preferredLocations: string[]
  acceptableWorkArrangements: WorkArrangement[]
  minimumExperienceToleranceYears: number | null
  maximumExperienceToleranceYears: number | null
  freshnessDays: number | null
  relocationWilling: CandidateFactState
  compensationCurrency: string | null
  minimumCompensation: number | null
  desiredCompensation: number | null
}

export interface JobSearchPreferenceResponse {
  id: number
  candidateProfileId: number
  preference: JobSearchPreferenceData
  createdAt: string
  updatedAt: string
}

export interface ResumeRouteSnapshot {
  id: number
  candidateProfileId: number
  resumeId: number
  strategy: ResumeStrategy
  roleFamily: string | null
  defaultRoute: boolean
  variantLabel: string
  approved: boolean
  sourceFileName: string | null
  sourceVersion: string | null
  createdAt: string
}

export interface ResumeRouteRequest {
  resumeId: number
  strategy: ResumeStrategy
  roleFamily: string | null
  defaultRoute: boolean
  variantLabel: string
  approved: boolean
}

export interface RankingJobSummary {
  id: number
  title: string
  company: string
  location: string | null
  jobUrl: string | null
  status: JobStatus
  createdAt: string
  updatedAt: string
}

export interface Explanation {
  code: string
  message: string
  evidence: string[]
}

export interface Cap {
  code: string
  maximumScore: number
}

export interface ExperienceComparison {
  requiredYears: number | null
  candidateYears: number | null
  status: MatchStatus
  score: number | null
}

export interface Relevance {
  status: MatchStatus
  score: number | null
  matchedTerms: string[]
}

export interface Breakdown {
  category: string
  status: MatchStatus
  score: number | null
  effectiveWeight: number | null
  contribution: number | null
}

export interface MatchResponse {
  candidateProfileId: number
  jobId: number
  algorithmVersion: string
  vocabularyVersion: string
  profileParserVersion: string
  profileAssessedOn: string
  profileRevision: number
  jobUpdatedAt: string
  overallScore: number
  recommendation: Recommendation
  matchedRequiredSkills: string[]
  missingRequiredSkills: string[]
  matchedPreferredSkills: string[]
  unmatchedPreferredSkills: string[]
  experienceComparison: ExperienceComparison
  roleRelevance: Relevance
  keywordRelevance: Relevance
  breakdown: Breakdown[]
  appliedCaps: Cap[]
  strengths: Explanation[]
  gaps: Explanation[]
  warnings: string[]
  unassessedFactors: string[]
}

export interface ApplicationDecisionResult {
  jobId: number
  candidateProfileId: number
  decision: ApplicationDecision
  reasons: string[]
  rankingScore: number | null
  assessmentRecommendation: Recommendation | null
  candidateDataMissing: boolean
  recommendedResumeStrategy: ResumeStrategy | null
  policyVersion: string
}

export interface ReadinessCheck {
  code: string
  status: ReadinessCheckStatus
  message: string
}

export interface ApplicationReadinessResult {
  jobId: number
  candidateProfileId: number
  readiness: ApplicationReadiness
  decision: ApplicationDecisionResult
  resumeRoute: ResumeRouteSnapshot | null
  checks: ReadinessCheck[]
}

export interface OpportunityListingSummary {
  listingId: number
  sourceId: number
  externalJobId: string
  hostedJobUrl: string | null
  applyUrl: string | null
  lastVerifiedAt: string | null
}

export interface LiveOpportunityResponse {
  rank: number
  job: RankingJobSummary
  listing: OpportunityListingSummary
  match: MatchResponse
  decision: ApplicationDecisionResult
  readiness: ApplicationReadinessResult
}

export interface UnassessedJobResponse {
  job: RankingJobSummary
  reason: string
  message: string
}

export interface LiveOpportunityPageResponse {
  candidateProfileId: number
  profileRevision: number
  algorithmVersion: string
  vocabularyVersion: string
  profileParserVersion: string
  profileAssessedOn: string
  preferenceId: number | null
  preferenceUpdatedAt: string | null
  liveListingCount: number
  eligibleListingCount: number
  ineligibleListingCount: number
  computableJobCount: number
  unassessedJobCount: number
  excludedJobCount: number
  filteredJobCount: number
  pageResultCount: number
  page: number
  size: number
  totalPages: number
  first: boolean
  last: boolean
  opportunities: LiveOpportunityResponse[]
  unassessedJobs: UnassessedJobResponse[]
}

export interface JobResponse {
  id: number
  title: string
  company: string
  location: string | null
  jobUrl: string | null
  description: string | null
  source: string | null
  externalJobId: string | null
  status: JobStatus
  createdAt: string
  updatedAt: string
}

export interface JobRequirementsResponse {
  requiredSkills: string[]
  preferredSkills: string[]
  minYearsExperience: number | null
}

export interface OpportunityQuery {
  page?: number
  size?: number
  minScore?: number
  recommendation?: Recommendation
  readiness?: ApplicationReadiness
}
