package com.jobcopilot.resume;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static com.jobcopilot.resume.CandidateProfileData.*;
import static org.junit.jupiter.api.Assertions.*;

class DeterministicProfileParserV3Test {
    private static final LocalDate ASSESSED_ON = LocalDate.of(2026, 10, 3);
    private final DeterministicProfileParser parser = new DeterministicProfileParser();

    @Test
    void amazonPdfExtractionHasTwoProjectsAndFiveCompleteBullets() throws Exception {
        CandidateProfileData data = realFixture("Ankur_Bohra_Amazon", List.of(
                "Job Copilot - AI Job Intelligence Platform", "Raahi - Full-Stack Ride Booking Platform"),
                List.of(3, 2), List.of(List.of("docker", "java", "postgresql", "spring-boot"),
                        List.of("aws", "java", "javascript", "postgresql", "react", "spring-boot")), false);
        assertEquals(List.of(
                "• Built production-style Spring Boot workflows for job ingestion, structured extraction, matching, ranking, and external ATS synchronization, with explicit validation and deterministic failure handling across service boundaries.",
                "• Built and repeatedly ran a 900+ test automated suite spanning unit, integration, regression, parity/golden, malformed-input, provider-failure, ordering, concurrency, idempotency, and recovery scenarios to detect regressions before milestone freezes.",
                "• Engineered correctness controls including transactional updates, optimistic revisioning, run fencing, leases/heartbeats, startup recovery, and exactly-once model invocation; used independent review and adversarial QA to validate changes before acceptance."),
                data.projects().get(0).bullets());
        assertEquals(List.of(
                "• Built Spring Boot REST services and a React Native/JavaScript client with PostgreSQL, JWT authentication, role-based authorization, and transactional ride/wallet workflows.",
                "• Improved ride batching by 20% and reduced manual payment handling by 40% through batching and wallet auto-pay workflows."),
                data.projects().get(1).bullets());
        assertEducation(data, "University of Delhi, Deen Dayal Upadhyaya College",
                "Master of Computer Applications", "B.Sc. (Hons.) Computer Science");
    }

    @Test
    void backendGenAiPdfExtractionHasTwoProjectsAndSixCompleteBullets() throws Exception {
        CandidateProfileData data = realFixture("Ankur_Bohra_Backend_GenAI", List.of(
                "Job Copilot - AI Job Discovery, Intelligence & Matching Platform", "Raahi - Ride Booking Backend"),
                List.of(4, 2), List.of(List.of("docker", "java", "postgresql", "redis", "spring-boot"),
                        List.of("aws", "docker", "java", "postgresql", "spring-boot")), false);
        assertEquals(List.of(
                "- Building an AI-assisted platform for job discovery, structured requirement extraction, resume ingestion, authoritative candidate profiles, deterministic fit assessment, and opportunity ranking through Spring Boot APIs and PostgreSQL-backed workflows.",
                "- Designed a provider-neutral LLM pipeline with structured decoding, source-evidence grounding, deterministic Java validation, and exactly-once model execution with no hidden retry/repair behavior; validated the integration against a real LLM provider.",
                "- Built versioned human-labelled golden cases and evaluation harnesses for candidate extraction, job-requirement extraction, matching/ranking, and decision readiness, enabling deterministic regression and parity validation of AI-assisted workflows.",
                "- Built authoritative ATS ingestion and reconciliation with database-enforced invariants, concurrency controls, idempotency, leases/heartbeats, failure recovery, scheduled synchronization, and PostgreSQL integration testing."),
                data.projects().get(0).bullets());
        assertEquals(List.of(
                "- Built modular Spring Boot REST APIs with JWT authentication, role-based authorization, and transactional ride/wallet workflows.",
                "- Implemented ride batching and wallet auto-pay, improving batching efficiency by 20% and reducing manual payment handling by 40%."),
                data.projects().get(1).bullets());
        assertEducation(data, "University of Delhi, DDUC", "Master of Computer Applications",
                "B.Sc. (Hons.) Computer Science");
    }

