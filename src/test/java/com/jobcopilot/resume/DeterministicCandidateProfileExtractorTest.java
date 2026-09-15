package com.jobcopilot.resume;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicCandidateProfileExtractorTest {
    @Test void goldenExtractionMatchesReleasedParserExactly() {
        String text = """
                Skills
                Java, Spring Boot, PostgreSQL
                Experience
                Backend Engineer at Acme
                Jan 2020 - Dec 2023
                - Built REST services
                """;
        LocalDate assessedOn = LocalDate.of(2026, 9, 15);
        DeterministicProfileParser parser = new DeterministicProfileParser();
        CandidateProfileExtraction result =
                new DeterministicCandidateProfileExtractor(parser).extract(text, assessedOn);

        assertEquals(parser.parse(text, assessedOn), result.profile());
        assertEquals(DeterministicProfileParser.VERSION, result.extractorVersion());
        assertEquals("v1", result.vocabularyVersion());
    }

    @Test void resumeServiceDependsOnTheExtractorAbstraction() throws Exception {
        assertEquals(CandidateProfileExtractor.class,
                ResumeService.class.getDeclaredField("profileExtractor").getType());
    }
}
