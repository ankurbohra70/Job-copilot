package com.jobcopilot.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DescriptionTextNormalizerTest {
    @Test void decodesEscapedMarkupAndNestedEntitiesWithoutLosingUnicode() {
        assertThat(DescriptionTextNormalizer.htmlText(
                "&lt;p&gt;Équipe &amp;amp; café&lt;/p&gt;"))
                .isEqualTo("Équipe & café");
        assertThat(DescriptionTextNormalizer.htmlText(
                "&amp;lt;p&amp;gt;São Paulo&amp;lt;/p&amp;gt;"))
                .isEqualTo("São Paulo");
    }

    @Test void preservesParagraphAndBulletBoundariesAndPlainText() {
        assertThat(DescriptionTextNormalizer.htmlText(
                "<p>First</p><p>Second</p><ul><li>One</li><li>Two</li></ul>"))
                .isEqualTo("First\nSecond\n• One\n• Two");
        assertThat(DescriptionTextNormalizer.htmlText("Plain résumé &amp; skills"))
                .isEqualTo("Plain résumé & skills");
    }

    @Test void dropsExecutableMarkupAndNormalizesLineEndings() {
        assertThat(DescriptionTextNormalizer.htmlText(
                "<style>.x{}</style><p>A</p><script>secret()</script><p>B</p>"))
                .isEqualTo("A\nB");
        assertThat(DescriptionTextNormalizer.normalize("  A  \r\n\r\n\r\n\r\n B\t "))
                .isEqualTo("A\n\n\n B");
    }
}
