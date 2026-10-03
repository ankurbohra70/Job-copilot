package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverPosting;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
final class LeverDescriptionAssembler {
    String assemble(LeverPosting.Content content) {
        Set<String> components = new LinkedHashSet<>();
        add(components, preferred(content.descriptionPlain(), content.descriptionHtml()));
        for (LeverPosting.Section section : content.sections()) {
            String heading = normalize(section.heading());
            String body = DescriptionTextNormalizer.htmlText(section.html());
            add(components, join(heading, body));
        }
        add(components, preferred(content.additionalPlain(), content.additionalHtml()));
        String assembled = String.join("\n\n", components);
        return assembled.isBlank() ? null : assembled;
    }

    private static String preferred(String plain, String html) {
        String normalizedPlain = normalize(plain);
        return normalizedPlain != null ? normalizedPlain : DescriptionTextNormalizer.htmlText(html);
    }

    private static String join(String heading, String body) {
        if (heading == null) return body;
        if (body == null) return heading;
        return heading + "\n" + body;
    }

    private static void add(Set<String> components, String value) {
        if (value != null) components.add(value);
    }

    static String normalize(String value) {
        return DescriptionTextNormalizer.normalize(value);
    }
}
