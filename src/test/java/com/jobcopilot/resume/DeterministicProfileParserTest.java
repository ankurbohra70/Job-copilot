package com.jobcopilot.resume;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class DeterministicProfileParserTest {
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
    @Test void preservesUnknownAndUnparsedSource() {
        var data = parse("Experience\nAcme, five years of experience\n- Made things\nProjects\nA tool\n- Java");
        assertNull(data.totalExperienceMonths()); assertNull(data.workExperience().getFirst().company());
        assertEquals("A tool", data.projects().getFirst().title()); assertFalse(data.warnings().isEmpty());
    }
}

