package com.jobcopilot.resume;

import com.jobcopilot.application.ResumeStrategy;
import com.jobcopilot.resume.ResumeExceptions.ResumeRouteConflictException;
import com.jobcopilot.resume.dto.ResumeRouteRequest;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeRouteServiceTest {
    private final CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final ResumeRouteRepository routes = mock(ResumeRouteRepository.class);
    private final ResumeRouteService service = new ResumeRouteService(profiles, resumes, routes);

    @Test void duplicateRouteConstraintIsTranslatedWithoutLeakingPersistenceDetails() {
        when(profiles.findById(1L)).thenReturn(Optional.of(mock(CandidateProfile.class)));
        Resume resume = mock(Resume.class); when(resume.usableRouteSource()).thenReturn(true);
        when(resumes.findById(2L)).thenReturn(Optional.of(resume));
        ConstraintViolationException constraint = mock(ConstraintViolationException.class);
        when(constraint.getConstraintName()).thenReturn("ux_resume_routes_default");
        when(routes.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("hidden SQL", constraint));

        ResumeRouteConflictException failure = assertThrows(ResumeRouteConflictException.class,
                () -> service.create(1L, new ResumeRouteRequest(2L, ResumeStrategy.VOLUME,
                        null, true, "Default", true)));
        assertFalse(failure.getMessage().contains("SQL"));
    }

    @Test void unrelatedDatabaseFailureIsNotMisclassifiedAsDuplicateRoute() {
        when(profiles.findById(1L)).thenReturn(Optional.of(mock(CandidateProfile.class)));
        Resume resume = mock(Resume.class); when(resume.usableRouteSource()).thenReturn(true);
        when(resumes.findById(2L)).thenReturn(Optional.of(resume));
        DataIntegrityViolationException failure = new DataIntegrityViolationException("unrelated");
        when(routes.saveAndFlush(any())).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.create(1L, new ResumeRouteRequest(2L, ResumeStrategy.VOLUME,
                        null, true, "Default", true))));
    }
}
