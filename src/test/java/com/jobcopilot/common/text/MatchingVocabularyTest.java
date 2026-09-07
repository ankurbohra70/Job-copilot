package com.jobcopilot.common.text;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MatchingVocabularyTest {
    private final MatchingVocabulary vocabulary = MatchingVocabulary.standard();
    @Test void aliasesAreCanonical() { assertEquals("postgresql", vocabulary.canonical(" Postgres ")); assertEquals("spring-boot", vocabulary.canonical("SPRING BOOT")); }
    @Test void boundariesDoNotConfuseLanguages() { assertFalse(vocabulary.hasSkill("JavaScript", "java")); assertTrue(vocabulary.hasSkill("C++, C# and .NET", "c++")); assertFalse(vocabulary.hasSkill("scar", "c")); }
    @Test void keywordsDoNotDoubleCountSkillsOrRole() { assertEquals(java.util.List.of("payments"), vocabulary.keywords("Java backend engineer and payments payments")); }
}