    @Test
    void legacySdePdfExtractionHasTwoProjectsAndSixCompleteEnDashBullets() throws Exception {
        CandidateProfileData data = realFixture("Ankur_Bohra_SDE", List.of(
                "Raahi App", "Personalized Travel Recommender"), List.of(3, 3),
                List.of(List.of("postgresql", "react", "spring-boot"),
                        List.of("aws", "java", "postgresql", "redis", "spring-boot")), true);
        assertEquals(List.of(
                "– Engineered a ride-sharing platform with multi-ride scheduling, handling 5,000+ rides monthly and boosting cab utilization efficiency.",
                "– Designed and implemented an n-batching algorithm that optimized real-time ride grouping, improving booking efficiency by 20% and reducing passenger costs.",
                "– Built and integrated a secure e-wallet system with automated invoicing and payments, cutting manual transactions by 40% and enhancing user trust."),
                data.projects().get(0).bullets());
        assertEquals(List.of(
                "– Architected and developed a full-stack platform offering personalized destination, itinerary, and accommodation recommen- dations based on user profiles.",
                "– Designed, built, and deployed RESTful APIs in Spring Boot for user management, bookings, and reviews, integrating with external Weather, Maps, and Booking APIs.",
                "– Optimized backend performance with Redis caching and AWS auto-scaling, reducing response times by 40% and im- proving reliability under high traffic."),
                data.projects().get(1).bullets());
        assertEducation(data, "Deen Dayal Upadhyaya College, University of Delhi",
                "Master of Computer Applications (MCA)", "B.Sc (Hons) Computer Science");
    }

    private CandidateProfileData realFixture(String name, List<String> titles, List<Integer> counts,
            List<List<String>> technologies, boolean legacy) throws Exception {
        String text;
        try (var input = getClass().getResourceAsStream("/resume/deterministic/" + name + ".txt")) {
            assertNotNull(input);
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var input = getClass().getResourceAsStream("/resume/deterministic/" + name + ".pdf")) {
            assertNotNull(input);
            var pdfExtraction = new PdfBoxResumeTextExtractor(
                    new ResumeProcessingProperties(5_242_880, 25, 200_000, 50)).extract(input.readAllBytes());
            assertEquals(text.replace("\r", ""), pdfExtraction.text().replace("\r", ""));
            text = pdfExtraction.text();
        }
        var extraction = new DeterministicCandidateProfileExtractor(parser).extract(text, ASSESSED_ON);
        CandidateProfileData data = extraction.profile();
        assertEquals("rules-v5", extraction.extractorVersion());
        assertEquals("v1", extraction.vocabularyVersion());
        assertEquals(data, parser.parse(text, ASSESSED_ON));
        assertEquals(2, data.projects().size());
        assertEquals(titles, data.projects().stream().map(Project::title).toList());
        assertEquals(counts, data.projects().stream().map(p -> p.bullets().size()).toList());
        assertEquals(technologies, data.projects().stream().map(Project::technologies).toList());
        String normalizedText = text.replace("\r", "");
        for (int i = 0; i < 2; i++) {
            Project project = data.projects().get(i);
            assertTrue(normalizedText.contains(project.sourceText()));
            assertFalse(project.sourceText().toUpperCase().contains("SKILLS"));
            assertFalse(project.sourceText().contains(titles.get(1 - i)));
            assertTrue(project.sourceText().contains("\n"));
            assertTrue(project.bullets().stream().noneMatch(b -> b.contains("\n")));
        }
        assertEquals(List.of(), data.roleCategories());
        assertEquals(Assessment.KNOWN, data.experienceAssessment());
        assertEquals(legacy ? 15 : 13, data.totalExperienceMonths());
        assertEquals(data.totalExperienceMonths(), data.observedExperienceMonths());
        assertTrue(data.warnings().isEmpty());
        assertEquals(legacy ? 1 : 2, data.workExperience().size());
        WorkExperience intern = data.workExperience().get(legacy ? 0 : 1);
        assertEquals("Software Development Engineer Intern", intern.title());
        assertEquals(legacy ? "Mobikwik" : "MobiKwik", intern.company());
        assertEquals(month(2025, 7), intern.start());
        assertEquals(legacy, intern.ongoing());
        assertEquals(legacy ? null : month(2026, 1), intern.end());
        if (!legacy) {
            WorkExperience engineer = data.workExperience().getFirst();
            assertEquals("Software Development Engineer I", engineer.title());
            assertEquals("MobiKwik", engineer.company());
            assertEquals(month(2026, 2), engineer.start());
            assertEquals(month(2026, 7), engineer.end());
            assertFalse(engineer.ongoing());
        }
        data.workExperience().forEach(w -> {
            assertTrue(normalizedText.contains(w.sourceText()));
            assertFalse(w.sourceText().toUpperCase().contains("PROJECTS"));
        });
        data.education().forEach(e -> {
            assertTrue(normalizedText.contains(e.sourceText()));
            assertFalse(e.sourceText().toUpperCase().contains("ACHIEVEMENTS"));
        });
        List<String> expectedSkills = legacy
                ? List.of("aws", "c++", "docker", "git", "java", "javascript", "kubernetes", "postgresql", "python", "react", "redis", "spring-boot", "sql")
                : name.endsWith("Amazon")
                    ? List.of("aws", "c++", "docker", "git", "java", "javascript", "maven", "postgresql", "python", "react", "redis", "spring", "spring-boot", "sql")
                    : List.of("aws", "c++", "docker", "git", "java", "javascript", "maven", "postgresql", "python", "redis", "spring", "spring-boot", "sql");
        assertEquals(expectedSkills, data.skills());
        // Exercise the upload validator, including real folded bullets exceeding 255 characters.
        assertEquals(data, new CandidateProfileValidator().canonicalize(data));
        return data;
    }

