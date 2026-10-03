package com.jobcopilot.resume;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicProfileParserV4Test {
    private static final LocalDate ASSESSED_ON = LocalDate.of(2026, 10, 3);
    private final DeterministicProfileParser parser = new DeterministicProfileParser();

    @ParameterizedTest
    @ValueSource(strings = {"Ankur_Bohra_Amazon", "Ankur_Bohra_Backend_GenAI", "Ankur_Bohra_SDE"})
    void realFixturesContainCompleteLogicalWorkBullets(String name) throws Exception {
        String text;
        try (var input = getClass().getResourceAsStream("/resume/deterministic/" + name + ".txt")) {
            assertNotNull(input);
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var input = getClass().getResourceAsStream("/resume/deterministic/" + name + ".pdf")) {
            assertNotNull(input);
            var pdf = new PdfBoxResumeTextExtractor(new ResumeProcessingProperties(5_242_880, 25, 200_000, 50))
                    .extract(input.readAllBytes());
            assertEquals(text.replace("\r", ""), pdf.text().replace("\r", ""));
            text = pdf.text();
        }
        var extraction = new DeterministicCandidateProfileExtractor(parser).extract(text, ASSESSED_ON);
        var data = extraction.profile();
        assertEquals("rules-v5", extraction.extractorVersion());
        List<List<String>> expected = expectedWork(name);
        assertEquals(expected.size(), data.workExperience().size());
        assertEquals(expected, data.workExperience().stream().map(CandidateProfileData.WorkExperience::bullets).toList());
        assertEquals(data, parser.parse(text, ASSESSED_ON));
        assertEquals(data, new CandidateProfileValidator().canonicalize(data));
        for (var work : data.workExperience()) {
            assertTrue(text.replace("\r", "").contains(work.sourceText()));
            assertFalse(work.sourceText().contains("PROJECTS"));
        }
        if (name.endsWith("SDE")) {
            assertTrue(data.workExperience().getFirst().sourceText().contains("re-\nduction"));
            assertTrue(data.workExperience().getFirst().bullets().get(2).contains("re- duction"));
        }
    }

    private static List<List<String>> expectedWork(String name) {
        return switch (name) {
            case "Ankur_Bohra_Amazon" -> List.of(List.of(
                    "• Owned end-to-end design, implementation, testing, and production delivery of a greenfield Java, Spring Boot, MySQL Field Service Management platform, shipped in 2 months for 1,200+ agents processing 5,000+ tasks/day across merchant operations.",
                    "• Built maintainable REST APIs, transactional task workflows, deterministic assignment/state logic, and SQL aggregation APIs; also delivered backend workflows supporting merchant lending journeys and operational dashboards.",
                    "• Troubleshot the platform's highest-latency production path with Grafana, identified synchronous database/audit work on the request path, and moved it to Spring @Async, reducing peak API latency from ~7s to <150ms.",
                    "• Shipped 30+ post-launch reliability and performance improvements driven by monitoring and user pain points, including SQL/query optimization, composite indexing, pagination, Redis caching, aggregation queries, and defect resolution.",
                    "• Instrumented services with Spring Boot Actuator, Micrometer, and Grafana; worked with product, frontend, business, and operations teams on requirements, API contracts, implementation trade-offs, releases, code review, testing, and post-release validation."), List.of(
                    "• Developed and debugged Merchant Panel APIs for onboarding, dynamic forms, dashboards, and document workflows using Java, Spring Boot, PostgreSQL, and MongoDB, supporting merchant-facing production workflows.",
                    "• Built a merchant Soundbox transaction ACK pipeline using AWS IoT/MQTT and Amazon SQS with asynchronous processing, database-level idempotency, retry/DLQ handling, and tuned failure processing; improved ACK speed by approximately 2x.",
                    "• Consumed production Kafka payment events and called an internal notification service to send merchant-app transaction alerts; used retry with backoff and routed terminal failures to a DLQ.",
                    "• Extended Soundbox localization from 2 to 5 languages, improving usability of the merchant payment-device experience."));
            case "Ankur_Bohra_Backend_GenAI" -> List.of(List.of(
                    "- Owned end-to-end design and delivery of a greenfield Field Service Management platform using Java, Spring Boot, MySQL, Redis, and REST APIs; shipped from zero to production in 2 months.",
                    "- Designed concurrency-safe transactional task creation and assignment for 1,200+ agents processing 5,000+ tasks/day, with non-terminal uniqueness, per-agent capacity controls, explicit lifecycle transitions, and deterministic fallback handling.",
                    "- Diagnosed a critical production bottleneck with Grafana and moved suitable DB writes and audit work off the request path using Spring @Async, cutting peak API latency from ~7s to <150ms.",
                    "- Shipped 30+ performance and reliability improvements across Redis caching, composite indexing, query optimization, pagination, aggregation queries, in-memory processing, production debugging, and operational issue resolution.",
                    "- Consumed production payment events through Kafka and integrated with an internal notification API to trigger merchant push notifications, implementing retry with backoff and DLQ handling for terminal failures.",
                    "- Instrumented services with Spring Boot Actuator, Micrometer, and Grafana; built REST APIs and aggregation workflows for operations dashboards and merchant-lending journeys."), List.of(
                    "- Developed production backend APIs for Merchant Panel onboarding, dynamic forms, dashboards, and document workflows using Java, Spring Boot, PostgreSQL, and MongoDB.",
                    "- Built an event-driven Soundbox ACK pipeline using AWS IoT/MQTT and Amazon SQS with idempotent processing and retry/DLQ handling; improved ACK processing to ~2x faster and expanded localization from 2 to 5 languages."));
            case "Ankur_Bohra_SDE" -> List.of(List.of(
                    "– Engineered and deployed 5 feature-rich tabs on the MerchantPanel dashboard, reducing merchant data operation time by 30% and improving efficiency for over 10,000 active merchants.",
                    "– Diagnosed and resolved a critical latency bottleneck in the soundbox transactional system, achieving 2x faster response times and ensuring more reliable payments.",
                    "– Integrated features across MerchantPanel, MerchantBackend, MerchantDevices repositories, resulting in a 25% re- duction in bug reports during production releases.",
                    "– Worked with Java (multithreading, exception handling), Spring Boot, AWS IoT, and MQTT protocol, focusing on backend development, cloud integration, and real-time communication."));
            default -> throw new IllegalArgumentException(name);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"•", "-", "–", "*"})
    void foldsEveryMarkerWithinBoundedEntriesWithoutProjectHeuristics(String marker) {
        String first = "Software Development Engineer Intern July 2025 - Present\nMobikwik\n"
                + "Unmarked preamble\n" + marker + " Built\tservices\n"
                + "lowercase continuation\n\nUppercase continuation\n30% improvement\n123 merchants\n"
                + "Java | Spring Boot | PostgreSQL\nBackend Engineer\nExample | GitHub\n"
                + "with\u00a0AWS\n" + marker + " Improved re-\nduction in defects and event-\ndriven delivery.";
        String second = "Software Development Engineer I at Beta\nJan 2024 - Feb 2024\n"
                + marker + " Shipped another service\nwith reliable recovery.";
        var data = parser.parse("Experience\n" + first + "\n" + second
                + "\nProjects\nTool | GitHub\n- Python", ASSESSED_ON);
        assertEquals(2, data.workExperience().size());
        assertEquals(List.of(marker + " Built services lowercase continuation Uppercase continuation 30% improvement 123 merchants Java | Spring Boot | PostgreSQL Backend Engineer Example | GitHub with AWS",
                marker + " Improved re- duction in defects and event- driven delivery."), data.workExperience().getFirst().bullets());
        assertEquals(List.of(marker + " Shipped another service with reliable recovery."), data.workExperience().get(1).bullets());
        assertEquals(first, data.workExperience().getFirst().sourceText());
        assertEquals(second, data.workExperience().get(1).sourceText());
        assertEquals("Software Development Engineer Intern", data.workExperience().getFirst().title());
        assertEquals("Mobikwik", data.workExperience().getFirst().company());
        assertEquals(List.of(), data.roleCategories());
        assertEquals(List.of("Tool"), data.projects().stream().map(CandidateProfileData.Project::title).toList());
    }

    @Test
    void bulletlessEntriesRemainBulletlessAndEndOfInputFlushesFinalBullet() {
        String noBullet = "Backend Engineer at Acme\nJan 2020 - Dec 2020\nUnmarked description";
        String withBullet = "Backend Developer at Beta\nJan 2021 - Dec 2021\n- Built APIs\nwith Java";
        var data = parser.parse("Experience\n" + noBullet + "\n" + withBullet, ASSESSED_ON);
        assertEquals(List.of(), data.workExperience().getFirst().bullets());
        assertEquals(noBullet, data.workExperience().getFirst().sourceText());
        assertEquals(List.of("- Built APIs with Java"), data.workExperience().get(1).bullets());
        assertEquals(withBullet, data.workExperience().get(1).sourceText());
    }

    @Test
    void identicalLogicalBulletsRetainExistingValidatorDeduplicationBehavior() {
        var data = parser.parse("Experience\nBackend Engineer at Acme\nJan 2020 - Dec 2020\n"
                + "- Built Java\nservices\n- Built Java services", ASSESSED_ON);
        assertEquals(List.of("- Built Java services", "- Built Java services"), data.workExperience().getFirst().bullets());
        assertEquals(List.of("- Built Java services"), new CandidateProfileValidator().canonicalize(data)
                .workExperience().getFirst().bullets());
    }
}
