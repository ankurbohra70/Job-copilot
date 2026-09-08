package com.jobcopilot.resume;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class DeterministicProfileParserTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "Spring Boot|spring-boot", "Spring Framework|spring", "Java|java", "JavaScript|javascript",
            "C++|c++", "C#|c#", ".NET|.net", "React.js|react", "dotnet|.net", "k8s|kubernetes"})
    void vocabularySkillsHaveNoLostOrPhantomCanonicalValues(String text, String skill) {
        assertEquals(java.util.List.of(skill), parse(text).skills());
        assertEquals(parse(text), parse(text));
    }
    private CandidateProfileData parse(String text) { return new DeterministicProfileParser().parse(text, LocalDate.of(2026,9,7)); }
    @Test void mergesOverlappingEmploymentAndPreservesBullets() {
        var data = parse("Experience\nBackend Engineer at Acme\nJan 2020 - Dec 2021\n- Built Java services\n\nBackend Developer at Beta\nJan 2021 - Dec 2022\n- Used PostgreSQL");
        assertEquals(36, data.totalExperienceMonths());
        assertEquals("Acme", data.workExperience().getFirst().company());
        assertEquals(1, data.workExperience().getFirst().bullets().size());
    }
    @Test void yearOnlyDatesRemainUnknown() {
        var data = parse("Experience\nBackend Engineer at Acme\n2020 - 2023\n- Java");
        assertNull(data.totalExperienceMonths()); assertNull(data.workExperience().getFirst().start().month());
    }
    @Test void excludesEducationDates() {
        var data = parse("Education\nUniversity\nJan 2020 - Dec 2024\nSkills\nJava");
        assertNull(data.totalExperienceMonths()); assertEquals(1, data.education().size());
    }
    @Test void presentUsesAssessmentMonthWithoutFutureCredit() {
        assertEquals(8, parse("Experience\nBackend Engineer at Acme\nJan 2026 - present").totalExperienceMonths());
    }
    @Test void reversedAndFutureDatesRemainUnknown() {
        assertNull(parse("Experience\nJan 2024 - Jan 2020").totalExperienceMonths());
        assertNull(parse("Experience\nJan 2027 - Dec 2028").totalExperienceMonths());
    }
    @Test void springBootDoesNotCreatePhantomSpringFrameworkSkill() {
        assertEquals(java.util.List.of("spring-boot"), parse("Spring Boot").skills());
        assertEquals(java.util.List.of("spring"), parse("Spring Framework").skills());
        assertEquals(java.util.List.of("spring", "spring-boot"), parse("Spring Framework and Spring Boot").skills());
    }

    @Test void preservesUnknownAndUnparsedSource() {
        var data = parse("Experience\nAcme, five years of experience\n- Made things\nProjects\nA tool\n- Java");
        assertNull(data.totalExperienceMonths()); assertNull(data.workExperience().getFirst().company());
        assertEquals("A tool", data.projects().getFirst().title()); assertFalse(data.warnings().isEmpty());
    }
}