    private static void assertEducation(CandidateProfileData data, String secondInstitution,
            String firstQualification, String secondQualification) {
        assertEquals(2, data.education().size());
        assertEquals(List.of("National Institute of Technology, Tiruchirappalli", secondInstitution),
                data.education().stream().map(Education::institution).toList());
        assertEquals(List.of(firstQualification, secondQualification),
                data.education().stream().map(Education::qualification).toList());
        assertEquals(List.of(year(2021), year(2018)), data.education().stream().map(Education::start).toList());
        assertEquals(List.of(year(2024), year(2021)), data.education().stream().map(Education::end).toList());
    }

    private static PartialDate month(int year, int month) {
        return new PartialDate(year, month, DatePrecision.MONTH);
    }

    private static PartialDate year(int year) {
        return new PartialDate(year, null, DatePrecision.YEAR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"•", "-", "–", "*"})
    void foldsGlyphVariantsWithoutLosingProvenanceOrSplittingProse(String marker) {
        String source = "Alpha | GitHub\nJava | PostgreSQL\n" + marker + " Built services\n"
                + "lowercase continuation\n\nUppercase continuation\n40%.\n"
                + "Java, PostgreSQL, and Redis powered the workflows\nread more at this link\n"
                + marker + " Shipped changes\nBeta | Redis, AWS Link\n" + marker + " Deployed APIs";
        var data = parser.parse("Projects:\n" + source + "\nSkills:\nC++", ASSESSED_ON);
        assertEquals(List.of("Alpha", "Beta"), data.projects().stream().map(Project::title).toList());
        assertEquals(List.of(marker + " Built services lowercase continuation Uppercase continuation 40%. Java, PostgreSQL, and Redis powered the workflows read more at this link",
                marker + " Shipped changes"), data.projects().getFirst().bullets());
        assertEquals(List.of(marker + " Deployed APIs"), data.projects().get(1).bullets());
        assertEquals(List.of("java", "postgresql", "redis"), data.projects().getFirst().technologies());
        assertEquals(List.of("aws", "redis"), data.projects().get(1).technologies());
        assertEquals(source.substring(0, source.indexOf("Beta")).trim(), data.projects().getFirst().sourceText());
        assertEquals(source.substring(source.indexOf("Beta")), data.projects().get(1).sourceText());
    }

    @Test
    void technologyMetadataClosesBulletAndHeaderlessProjectsRequirePositiveEvidence() {
        var data = parser.parse("""
                Projects
                Alpha
                Java, PostgreSQL
                - Built a service
                Technologies: Redis, AWS

                Beta
                Python | Docker
                - Built another service

                Gamma
                - Shipped a tool
                Skills
                C++
                """, ASSESSED_ON);
        assertEquals(List.of("Alpha", "Beta", "Gamma"), data.projects().stream().map(Project::title).toList());
        assertEquals(List.of("- Built a service"), data.projects().getFirst().bullets());
        assertEquals(List.of("aws", "java", "postgresql", "redis"), data.projects().getFirst().technologies());
        assertEquals(List.of("docker", "python"), data.projects().get(1).technologies());
        assertEquals(List.of(), data.projects().get(2).technologies());
    }

