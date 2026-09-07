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
    @Transactional(readOnly = true)
    public CandidateMatchingSnapshot matchingSnapshot(Long id) {
        CandidateProfile p = findProfile(id);
        return new CandidateMatchingSnapshot(p.id(), p.data(), p.resume().text(), p.parserVersion(), p.vocabularyVersion(), p.assessedOn());
    }
    private CandidateProfile findProfile(Long id) { return profiles.findById(id).orElseThrow(() -> new CandidateProfileNotFoundException(id)); }
    private static CandidateProfileResponse profileResponse(CandidateProfile p) {
        return new CandidateProfileResponse(p.id(), p.resume().id(), p.data(), p.schemaVersion(), p.parserVersion(),
                p.vocabularyVersion(), p.assessedOn(), p.createdAt());
    }
    private static ResumeResponse response(Resume r, CandidateProfile p) {
        return new ResumeResponse(r.id(), r.fileName(), r.sizeBytes(), r.pageCount(), r.extractorVersion(), r.createdAt(), profileResponse(p));
    }
}

