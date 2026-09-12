package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverPosting;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LeverDescriptionAssemblerTest {
    private final LeverDescriptionAssembler assembler = new LeverDescriptionAssembler();

    @Test void usesPlainPrecedencePreservesSectionsAndDeduplicatesExactComponents() {
        var content = new LeverPosting.Content("<p>ignored</p>", " Base\r\ntext\t ", List.of(
                new LeverPosting.Section(" Requirements ", "<ul><li>Java &amp; SQL</li><li>Docker</li></ul>")),
                "<p>ignored too</p>", "Base\ntext");

        String result = assembler.assemble(content);

        assertTrue(result.startsWith("Base\ntext\n\nRequirements"));
        assertTrue(result.contains("• Java & SQL"));
        assertTrue(result.contains("• Docker"));
        assertEquals(1, occurrences(result, "Base\ntext"));
    }

    @Test void usesHtmlFallbackAndAllowsBlankContent() {
        assertEquals("First\nSecond", assembler.assemble(new LeverPosting.Content(
                "<p>First</p><p>Second</p>", null, List.of(), null, null)));
        assertNull(assembler.assemble(new LeverPosting.Content(null, "  ", List.of(), null, null)));
    }

    @Test void rejectsUnsupportedControlsAndCollapsesBlankLines() {
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(
                new LeverPosting.Content(null, "bad\u0000text", List.of(), null, null)));
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(
                new LeverPosting.Content("<p>bad\u0000html</p>", null, List.of(), null, null)));
        assertEquals("a\n\n\nb", LeverDescriptionAssembler.normalize("a\n\n\n\n\nb"));
    }

    private static int occurrences(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }
}
