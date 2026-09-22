import type {
  CandidateProfileConfirmationRequest,
  CandidateProfileData,
  CandidateProfileFacts,
  CandidateProfileRequest,
  CandidateProfileResponse,
  Education,
  Project,
  WorkExperience,
} from '../../api/types'

export interface VisibleProfileEdits {
  facts: CandidateProfileRequest
  skills: string[]
  totalExperienceMonths: number | null
  workExperience: Array<Pick<WorkExperience, 'title' | 'company' | 'ongoing' | 'bullets'>>
  education: Array<Pick<Education, 'institution' | 'qualification'>>
  projects: Array<Pick<Project, 'title' | 'bullets' | 'technologies'>>
}

export function factsToRequest(facts: CandidateProfileFacts): CandidateProfileRequest {
  return {
    fullName: facts.fullName,
    email: facts.email,
    phone: facts.phone,
    location: facts.location,
    currentTitle: facts.currentTitle,
    totalRelevantExperienceMonths: facts.totalRelevantExperienceMonths,
    workAuthorization: facts.workAuthorization ?? 'UNKNOWN',
    sponsorshipRequired: facts.sponsorshipRequired ?? 'UNKNOWN',
    noticePeriodDays: facts.noticePeriodDays,
  }
}

export function fromProfile(profile: CandidateProfileResponse): VisibleProfileEdits {
  return {
    facts: factsToRequest(profile.facts),
    skills: [...profile.profile.skills],
    totalExperienceMonths: profile.profile.totalExperienceMonths,
    workExperience: profile.profile.workExperience.map((item) => ({
      title: item.title,
      company: item.company,
      ongoing: item.ongoing,
      bullets: [...item.bullets],
    })),
    education: profile.profile.education.map((item) => ({
      institution: item.institution,
      qualification: item.qualification,
    })),
    projects: profile.profile.projects.map((item) => ({
      title: item.title,
      bullets: [...item.bullets],
      technologies: [...item.technologies],
    })),
  }
}

export function cloneEdits(edits: VisibleProfileEdits): VisibleProfileEdits {
  return structuredClone(edits)
}

export function buildConfirmation(
  authority: CandidateProfileResponse,
  edits: VisibleProfileEdits,
): CandidateProfileConfirmationRequest {
  return {
    expectedRevision: authority.revision,
    profile: mergeProfile(authority.profile, edits),
    facts: edits.facts,
  }
}

function mergeProfile(original: CandidateProfileData, edits: VisibleProfileEdits): CandidateProfileData {
  return {
    ...original,
    skills: edits.skills,
    totalExperienceMonths: edits.totalExperienceMonths,
    workExperience: edits.workExperience.map((item, index) => ({
      ...(original.workExperience[index] ?? emptyWork()),
      title: item.title,
      company: item.company,
      ongoing: item.ongoing,
      bullets: item.bullets,
    })),
    education: edits.education.map((item, index) => ({
      ...(original.education[index] ?? emptyEducation()),
      institution: item.institution,
      qualification: item.qualification,
    })),
    projects: edits.projects.map((item, index) => ({
      ...(original.projects[index] ?? emptyProject()),
      title: item.title,
      bullets: item.bullets,
      technologies: item.technologies,
    })),
  }
}

function emptyWork(): WorkExperience {
  return {
    title: null,
    company: null,
    start: null,
    end: null,
    ongoing: false,
    bullets: [],
    sourceText: null,
  }
}

function emptyEducation(): Education {
  return {
    institution: null,
    qualification: null,
    start: null,
    end: null,
    sourceText: null,
  }
}

function emptyProject(): Project {
  return {
    title: null,
    bullets: [],
    technologies: [],
    sourceText: null,
  }
}
