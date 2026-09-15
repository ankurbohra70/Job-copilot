package com.jobcopilot.resume;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandidateProfileValidatorTest {
    private final CandidateProfileValidator validator = new CandidateProfileValidator();

    @Test void canonicalizesFullStructuredProfileWithoutChangingEvidenceOrder() {
        CandidateProfileData input = new CandidateProfileData(List.of(" Java ", "java"), 12, 12,
                CandidateProfileData.Assessment.KNOWN,
                List.of(new CandidateProfileData.WorkExperience(" Engineer ", " Acme ",
                        new CandidateProfileData.PartialDate(2024, 1, CandidateProfileData.DatePrecision.MONTH),
                        null, true, List.of(" second ", " first "), " source ")),
                List.of(), List.of(), List.of(" APIs ", "apis"), List.of(" Engineer "),
                List.of(new CandidateProfileData.Evidence(" SKILL ", " Java ", " proof ")), List.of(" warning "));

        CandidateProfileData result = validator.canonicalize(input);

        assertEquals(List.of("java"), result.skills());
        assertEquals(List.of("apis"), result.keywords());
        assertEquals(List.of("engineer"), result.roleCategories());
        assertEquals(List.of("second", "first"), result.workExperience().getFirst().bullets());
        assertEquals("source", result.workExperience().getFirst().sourceText());
        assertEquals(List.of("warning"), result.warnings());
    }

    @Test void rejectsInvalidDatesAndBounds() {
        CandidateProfileData invalid = new CandidateProfileData(List.of(), null, 0,
                CandidateProfileData.Assessment.UNKNOWN,
                List.of(new CandidateProfileData.WorkExperience("Engineer", "Acme",
                        new CandidateProfileData.PartialDate(2025, 2, CandidateProfileData.DatePrecision.MONTH),
                        new CandidateProfileData.PartialDate(2024, 1, CandidateProfileData.DatePrecision.MONTH),
                        false, List.of(), "source")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> validator.canonicalize(invalid));
    }

    @Test void lifecycleHasDraftAndConfirmedNoOpSemantics() {
        CandidateProfileData data = new DeterministicProfileParser().parse("Java", LocalDate.now());
        CandidateProfile resumeBacked = new CandidateProfile(new Resume("a.pdf", 10, "Java", 1, "pdfbox-v1"),
                data, LocalDate.now(), "rules-v1", "v1");
        CandidateProfileFacts facts = new CandidateProfileFacts("Candidate", null, null, null, null,
                null, null, null, null);
        assertEquals(CandidateProfileStatus.DRAFT, resumeBacked.status());
        assertTrue(resumeBacked.confirmOrReplace(data, facts));
        assertEquals(CandidateProfileStatus.CONFIRMED, resumeBacked.status());
        assertFalse(resumeBacked.confirmOrReplace(data, facts));
        assertEquals(CandidateProfileStatus.CONFIRMED,
                new CandidateProfile(data, LocalDate.now()).status());
    }

    @Test void canonicalizationMakesSemanticallyEquivalentCaseAndUnicodeStable() {
        CandidateProfileData upper = profile(List.of(" PAYMENTS "), List.of(" Backend "));
        CandidateProfileData lower = profile(List.of("payments"), List.of("backend"));

        assertEquals(validator.canonicalize(lower), validator.canonicalize(upper));
    }

    @Test void rejectsNestedCollectionAndTextAmplification() {
        List<String> manyBullets = java.util.stream.IntStream.range(0, 250)
                .mapToObj(index -> "bullet-" + index).toList();
        List<CandidateProfileData.WorkExperience> amplified = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> new CandidateProfileData.WorkExperience("Engineer", "Company", null,
                        null, false, manyBullets, "source")).toList();
        CandidateProfileData tooManyItems = new CandidateProfileData(List.of(), null, 0,
                CandidateProfileData.Assessment.UNKNOWN, amplified, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> validator.canonicalize(tooManyItems));

        String large = "x".repeat(20_000);
        List<CandidateProfileData.Evidence> evidence = java.util.stream.IntStream.range(0, 26)
                .mapToObj(index -> new CandidateProfileData.Evidence("note", "value-" + index, large))
                .toList();
        CandidateProfileData tooMuchText = new CandidateProfileData(List.of(), null, 0,
                CandidateProfileData.Assessment.UNKNOWN, List.of(), List.of(), List.of(), List.of(),
                List.of(), evidence, List.of());
        assertThrows(IllegalArgumentException.class, () -> validator.canonicalize(tooMuchText));
    }

    private static CandidateProfileData profile(List<String> keywords, List<String> roles) {
        return new CandidateProfileData(List.of(), null, 0, CandidateProfileData.Assessment.UNKNOWN,
                List.of(), List.of(), List.of(), keywords, roles, List.of(), List.of());
    }
}
