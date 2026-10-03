package com.jobcopilot.resume;

import com.jobcopilot.resume.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @Testcontainers
class CandidateFactPersistenceIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired ResumeService uploads;
    @Autowired ResumePersistenceService persistence;
    @Autowired JdbcTemplate jdbc;
    private static CandidateProfileRequest request(CandidateProfileFacts f) {
        return new CandidateProfileRequest(f.fullName(),f.email(),f.phone(),f.location(),f.currentTitle(),
                f.totalRelevantExperienceMonths(),f.workAuthorization(),f.sponsorshipRequired(),f.noticePeriodDays(),f.professionalLinks());
    }
    private ResumeResponse upload() throws Exception {
        return uploads.upload(new MockMultipartFile("file","synthetic.pdf","application/pdf",CandidateFactsExtractorTest.masterPdf()));
    }
    @Test void populatedDraftRoundTripsAndEvidenceSurvivesAcceptanceCorrectionClearingAndUpdates() throws Exception {
        var upload = upload(); var draft = upload.candidateProfile();
        assertEquals("layout-v2",upload.layoutVersion()); assertEquals("facts-v1",draft.factExtractorVersion());
        assertEquals("rules-v5",draft.parserVersion()); assertEquals("v1",draft.vocabularyVersion());
        assertEquals(CandidateProfileStatus.DRAFT,draft.status()); assertEquals(1,draft.revision());
        assertEquals(draft,persistence.getProfile(draft.id())); assertEquals(upload,persistence.getResume(upload.id()));
        assertEquals(3,draft.facts().professionalLinks().size()); assertEquals("Ada@example.com",draft.facts().email());
        assertThrows(ResumeExceptions.CandidateProfileNotConfirmedException.class,()->persistence.facts(draft.id()));
        assertThrows(ResumeExceptions.CandidateProfileNotConfirmedException.class,()->persistence.matchingSnapshot(draft.id()));
        String evidenceBefore = jdbc.queryForObject("SELECT fact_extraction::text FROM candidate_profiles WHERE id = ?",String.class,draft.id());
        var confirmed = persistence.confirm(draft.id(),new CandidateProfileConfirmationRequest(1,draft.profile(),request(draft.facts())));
        assertEquals(2,confirmed.revision()); assertEquals(CandidateProfileStatus.CONFIRMED,confirmed.status());
        assertEquals(draft.factExtraction(),confirmed.factExtraction());
        assertEquals(2,persistence.confirm(draft.id(),new CandidateProfileConfirmationRequest(2,confirmed.profile(),request(confirmed.facts()))).revision());
        var correctedFacts = new CandidateProfileFacts("Grace Hopper","grace@example.com",null,null,"User title",null,
                CandidateFactState.UNKNOWN,CandidateFactState.UNKNOWN,null,List.of(new ProfessionalLink(ProfessionalLink.Type.WEBSITE,"https://example.org")));
        var corrected = persistence.confirm(draft.id(),new CandidateProfileConfirmationRequest(2,draft.profile(),request(correctedFacts)));
        assertEquals(3,corrected.revision()); assertEquals("User title",corrected.facts().currentTitle());
        assertEquals("Ada@example.com",corrected.factExtraction().suggestions().email());
        assertThrows(ResumeExceptions.CandidateProfileRevisionConflictException.class,()->persistence.confirm(draft.id(),
                new CandidateProfileConfirmationRequest(2,draft.profile(),request(draft.facts()))));
        var clearedRequest = new CandidateProfileRequest(null,null,null,null,null,null,null,null,null);
        var cleared = persistence.confirm(draft.id(),new CandidateProfileConfirmationRequest(3,draft.profile(),clearedRequest));
        assertNull(cleared.facts().email()); assertNull(cleared.facts().currentTitle()); assertTrue(cleared.facts().professionalLinks().isEmpty());
        var updated = persistence.updateProfile(draft.id(),request(correctedFacts));
        assertEquals(draft.factExtraction(),updated.factExtraction());
        assertEquals(evidenceBefore,jdbc.queryForObject("SELECT fact_extraction::text FROM candidate_profiles WHERE id = ?",String.class,draft.id()));
        assertEquals(draft.profile(),updated.profile()); assertDoesNotThrow(()->persistence.facts(draft.id()));
    }
    @Test void failedFactPersistenceRollsBackResumeAndProfileTogether() throws Exception {
        var extraction = new PdfBoxResumeTextExtractor(new ResumeProcessingProperties(5242880,25,200000,1)).extract(CandidateFactsExtractorTest.masterPdf());
        var profile = new DeterministicProfileParser().parse(extraction.text(),CandidateFactsExtractorTest.DAY);
        var invalid = new CandidateProfileFacts("x".repeat(256),null,null,null,null,null,null,null,null);
        var facts = new CandidateFactExtraction(invalid,"facts-v1",new CandidateFactExtractionData("v1",invalid,List.of(),List.of()));
        long resumes = jdbc.queryForObject("SELECT count(*) FROM resumes",Long.class);
        long profiles = jdbc.queryForObject("SELECT count(*) FROM candidate_profiles",Long.class);
        assertThrows(IllegalArgumentException.class,()->persistence.saveDraft("synthetic.pdf",100,extraction,profile,LocalDate.now(),"rules-v5","v1",facts));
        assertEquals(resumes,jdbc.queryForObject("SELECT count(*) FROM resumes",Long.class));
        assertEquals(profiles,jdbc.queryForObject("SELECT count(*) FROM candidate_profiles",Long.class));
    }
    @Test void getAndNewUploadsLeaveAllHistoricalVersionsAndNullMetadataUntouched() throws Exception {
        for (String version : List.of("rules-v1","rules-v2","rules-v3","rules-v4","rules-v5")) {
            var data = new DeterministicProfileParser().parse("Java",CandidateFactsExtractorTest.DAY);
            var old = persistence.save("historical.pdf",100,"Java",1,"pdfbox-3.0.8",data,CandidateFactsExtractorTest.DAY,version,"v1");
            String profileBefore = jdbc.queryForObject("SELECT row_to_json(p)::text FROM candidate_profiles p WHERE id = ?",String.class,old.candidateProfile().id());
            String resumeBefore = jdbc.queryForObject("SELECT row_to_json(r)::text FROM resumes r WHERE id = ?",String.class,old.id());
            upload(); assertEquals(old,persistence.getResume(old.id()));
            assertNull(old.layoutVersion()); assertNull(old.candidateProfile().factExtractorVersion()); assertNull(old.candidateProfile().factExtraction());
            persistence.confirm(old.candidateProfile().id(),new CandidateProfileConfirmationRequest(old.candidateProfile().revision(),data,request(old.candidateProfile().facts())));
            assertEquals(profileBefore,jdbc.queryForObject("SELECT row_to_json(p)::text FROM candidate_profiles p WHERE id = ?",String.class,old.candidateProfile().id()));
            assertEquals(resumeBefore,jdbc.queryForObject("SELECT row_to_json(r)::text FROM resumes r WHERE id = ?",String.class,old.id()));
        }
    }
}
