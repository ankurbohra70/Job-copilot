package com.jobcopilot.job;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobRequirementExtractorTest {
    private final JobRequirementExtractor extractor = new JobRequirementExtractor();

    @Test
    void requiredHeadingClassifiesSkillsAsRequired() {
        assertEquals(List.of("java"), extract("""
                Required:
                Java
                """).requiredSkills());
        assertEquals(List.of("java"), extract("""
                Requirements:
                Java
                """).requiredSkills());
        assertEquals(List.of("java"), extract("""
                Required skills:
                Java
                """).requiredSkills());
        assertEquals(List.of("java"), extract("""
                Must have:
                Java
                """).requiredSkills());
        assertEquals(List.of("java"), extract("""
                Minimum qualifications:
                Java
                """).requiredSkills());
        assertTrue(extract("""
                Required:
                Java
                """).preferredSkills().isEmpty());
    }

    @Test
    void preferredHeadingClassifiesSkillsAsPreferred() {
        assertEquals(List.of("docker"), extract("""
                Preferred:
                Docker
                """).preferredSkills());
        assertEquals(List.of("docker"), extract("""
                Preferred qualifications:
                Docker
                """).preferredSkills());
        assertEquals(List.of("docker"), extract("""
                Nice to have:
                Docker
                """).preferredSkills());
        assertEquals(List.of("docker"), extract("""
                Good to have:
                Docker
                """).preferredSkills());
        assertEquals(List.of("docker"), extract("""
                Bonus:
                Docker
                """).preferredSkills());
        assertTrue(extract("""
                Preferred:
                Docker
                """).requiredSkills().isEmpty());
    }

    @Test
    void inlinePreferredOverridesRequiredSectionForThatClause() {
        var result = extract("""
                Requirements:
                * Java
                * Redis
                * Docker is preferred
                """);
        assertEquals(List.of("java", "redis"), result.requiredSkills());
        assertEquals(List.of("docker"), result.preferredSkills());
    }

    @Test
    void unrelatedHeadingTerminatesInheritedSectionContext() {
        var preferredThenResponsibilities = extract("""
                Preferred Qualifications:
                * Docker
                * AWS

                Responsibilities:
                * Build Java microservices
                """);
        assertTrue(preferredThenResponsibilities.requiredSkills().isEmpty());
        assertEquals(List.of("aws", "docker", "java"), preferredThenResponsibilities.preferredSkills());

        var requiredThenResponsibilities = extract("""
                Requirements:
                * Java

                Responsibilities:
                * Build Docker microservices
                """);
        assertEquals(List.of("java"), requiredThenResponsibilities.requiredSkills());
        assertEquals(List.of("docker"), requiredThenResponsibilities.preferredSkills());
    }

    @Test
    void unqualifiedSkillsDefaultToPreferred() {
        var result = extract("You will build backend services using Java, Docker and Redis.");
        assertTrue(result.requiredSkills().isEmpty());
        assertEquals(List.of("docker", "java", "redis"), result.preferredSkills());
    }

    @Test
    void springBootDoesNotCreatePhantomSpring() {
        assertEquals(List.of("spring-boot"), extract("Required: Spring Boot").requiredSkills());
        assertTrue(extract("Required: Spring Boot").preferredSkills().isEmpty());
        assertEquals(List.of("spring-boot"), extract("Preferred: Spring Boot").preferredSkills());
        assertTrue(extract("Preferred: Spring Boot").requiredSkills().isEmpty());
        var both = extract("Spring Framework and Spring Boot");
        assertEquals(List.of("spring", "spring-boot"), both.preferredSkills());
        assertTrue(both.requiredSkills().isEmpty());
    }

    @Test
    void aliasesCollapseToCanonicalSkills() {
        var result = extract("""
                Requirements:
                Postgres, PostgreSQL, k8s
                """);
        assertEquals(List.of("kubernetes", "postgresql"), result.requiredSkills());
    }

    @Test
    void requiredWinsWhenTheSameSkillAppearsAsPreferred() {
        var result = extract("""
                Requirements:
                * Java

                Nice to have:
                * Java
                * Docker
                """);
        assertEquals(List.of("java"), result.requiredSkills());
        assertEquals(List.of("docker"), result.preferredSkills());
    }

    @Test
    void casePunctuationBulletsAndSemicolonsAreRecognized() {
        var result = extract("""
                Required skills: JAVA; redis.
                - Docker
                """);
        assertEquals(List.of("docker", "java", "redis"), result.requiredSkills());
    }

    @Test
    void symbolBearingSkillsUseVocabularyBoundaries() {
        var result = extract("You will use C++, C#, .NET and React.js.");
        assertEquals(List.of(".net", "c#", "c++", "react"), result.preferredSkills());
    }

    @Test
    void javaDoesNotMatchJavascript() {
        var result = extract("We build product UIs in JavaScript and TypeScript.");
        assertEquals(List.of("javascript", "typescript"), result.preferredSkills());
        assertTrue(result.preferredSkills().stream().noneMatch("java"::equals));
    }

    @Test
    void repeatedMentionsStayUniqueAndSorted() {
        var result = extract("Java and java and JAVA with Docker and docker.");
        assertEquals(List.of("docker", "java"), result.preferredSkills());
    }

    @Test
    void extractsApprovedNumericExperienceForms() {
        assertEquals(new BigDecimal("2"), extract("2+ years backend development").minYearsExperience());
        assertEquals(new BigDecimal("3"), extract("3 years of experience").minYearsExperience());
        assertEquals(new BigDecimal("2"), extract("minimum 2 years").minYearsExperience());
        assertEquals(new BigDecimal("1"), extract("at least 1 year").minYearsExperience());
        assertEquals(new BigDecimal("2.5"), extract("2.5 years of experience").minYearsExperience());
        assertEquals(new BigDecimal("2"), extract("2-4 years").minYearsExperience());
        assertEquals(new BigDecimal("3"), extract("3 to 5 years").minYearsExperience());
    }

    @Test
    void rangesStoreTheLowerBound() {
        assertEquals(new BigDecimal("2"), extract("2-4 years of experience").minYearsExperience());
        assertEquals(new BigDecimal("3"), extract("3 to 5 years of experience").minYearsExperience());
    }

    @Test
    void multipleMinimaUseTheGreatestLowerBound() {
        var result = extract("""
                2+ years backend development
                3+ years Java experience
                """);
        assertEquals(new BigDecimal("3"), result.minYearsExperience());
        assertEquals(List.of("java"), result.preferredSkills());
    }

    @Test
    void coordinatedSkillListsShareMarkerScope() {
        assertEquals(List.of("java", "redis"), extract("Must have Java and Redis.").requiredSkills());
        assertTrue(extract("Must have Java and Redis.").preferredSkills().isEmpty());
        assertEquals(List.of("java", "redis"), extract("Java and Redis are required.").requiredSkills());
        assertEquals(List.of("docker", "java", "redis"), extract("Required: Java, Redis and Docker").requiredSkills());
        assertEquals(List.of("docker", "java", "redis"), extract("Java, Redis, and Docker are preferred").preferredSkills());
        assertTrue(extract("Java, Redis, and Docker are preferred").requiredSkills().isEmpty());
        assertEquals(List.of("java", "redis"), extract("Java and Redis are nice to have").preferredSkills());
        var mixed = extract("Java is required and Docker is preferred");
        assertEquals(List.of("java"), mixed.requiredSkills());
        assertEquals(List.of("docker"), mixed.preferredSkills());
    }

    @Test
    void experienceUsesLocalRequiredPreferredContext() {
        assertEquals(new BigDecimal("2"), extract("2 years required and 5 years preferred").minYearsExperience());
        assertEquals(new BigDecimal("2"), extract("5 years preferred and 2 years required").minYearsExperience());
        assertEquals(new BigDecimal("2"), extract("Minimum 2 years; 5 years preferred").minYearsExperience());
        assertEquals(new BigDecimal("2"), extract("2 years required, 5+ years nice to have").minYearsExperience());
        assertNull(extract("5 years preferred. Java").minYearsExperience());
        var multipleRequired = extract("2+ years backend experience and minimum 3 years Java");
        assertEquals(new BigDecimal("3"), multipleRequired.minYearsExperience());
        assertEquals(List.of("java"), multipleRequired.preferredSkills());
    }

    @Test
    void gluedToRangeIsIgnored() {
        var result = extract("3to5 years. Java");
        assertEquals(List.of("java"), result.preferredSkills());
        assertNull(result.minYearsExperience());
        assertEquals(new BigDecimal("3"), extract("3-5 years. Java").minYearsExperience());
        assertEquals(new BigDecimal("3"), extract("3 – 5 years. Java").minYearsExperience());
        assertEquals(new BigDecimal("3"), extract("3 — 5 years. Java").minYearsExperience());
        assertEquals(new BigDecimal("3"), extract("3 to 5 years. Java").minYearsExperience());
    }

    @Test
    void markdownAndStandaloneHeadingsSetAndResetContext() {
        assertEquals(List.of("java"), extract("""
                ## Required Qualifications
                Java
                """).requiredSkills());
        assertEquals(List.of("docker"), extract("""
                ## Preferred Qualifications
                Docker
                """).preferredSkills());
        var markdownReset = extract("""
                Requirements:
                Java

                ## Responsibilities
                Redis
                """);
        assertEquals(List.of("java"), markdownReset.requiredSkills());
        assertEquals(List.of("redis"), markdownReset.preferredSkills());
        var uppercaseMarkdown = extract("""
                ## REQUIRED QUALIFICATIONS
                Java

                ## RESPONSIBILITIES
                Redis
                """);
        assertEquals(List.of("java"), uppercaseMarkdown.requiredSkills());
        assertEquals(List.of("redis"), uppercaseMarkdown.preferredSkills());
        var unknownHeading = extract("""
                Requirements:
                Java

                Culture
                Redis
                """);
        assertEquals(List.of("java"), unknownHeading.requiredSkills());
        assertEquals(List.of("redis"), unknownHeading.preferredSkills());
        var preferredMarkdownReset = extract("""
                ## Preferred Qualifications
                Docker

                ## Responsibilities
                Java
                """);
        assertEquals(List.of("docker", "java"), preferredMarkdownReset.preferredSkills());
        assertTrue(preferredMarkdownReset.requiredSkills().isEmpty());
    }

    @Test
    void nestedColonLabelsPreserveParentRequirementContext() {
        assertEquals(List.of("java"), extract("""
                Requirements:
                Core skills: Java
                """).requiredSkills());
        var backend = extract("""
                Requirements:
                Backend: Java, Spring Boot
                """);
        assertEquals(List.of("java", "spring-boot"), backend.requiredSkills());
        assertTrue(backend.preferredSkills().isEmpty());
    }

    @Test
    void vagueMalformedAndReversedExperienceExpressionsAreIgnored() {
        var result = extract("""
                Experienced engineer with strong experience, several years and extensive experience.
                Up to 5 years. 2 or 4 years. three years. 4-2 years.
                Java
                """);
        assertEquals(List.of("java"), result.preferredSkills());
        assertNull(result.minYearsExperience());
    }

    @Test
    void parseableDomainInvalidMinimumFailsWithoutReturningAResult() {
        JobRequirementExtractionException exception = assertThrows(
                JobRequirementExtractionException.class,
                () -> extractor.extract("minimum 100 years of experience"));
        assertEquals(
                "Extracted minimum experience is outside the allowed range of 0 to 80 years",
                exception.getMessage());
    }

    @Test
    void invalidMinimumDoesNotFailWhenSkillsArePresentIfTheInvalidExpressionIsNotMinimumOriented() {
        var result = extract("Java. Up to 100 years.");
        assertEquals(List.of("java"), result.preferredSkills());
        assertNull(result.minYearsExperience());
    }

    @Test
    void skillsWithoutExperienceStillSucceedWithNullMinimum() {
        var result = extract("Required: Java");
        assertEquals(List.of("java"), result.requiredSkills());
        assertNull(result.minYearsExperience());
    }

    @Test
    void blankAndNullDescriptionsFail() {
        assertEquals(
                "Job description must be non-blank to extract requirements",
                assertThrows(JobRequirementExtractionException.class, () -> extractor.extract(null)).getMessage());
        assertEquals(
                "Job description must be non-blank to extract requirements",
                assertThrows(JobRequirementExtractionException.class, () -> extractor.extract("   ")).getMessage());
    }

    @Test
    void noRecognizedRequirementsFail() {
        assertEquals(
                "No recognized job requirements could be extracted from the job description",
                assertThrows(JobRequirementExtractionException.class, () -> extractor.extract("A friendly workplace."))
                        .getMessage());
    }

    @Test
    void malformedExperienceDoesNotFailValidSkills() {
        var result = extract("Required: Java. 2- years of experience.");
        assertEquals(List.of("java"), result.requiredSkills());
        assertNull(result.minYearsExperience());
    }

    @Test
    void genericYearMentionsAreNotCandidateMinimums() {
        assertNull(extract("Team of 5 engineers, 2 years old product. Java").minYearsExperience());
        assertNull(extract("Used by 3 million users for 5 years. Java").minYearsExperience());
        assertNull(extract("Founded 10 years ago. Java").minYearsExperience());
        assertNull(extract("5 years in business. Java").minYearsExperience());
        assertNull(extract("4 year degree required. Java").minYearsExperience());
        assertNull(extract("Bachelor's degree, 4 years. Java").minYearsExperience());
        assertNull(extract("Contract duration: 2 years. Java").minYearsExperience());
        assertNull(extract("Company has 10 years of experience in fintech. Java").minYearsExperience());
        assertNull(extract("Our company has 10 years of experience in fintech. Java").minYearsExperience());
        assertNull(extract("Our founders have 15 years of experience. Java").minYearsExperience());
        assertNull(extract("The team has 7 years of combined experience. Java").minYearsExperience());
        assertNull(extract("Product has been in market for 5 years. Java").minYearsExperience());
        assertNull(extract("Used by customers for 5 years. Java").minYearsExperience());
    }

    @Test
    void candidateAndSectionExperienceIsExtracted() {
        assertEquals(new BigDecimal("3"), extract("You should have 3 years of experience").minYearsExperience());
        assertEquals(new BigDecimal("3"), extract("Candidate should have 3 years of experience").minYearsExperience());
        assertEquals(new BigDecimal("4"), extract("Candidate should bring 4+ years of backend experience").minYearsExperience());
        assertEquals(new BigDecimal("3"), extract("""
                Requirements:
                3 years Java experience
                """).minYearsExperience());
        assertEquals(new BigDecimal("2"), extract("At least 2 years of software development experience").minYearsExperience());
        assertEquals(new BigDecimal("2"), extract("""
                Minimum qualifications:
                Minimum 2 years.
                Java
                """).minYearsExperience());
    }

    @Test
    void mixedMarkerGroupsKeepListScopeAndPropositionBoundaries() {
        var commaGroups = extract("Java and Redis are required, Docker and AWS are preferred.");
        assertEquals(List.of("java", "redis"), commaGroups.requiredSkills());
        assertEquals(List.of("aws", "docker"), commaGroups.preferredSkills());
        var prose = extract("Java is required and you will work with Redis.");
        assertEquals(List.of("java"), prose.requiredSkills());
        assertEquals(List.of("redis"), prose.preferredSkills());
        var semicolon = extract("Must have Java and Redis; nice to have Docker and AWS.");
        assertEquals(List.of("java", "redis"), semicolon.requiredSkills());
        assertEquals(List.of("aws", "docker"), semicolon.preferredSkills());
        var prefix = extract("Must have Java and Redis");
        assertEquals(List.of("java", "redis"), prefix.requiredSkills());
        assertTrue(prefix.preferredSkills().isEmpty());
        var suffix = extract("Java and Redis are required");
        assertEquals(List.of("java", "redis"), suffix.requiredSkills());
        var allPreferred = extract("Java, Redis and Docker are preferred");
        assertEquals(List.of("docker", "java", "redis"), allPreferred.preferredSkills());
        assertTrue(allPreferred.requiredSkills().isEmpty());
        var inlineDoesNotMutateSection = extract("""
                Requirements:
                Java
                Docker is preferred
                Redis
                """);
        assertEquals(List.of("java", "redis"), inlineDoesNotMutateSection.requiredSkills());
        assertEquals(List.of("docker"), inlineDoesNotMutateSection.preferredSkills());
    }

    @Test
    void headingsUseStructureInsteadOfTitleCase() {
        var values = extract("""
                Requirements:
                Java

                Our Values:
                Redis
                """);
        assertEquals(List.of("java"), values.requiredSkills());
        assertEquals(List.of("redis"), values.preferredSkills());
        var whatYouWillBuild = extract("""
                Requirements:
                Java

                What You Will Build
                Redis
                """);
        assertEquals(List.of("java"), whatYouWillBuild.requiredSkills());
        assertEquals(List.of("redis"), whatYouWillBuild.preferredSkills());
        var communication = extract("""
                Requirements:
                Java
                Strong Communication
                Redis
                """);
        assertEquals(List.of("java", "redis"), communication.requiredSkills());
        var cloudNative = extract("""
                Requirements:
                Java
                Cloud Native
                Redis
                """);
        assertEquals(List.of("java", "redis"), cloudNative.requiredSkills());
        var markdownSkill = extract("""
                ## Java
                Redis
                """);
        assertEquals(List.of("java", "redis"), markdownSkill.preferredSkills());
        assertTrue(markdownSkill.requiredSkills().isEmpty());
        var markdownInRequirements = extract("""
                Requirements:
                ## Java
                Redis
                """);
        assertEquals(List.of("java", "redis"), markdownInRequirements.requiredSkills());
    }

    @Test
    void malformedRangesAreIgnoredAtomically() {
        assertNull(extract("3to5 years. Java").minYearsExperience());
        assertNull(extract("3to 5 years. Java").minYearsExperience());
        assertNull(extract("3-to-5 years. Java").minYearsExperience());
        assertEquals(new BigDecimal("3"), extract("3 to 5 years. Java").minYearsExperience());
        assertEquals(new BigDecimal("3"), extract("3-5 years. Java").minYearsExperience());
        assertEquals(new BigDecimal("3"), extract("3 – 5 years. Java").minYearsExperience());
        assertEquals(new BigDecimal("3"), extract("3 — 5 years. Java").minYearsExperience());
    }

    @Test
    void preferredExperienceMarkersAreLocal() {
        assertEquals(new BigDecimal("2"), extract("Minimum 2 years and ideally 5 years. Java").minYearsExperience());
        assertEquals(new BigDecimal("2"), extract("2 years required and 5 years desired. Java").minYearsExperience());
        assertNull(extract("5 years ideally. Java").minYearsExperience());
    }

    private JobRequirementExtractor.Extraction extract(String description) {
        return extractor.extract(description);
    }
}