    @Test
    void navigationLinkHeaderIsDistinctFromCapitalizedContinuationAfterBlankLine() {
        var data = parser.parse("Projects\nAlpha Link\n- Built services\n\nUppercase continuation\n"
                + "- Next bullet\nRead more at this link\nBeta Link\n– Used\tRedis\n"
                + "with\u00a0AWS\nSkills\nJava", ASSESSED_ON);
        assertEquals(List.of("Alpha", "Beta"), data.projects().stream().map(Project::title).toList());
        assertEquals(List.of("- Built services Uppercase continuation", "- Next bullet Read more at this link"),
                data.projects().getFirst().bullets());
        assertEquals(List.of("– Used Redis with AWS"), data.projects().get(1).bullets());
        assertTrue(data.projects().get(1).sourceText().contains("Used\tRedis\nwith\u00a0AWS"));
    }

    @Test
    void repeatedSectionsAndHeadingVariantsKeepEachEntryWithinItsOwnOccurrence() {
        var data = parser.parse("""
                wOrK eXpErIeNcE :
                Backend Engineer at Acme
                Jan 2025 - Jan 2025
                - Java
                PrOjEcTs:
                Alpha | GitHub
                - Python
                Academic Background:
                University One - Degree One | 2018 - 2021
                aChIeVeMeNtS :
                Won a contest in 2022 - 2023
                Employment History:
                Frontend Developer at Beta
                Feb 2025 - Feb 2025
                - React
                Personal Projects:
                Beta | GitHub
                - Docker
                Education:
                University Two - Degree Two | 2021 - 2024
                Leadership & Achievements:
                Led a club
                Technical Skills:
                Redis
                """, ASSESSED_ON);
        assertEquals(List.of("Backend Engineer", "Frontend Developer"), data.workExperience().stream().map(WorkExperience::title).toList());
        assertEquals(List.of("Alpha", "Beta"), data.projects().stream().map(Project::title).toList());
        assertEquals(List.of("University One", "University Two"), data.education().stream().map(Education::institution).toList());
        assertEquals("Backend Engineer at Acme\nJan 2025 - Jan 2025\n- Java", data.workExperience().getFirst().sourceText());
        assertEquals("Alpha | GitHub\n- Python", data.projects().getFirst().sourceText());
        assertEquals("University One - Degree One | 2018 - 2021", data.education().getFirst().sourceText());
        assertEquals("University Two - Degree Two | 2021 - 2024", data.education().get(1).sourceText());
        assertEquals(List.of("backend", "frontend"), data.roleCategories());
    }

    @Test
    void rolesComeOnlyFromTitlesAndFreshExtractionDoesNotChangeOlderProfileVersions() {
        String generic = "Experience\nSoftware Development Engineer Intern July 2025 - Present\nMobikwik\n- backend development\nProjects\nBackend Engineer Tool | GitHub\n- Java";
        CandidateProfileData data = parser.parse(generic, ASSESSED_ON);
        assertEquals(List.of(), data.roleCategories());
        assertEquals(List.of("backend"), parser.parse(generic.replace("Software Development Engineer Intern", "Backend Engineer"), ASSESSED_ON).roleCategories());
        CandidateProfile v1 = new CandidateProfile(null, data, ASSESSED_ON, "rules-v1", "v1");
        CandidateProfile v2 = new CandidateProfile(null, data, ASSESSED_ON, "rules-v2", "v1");
        var fresh = new DeterministicCandidateProfileExtractor(parser).extract(generic, ASSESSED_ON);
        assertEquals("rules-v5", fresh.extractorVersion());
        assertEquals("rules-v1", v1.parserVersion());
        assertEquals("rules-v2", v2.parserVersion());
        assertEquals(data, v1.data());
        assertEquals(data, v2.data());
    }
}
