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
    private final CandidateProfileValidator validator;
    ResumePersistenceService(ResumeRepository resumes, CandidateProfileRepository profiles,
            CandidateProfileValidator validator) {
        this.resumes = resumes; this.profiles = profiles; this.validator = validator;
    }
    @Transactional
    public ResumeResponse save(String filename, long size, String text, int pages, String extractorVersion,
            CandidateProfileData data, LocalDate date, String parserVersion, String vocabularyVersion) {
        return save(filename, size, text, pages, extractorVersion, data, date, parserVersion,
                vocabularyVersion, CandidateProfileStatus.CONFIRMED);
    }
    @Transactional
    public ResumeResponse saveDraft(String filename, long size, String text, int pages, String extractorVersion,
            CandidateProfileData data, LocalDate date, String parserVersion, String vocabularyVersion) {
        return save(filename, size, text, pages, extractorVersion, data, date, parserVersion,
                vocabularyVersion, CandidateProfileStatus.DRAFT);
    }
    private ResumeResponse save(String filename, long size, String text, int pages, String extractorVersion,
            CandidateProfileData data, LocalDate date, String parserVersion, String vocabularyVersion,
            CandidateProfileStatus status) {
        Resume resume = resumes.save(new Resume(filename, size, text, pages, extractorVersion));
        CandidateProfile profile = profiles.saveAndFlush(new CandidateProfile(
                resume, validator.canonicalize(data), date, parserVersion, vocabularyVersion, status));
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
    @Transactional
    public CandidateProfileResponse confirm(Long id,
            com.jobcopilot.resume.dto.CandidateProfileConfirmationRequest request) {
        CandidateProfile profile = findProfile(id);
        if (profile.revision() != request.expectedRevision())
            throw new CandidateProfileRevisionConflictException(id);
        CandidateProfileData data = validator.canonicalize(request.profile());
        CandidateProfileFacts facts = toFacts(request.facts());
        if (!profile.confirmOrReplace(data, facts)) return profileResponse(profile);
        try {
            return profileResponse(profiles.saveAndFlush(profile));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException
                | jakarta.persistence.OptimisticLockException exception) {
            throw new CandidateProfileRevisionConflictException(id);
        }
    }
    @Transactional(readOnly = true)
    public CandidateProfileFacts facts(Long id) { return findProfile(id).facts(); }
    @Transactional(readOnly = true)
    public CandidateMatchingSnapshot matchingSnapshot(Long id) {
        CandidateProfile p = findProfile(id);
        if (p.status() != CandidateProfileStatus.CONFIRMED)
            throw new CandidateProfileNotConfirmedException(id);
        return new CandidateMatchingSnapshot(p.id(), p.data(), p.parserVersion(), p.vocabularyVersion(),
                p.assessedOn(), p.revision());
    }
    private CandidateProfile findProfile(Long id) { return profiles.findById(id).orElseThrow(() -> new CandidateProfileNotFoundException(id)); }
    private static CandidateProfileResponse profileResponse(CandidateProfile p) {
        return new CandidateProfileResponse(p.id(), p.resume() == null ? null : p.resume().id(), p.data(), p.facts(),
                p.schemaVersion(), p.parserVersion(), p.vocabularyVersion(), p.assessedOn(),
                p.status(), p.revision(), p.confirmedAt(), p.createdAt(), p.updatedAt());
    }
    private static ResumeResponse response(Resume r, CandidateProfile p) {
        return new ResumeResponse(r.id(), r.fileName(), r.sizeBytes(), r.pageCount(), r.extractorVersion(), r.createdAt(), profileResponse(p));
    }
    private static CandidateProfileFacts toFacts(com.jobcopilot.resume.dto.CandidateProfileRequest r) {
        return new CandidateProfileFacts(r.fullName(), r.email(), r.phone(), r.location(), r.currentTitle(),
                r.totalRelevantExperienceMonths(), r.workAuthorization(), r.sponsorshipRequired(),
                r.noticePeriodDays());
    }
    private static CandidateProfileData emptyProfileData() {
        return new CandidateProfileData(java.util.List.of(), null, 0, CandidateProfileData.Assessment.UNKNOWN,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of());
    }
}

