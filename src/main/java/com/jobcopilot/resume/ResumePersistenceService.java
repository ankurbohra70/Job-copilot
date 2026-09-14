package com.jobcopilot.resume;

import com.jobcopilot.resume.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import static com.jobcopilot.resume.ResumeExceptions.*;

@Service
public class ResumePersistenceService {
    private final ResumeRepository resumes;
    private final CandidateProfileRepository profiles;
    ResumePersistenceService(ResumeRepository resumes, CandidateProfileRepository profiles) {
        this.resumes = resumes; this.profiles = profiles;
    }
    @Transactional
    public ResumeResponse save(String filename, long size, String text, int pages, String extractorVersion,
            CandidateProfileData data, LocalDate date, String parserVersion, String vocabularyVersion) {
        Resume resume = resumes.save(new Resume(filename, size, text, pages, extractorVersion));
        CandidateProfile profile = profiles.saveAndFlush(new CandidateProfile(resume, data, date, parserVersion, vocabularyVersion));
        return response(resume, profile);
    }
    @Transactional(readOnly = true)
    public ResumeResponse getResume(Long id) {
        Resume resume = resumes.findById(id).orElseThrow(() -> new ResumeNotFoundException(id));
        CandidateProfile profile = profiles.findByResumeId(id).orElseThrow(() -> new IllegalStateException("Resume has no profile"));
        return response(resume, profile);
    }
    @Transactional(readOnly = true)
    public CandidateProfileResponse getProfile(Long id) { return profileResponse(findProfile(id)); }
    @Transactional
    public CandidateProfileResponse createProfile(com.jobcopilot.resume.dto.CandidateProfileRequest request) {
        CandidateProfile profile = new CandidateProfile(emptyProfileData(), LocalDate.now());
        profile.replaceFacts(toFacts(request));
        return profileResponse(profiles.saveAndFlush(profile));
    }
    @Transactional
    public CandidateProfileResponse updateProfile(Long id, com.jobcopilot.resume.dto.CandidateProfileRequest request) {
        CandidateProfile profile = findProfile(id);
        profile.replaceFacts(toFacts(request));
        return profileResponse(profiles.saveAndFlush(profile));
    }
    @Transactional(readOnly = true)
    public CandidateProfileFacts facts(Long id) { return findProfile(id).facts(); }
    @Transactional(readOnly = true)
    public CandidateMatchingSnapshot matchingSnapshot(Long id) {
        CandidateProfile p = findProfile(id);
        return new CandidateMatchingSnapshot(p.id(), matchingData(p), p.resume() == null ? "" : p.resume().text(), p.parserVersion(), p.vocabularyVersion(), p.assessedOn());
    }
    private CandidateProfile findProfile(Long id) { return profiles.findById(id).orElseThrow(() -> new CandidateProfileNotFoundException(id)); }
    private static CandidateProfileResponse profileResponse(CandidateProfile p) {
        return new CandidateProfileResponse(p.id(), p.resume() == null ? null : p.resume().id(), p.data(), p.facts(),
                p.schemaVersion(), p.parserVersion(), p.vocabularyVersion(), p.assessedOn(), p.createdAt(), p.updatedAt());
    }
    private static ResumeResponse response(Resume r, CandidateProfile p) {
        return new ResumeResponse(r.id(), r.fileName(), r.sizeBytes(), r.pageCount(), r.extractorVersion(), r.createdAt(), profileResponse(p));
    }
    private static CandidateProfileFacts toFacts(com.jobcopilot.resume.dto.CandidateProfileRequest r) {
        return new CandidateProfileFacts(r.fullName(), r.email(), r.phone(), r.location(), r.currentTitle(),
                r.totalRelevantExperienceMonths(), r.workAuthorization(), r.sponsorshipRequired(),
                r.relocationWilling(), r.noticePeriodDays(), r.compensationCurrency(),
                r.minimumCompensation(), r.desiredCompensation());
    }
    private static CandidateProfileData emptyProfileData() {
        return new CandidateProfileData(java.util.List.of(), null, 0, CandidateProfileData.Assessment.UNKNOWN,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of());
    }
    private static CandidateProfileData matchingData(CandidateProfile p) {
        Integer explicit = p.facts().totalRelevantExperienceMonths();
        if (explicit == null) return p.data();
        CandidateProfileData d = p.data();
        return new CandidateProfileData(d.skills(), explicit, d.observedExperienceMonths(),
                CandidateProfileData.Assessment.KNOWN, d.workExperience(), d.education(), d.projects(),
                d.keywords(), d.roleCategories(), d.evidence(), d.warnings());
    }
}

