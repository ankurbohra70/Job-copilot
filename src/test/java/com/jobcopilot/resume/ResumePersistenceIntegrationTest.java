package com.jobcopilot.resume;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @Testcontainers
class ResumePersistenceIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername); r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired ResumePersistenceService service;
    @Autowired ResumeRepository resumes;
    @Autowired CandidateProfileRepository profiles;
    @Test void jsonRoundTripAndLifecyclePreserveUnknownEvidence() {
        String text = "Experience\nBackend Engineer at Acme\n2020 - 2023\n- Java services\nEducation\nUniversity";
        var data = new DeterministicProfileParser().parse(text,LocalDate.of(2026,9,7));
        var saved = service.save("a.pdf",100,text,1,"test",data,LocalDate.of(2026,9,7),"rules-v1","v1");
        var loaded = service.getProfile(saved.candidateProfile().id());
        assertEquals(data,loaded.profile()); assertNotNull(loaded.createdAt()); assertNotNull(saved.createdAt());
        assertEquals(text,service.matchingSnapshot(loaded.id()).extractedText()); assertNull(loaded.totalYearsExperience());
        assertThrows(UnsupportedOperationException.class, () -> loaded.profile().skills().add("invented"));
    }
    @Test void profileFailureRollsBackResumeInsert() {
        long count = resumes.count();
        assertThrows(RuntimeException.class, () -> service.save("a.pdf",10,"text",1,"test",null,LocalDate.now(),"rules-v1","v1"));
        assertEquals(count,resumes.count());
    }
    @Test void deletingResumeCanCascadeToItsProfile() {
        var data = new DeterministicProfileParser().parse("Java",LocalDate.now());
        var saved = service.save("a.pdf",10,"Java",1,"test",data,LocalDate.now(),"rules-v1","v1");
        resumes.deleteById(saved.id());
        assertFalse(profiles.existsById(saved.candidateProfile().id()));
    }
}

