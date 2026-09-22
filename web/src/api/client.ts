import { requestJson, requestMultipart } from './http'
import type {
  ApplicationDecisionResult,
  ApplicationReadinessResult,
  CandidateProfileConfirmationRequest,
  CandidateProfileResponse,
  JobRequirementsResponse,
  JobResponse,
  JobSearchPreferenceRequest,
  JobSearchPreferenceResponse,
  LiveOpportunityPageResponse,
  MatchResponse,
  OpportunityQuery,
  ResumeResponse,
  ResumeRouteRequest,
  ResumeRouteSnapshot,
} from './types'

export const api = {
  uploadResume(file: File): Promise<ResumeResponse> {
    const body = new FormData()
    body.append('file', file)
    return requestMultipart<ResumeResponse>('/api/resumes', body)
  },

  getProfile(id: number): Promise<CandidateProfileResponse> {
    return requestJson(`/api/candidate-profiles/${id}`)
  },

  confirmProfile(id: number, body: CandidateProfileConfirmationRequest): Promise<CandidateProfileResponse> {
    return requestJson(`/api/candidate-profiles/${id}/confirmation`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },

  getPreference(profileId: number): Promise<JobSearchPreferenceResponse | null> {
    return requestJson(`/api/candidate-profiles/${profileId}/job-search-preference`, {}, { allowNotFound: true })
  },

  replacePreference(profileId: number, body: JobSearchPreferenceRequest): Promise<JobSearchPreferenceResponse> {
    return requestJson(`/api/candidate-profiles/${profileId}/job-search-preference`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },

  listResumeRoutes(profileId: number): Promise<ResumeRouteSnapshot[]> {
    return requestJson(`/api/candidate-profiles/${profileId}/resume-routes`)
  },

  createResumeRoute(profileId: number, body: ResumeRouteRequest): Promise<ResumeRouteSnapshot> {
    return requestJson(`/api/candidate-profiles/${profileId}/resume-routes`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },

  getOpportunities(profileId: number, query: OpportunityQuery = {}): Promise<LiveOpportunityPageResponse> {
    const params = new URLSearchParams()
    if (query.page !== undefined) params.set('page', String(query.page))
    if (query.size !== undefined) params.set('size', String(query.size))
    if (query.minScore !== undefined) params.set('minScore', String(query.minScore))
    if (query.recommendation) params.set('recommendation', query.recommendation)
    if (query.readiness) params.set('readiness', query.readiness)
    const suffix = params.size > 0 ? `?${params.toString()}` : ''
    return requestJson(`/api/candidate-profiles/${profileId}/opportunities${suffix}`)
  },

  getJob(jobId: number): Promise<JobResponse> {
    return requestJson(`/api/jobs/${jobId}`)
  },

  getJobRequirements(jobId: number): Promise<JobRequirementsResponse> {
    return requestJson(`/api/jobs/${jobId}/requirements`)
  },

  assessJob(jobId: number, candidateProfileId: number): Promise<MatchResponse> {
    return requestJson(`/api/jobs/${jobId}/assessment`, {
      method: 'POST',
      body: JSON.stringify({ candidateProfileId }),
    })
  },

  getDecision(jobId: number, candidateProfileId: number): Promise<ApplicationDecisionResult> {
    return requestJson(`/api/jobs/${jobId}/application-decision?candidateProfileId=${candidateProfileId}`)
  },

  getReadiness(jobId: number, candidateProfileId: number): Promise<ApplicationReadinessResult> {
    return requestJson(`/api/jobs/${jobId}/application-readiness?candidateProfileId=${candidateProfileId}`)
  },
}
