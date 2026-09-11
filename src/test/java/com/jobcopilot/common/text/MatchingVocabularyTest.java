package com.jobcopilot.common.text;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MatchingVocabularyTest {
    @Test void activeWindowWorkIsLinearForNonOverlappingOccurrences() {
        for (int count : new int[]{1000, 5000, 10000, 20000}) {
            var spans = java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> new MatchingVocabulary.SkillSpan("java", i * 5, i * 5 + 4)).toList();
            var result = MatchingVocabulary.resolveSpans(spans);
            assertEquals(count, result.spans().size());
            assertTrue(result.comparisons() <= count, "all-history scanning must not return");
            long best = Long.MAX_VALUE;
            String text = "java ".repeat(count);
            for (int iteration = 0; iteration < 3; iteration++) {
                long start = System.nanoTime();
                assertEquals(java.util.List.of("java"), vocabulary.skills(text));
                best = Math.min(best, System.nanoTime() - start);
            }
            System.out.println("Vocabulary " + count + " occurrences: " + best / 1_000_000.0 + " ms; interval work=" + result.comparisons());
        }
    }

    @Test void distinctOccurrencesAndProtectedPunctuationHaveNormalizedOffsets() {
        assertEquals(java.util.List.of("spring", "spring-boot"), vocabulary.skills("Spring Boot Spring Framework"));
        assertEquals(java.util.List.of("spring", "spring-boot"), vocabulary.skills("Spring Framework Spring Boot"));
        String text = MatchingVocabulary.normalize("  Ｃ# and .NET and React.js  ");
        for (var span : vocabulary.skillSpans(text)) {
            assertEquals(span.canonical(), vocabulary.canonical(text.substring(span.start(), span.end())));
        }
    }
    private final MatchingVocabulary vocabulary = MatchingVocabulary.standard();
    @Test void aliasesAreCanonical() { assertEquals("postgresql", vocabulary.canonical(" Postgres ")); assertEquals("spring-boot", vocabulary.canonical("SPRING BOOT")); }
    @Test void longerSkillSpansSuppressShorterOverlaps() {
        assertEquals(java.util.List.of("spring-boot"), vocabulary.skills("Required: Spring Boot"));
        assertEquals(java.util.List.of("spring-boot"), vocabulary.skills("Preferred: Spring Boot"));
        assertEquals(java.util.List.of("spring-boot"), vocabulary.skills("Spring Boot"));
        assertEquals(java.util.List.of("spring"), vocabulary.skills("Spring Framework"));
        assertEquals(java.util.List.of("spring", "spring-boot"), vocabulary.skills("Spring Framework and Spring Boot"));
        assertFalse(vocabulary.hasSkill("Spring Boot", "spring"));
        assertTrue(vocabulary.hasSkill("Spring Framework and Spring Boot", "spring"));
        assertTrue(vocabulary.hasSkill("Spring Framework and Spring Boot", "spring-boot"));
    }

    @Test void overlapResolutionScalesWithoutPairwiseComparisonOfEverySpan() {
        String repeated = "java ".repeat(2000);
        long started = System.nanoTime();
        assertEquals(java.util.List.of("java"), vocabulary.skills(repeated));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMs < 2_000, "overlap resolution took " + elapsedMs + "ms");
    }
    @Test void overlapDoesNotConfuseDistinctSkills() {
        assertEquals(java.util.List.of("javascript"), vocabulary.skills("JavaScript"));
        assertFalse(vocabulary.skills("JavaScript").contains("java"));
        assertEquals(java.util.List.of("postgresql"), vocabulary.skills("Postgres"));
        assertEquals(java.util.List.of("postgresql"), vocabulary.skills("PostgreSQL"));
        assertEquals(java.util.List.of(".net", "c#", "c++", "react"), vocabulary.skills("C++, C# and .NET and React.js"));
        assertTrue(vocabulary.hasSkill("C++, C# and .NET", "c++"));
        assertFalse(vocabulary.hasSkill("scar", "c"));
    }
    @Test void keywordsDoNotDoubleCountSkillsOrRole() { assertEquals(java.util.List.of("payments"), vocabulary.keywords("Java backend engineer and payments payments")); }
    @Test void chronologyMonthNamesAreNotContextKeywords() {
        assertEquals(java.util.List.of("delivered"), vocabulary.keywords("Jan January May Dec December delivered"));
    }
}
