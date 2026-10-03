package com.jobcopilot.resume;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static com.jobcopilot.resume.CandidateProfileData.Assessment.KNOWN;
import static org.junit.jupiter.api.Assertions.*;

class DeterministicProfileParserRegressionTest {
    private static final LocalDate ASSESSED_ON = LocalDate.of(2026, 10, 3);
    private final DeterministicProfileParser parser = new DeterministicProfileParser();

    @Test
    void segmentsAndParsesModernInlineLayout() throws IOException {
        CandidateProfileData data = parseFixture("modern-inline.txt");

        assertModernWork(data);
        assertEquals("National Institute of Technology, Tiruchirappalli", data.education().get(0).institution());
        assertEquals("Master of Computer Applications", data.education().get(0).qualification());
        assertEquals("University of Delhi, Deen Dayal Upadhyaya College", data.education().get(1).institution());
        assertEquals("B.Sc. (Hons.) Computer Science", data.education().get(1).qualification());
        assertEquals(2021, data.education().get(0).start().year());
        assertEquals(2024, data.education().get(0).end().year());
        assertEducationIsolation(data);
        assertProjectIsolation(data, "Job Copilot - AI Job Intelligence Platform", "Raahi - Full-Stack Ride Booking Platform",
                List.of("aws", "java", "postgresql", "spring-boot"), List.of("react", "redis"));
        assertFalse(data.projects().get(0).title().contains("GitHub"));
    }

    @Test
    void segmentsAndParsesModernMultilineLayout() throws IOException {
        CandidateProfileData data = parseFixture("modern-multiline.txt");

        assertModernWork(data);
        assertEquals(2, data.education().size());
        assertEquals("National Institute of Technology, Tiruchirappalli", data.education().get(0).institution());
        assertEquals("Master of Computer Applications (MCA)", data.education().get(0).qualification());
        assertEquals("Deen Dayal Upadhyaya College, University of Delhi", data.education().get(1).institution());
        assertEquals("B.Sc (Hons) Computer Science", data.education().get(1).qualification());
        assertEducationIsolation(data);
        assertProjectIsolation(data, "Raahi App", "Personalized Travel Recommender",
                List.of("postgresql", "react", "spring-boot"), List.of("aws", "java", "postgresql", "redis"));
    }

    @Test
    void parsesLegacyTitleCompanyWithoutPromotingProse() throws IOException {
        CandidateProfileData data = parseFixture("legacy-title-company.txt");

        assertEquals(1, data.workExperience().size());
        var work = data.workExperience().getFirst();
        assertEquals("Software Development Engineer Intern", work.title());
        assertEquals("Mobikwik", work.company());
        assertNotEquals("on backend development, cloud integration, and real-time communication.", work.title());
        assertTrue(work.ongoing());
        assertEquals(KNOWN, data.experienceAssessment());
        assertEquals(15, data.totalExperienceMonths());
        assertEquals(2, data.education().size());
        assertProjectIsolation(data, "Raahi App", "Personalized Travel Recommender",
                List.of("postgresql", "spring-boot"), List.of("aws", "java", "redis"));
    }

    @Test
    void fixtureExtractionIsDeterministicAndPreservesGlobalSkills() throws IOException {
        String text = fixture("modern-inline.txt");
        CandidateProfileData first = parser.parse(text, ASSESSED_ON);
        CandidateProfileData second = parser.parse(text, ASSESSED_ON);

        assertEquals(first, second);
        assertEquals(List.of("aws", "java", "postgresql", "react", "redis", "spring-boot"), first.skills());
        assertEquals("rules-v5", DeterministicProfileParser.VERSION);
    }

    private void assertModernWork(CandidateProfileData data) {
        assertEquals(2, data.workExperience().size());
        assertEquals("MobiKwik", data.workExperience().get(0).company());
        assertEquals("Software Development Engineer I", data.workExperience().get(0).title());
        assertDate(data.workExperience().get(0).start(), 2026, 2);
        assertDate(data.workExperience().get(0).end(), 2026, 7);
        assertEquals("MobiKwik", data.workExperience().get(1).company());
        assertEquals("Software Development Engineer Intern", data.workExperience().get(1).title());
        assertDate(data.workExperience().get(1).start(), 2025, 7);
        assertDate(data.workExperience().get(1).end(), 2026, 1);
        assertFalse(data.workExperience().get(0).sourceText().contains("merchant services"));
        assertFalse(data.workExperience().get(1).sourceText().contains("payment APIs"));
    }

    private static void assertProjectIsolation(CandidateProfileData data, String firstTitle, String secondTitle,
            List<String> firstTechnologies, List<String> secondTechnologies) {
        assertEquals(2, data.projects().size());
        assertEquals(firstTitle, data.projects().get(0).title());
        assertEquals(secondTitle, data.projects().get(1).title());
        assertEquals(firstTechnologies, data.projects().get(0).technologies());
        assertEquals(secondTechnologies, data.projects().get(1).technologies());
        assertFalse(data.projects().get(0).sourceText().contains(secondTitle));
        assertFalse(data.projects().get(1).sourceText().contains(firstTitle));
    }

    private static void assertEducationIsolation(CandidateProfileData data) {
        assertFalse(data.education().get(0).sourceText().contains(data.education().get(1).institution()));
        assertFalse(data.education().get(1).sourceText().contains(data.education().get(0).institution()));
    }

    private static void assertDate(CandidateProfileData.PartialDate date, int year, int month) {
        assertNotNull(date);
        assertEquals(year, date.year());
        assertEquals(month, date.month());
    }

    private CandidateProfileData parseFixture(String name) throws IOException {
        return parser.parse(fixture(name), ASSESSED_ON);
    }

    private static String fixture(String name) throws IOException {
        try (var input = DeterministicProfileParserRegressionTest.class.getResourceAsStream(
                "/resume/deterministic/" + name)) {
            assertNotNull(input, "Missing fixture " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
