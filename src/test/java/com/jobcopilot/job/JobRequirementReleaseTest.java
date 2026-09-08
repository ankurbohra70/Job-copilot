package com.jobcopilot.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JobRequirementReleaseTest {
    private final JobRequirementExtractor extractor = new JobRequirementExtractor();

    @ParameterizedTest
    @ValueSource(strings = {"-2 years required", "Minimum -2-4 years experience", "Minimum -2 to 4 years experience"})
    void signedTokensAreNeverReinterpretedAsBulletsOrPositiveRanges(String text) {
        assertThrows(JobRequirementExtractionException.class, () -> extractor.extract(text));
    }

    @Test void nestedExperienceKeepsParentAcrossContentLines() {
        var result = extractor.extract("Requirements:\nExperience:\nStrong problem solving\nRedis");
        assertEquals(List.of("redis"), result.requiredSkills());
        assertEquals(List.of(), result.preferredSkills());
        assertEquals(new BigDecimal("3"), extractor.extract("Minimum Qualifications:\nExperience: 3 years").minYearsExperience());
    }

    @ParameterizedTest
    @ValueSource(strings = {"100", "80.001", "80.004", "-2", "2.345"})
    void invalidMandatoryNumbersNeverDisappear(String number) {
        for (String prefix : List.of("", "Required: Java\n"))
            assertThrows(JobRequirementExtractionException.class,
                    () -> extractor.extract(prefix + "Minimum " + number + " years experience"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "3 years backend development experience|3", "Professional experience of 3 years|3",
            "Experience: 3 years|3", "Relevant experience: 3 years|3", "3 years of Java experience|3",
            "The ideal candidate has 4 years of experience|4", "Applicants should have 3 years of experience|3",
            "You bring 5 years of experience|5", "We are looking for someone with 3 years of Java experience|3",
            "3 years desired, 5 required|5", "3 years required, 5 desired|3",
            "3 years preferred, 5 minimum|5", "3 years required, salary 5 LPA|3",
            "Ideally 5 years, minimum 2 years|2", "Minimum 2 years, ideally 5 years|2",
            "3 years preferred but 2 years minimum|2", "2 years required, preferably 5|2",
            "At least 2 years backend experience and 4+ years Java experience|4"})
    void quantityEvidenceAndMarkersAreLocal(String text, String years) {
        assertEquals(0, new BigDecimal(years).compareTo(extractor.extract(text).minYearsExperience()), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Our CTO has 15 years of experience", "Your manager has 12 years of experience",
            "Our founders have 15 years of experience", "Leadership team has 20 years combined experience",
            "Our company has 10 years of experience", "The company has been operating for 8 years",
            "We have served customers for 6 years", "The product has existed for 4 years",
            "This is a 2 year contract", "4 year degree", "At least three years", "2.345 years"})
    void nonCandidateOrUnsupportedDurationsAreIgnored(String text) {
        var result = extractor.extract(text + "\nJava");
        assertNull(result.minYearsExperience(), text);
        assertEquals(List.of("java"), result.preferredSkills());
        assertEquals(List.of(), result.requiredSkills());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Java is required, Redis preferred, Docker required|docker java|redis",
            "Java required; Redis preferred; Docker required|docker java|redis",
            "Java required|java|", "Java and Redis required|java redis|",
            "Java, Redis, Docker required|docker java redis|",
            "Java and Redis both required|java redis|",
            "Must have Java and our platform uses Redis|java|redis",
            "Must have Java and you will work with Redis|java|redis",
            "Must have Java and the team deploys with Docker|java|docker",
            "Must have Java, Redis and Docker|docker java redis|",
            "Must have .NET and React.js|.net react|", "C# and .NET are required|.net c#|",
            "Preferred: React.js and .NET||.net react",
            "Java is required. Redis is preferred.|java|redis",
            "Required Java and Redis, preferred Docker and AWS|java redis|aws docker",
            "Java and Redis are required, Docker and AWS are preferred|java redis|aws docker"})
    void markerGroupsPreserveExactSkillSets(String text, String required, String preferred) {
        var result = extractor.extract(text);
        assertEquals(words(required), result.requiredSkills(), text);
        assertEquals(words(preferred), result.preferredSkills(), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Experience:", "Experience: strong problem solving", "Education:\nBachelor's degree",
            "Skills:", "Core Skills:", "Technical Skills:", "Qualifications:", "Certifications:"})
    void nestedLabelsInheritBothParentSections(String label) {
        var required = extractor.extract("Requirements:\n" + label + "\nRedis");
        assertEquals(List.of("redis"), required.requiredSkills());
        assertEquals(List.of(), required.preferredSkills());
        var preferred = extractor.extract("Preferred Qualifications:\n" + label + "\nRedis");
        assertEquals(List.of(), preferred.requiredSkills());
        assertEquals(List.of("redis"), preferred.preferredSkills());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Benefits:", "About Us:", "Responsibilities:", "Our Engineering:", "Location:"})
    void unrelatedHeadingsStillReset(String heading) {
        var result = extractor.extract("Requirements:\nJava\n" + heading + "\nRedis");
        assertEquals(List.of("java"), result.requiredSkills());
        assertEquals(List.of("redis"), result.preferredSkills());
    }

    @Test void repeatedSectionsAndMarkerCombinationsStayDeterministic() {
        String text = "Requirements:\nJava and Redis\nResponsibilities:\nBuild systems with Docker\n".repeat(100);
        var result = extractor.extract(text);
        assertEquals(List.of("java", "redis"), result.requiredSkills());
        assertEquals(List.of("docker"), result.preferredSkills());
        assertEquals(result, extractor.extract(text));
        for (String separator : List.of(", ", " and ")) for (String marker : List.of("required", "preferred")) {
            for (String phrase : List.of(marker + " Java" + separator + "Redis", "Java" + separator + "Redis " + marker)) {
                var actual = extractor.extract(phrase);
                assertEquals(marker.equals("required") ? List.of("java", "redis") : List.of(), actual.requiredSkills(), phrase);
                assertEquals(marker.equals("preferred") ? List.of("java", "redis") : List.of(), actual.preferredSkills(), phrase);
            }
        }
    }

    private static List<String> words(String value) { return value == null ? List.of() : List.of(value.split(" ")); }
}
