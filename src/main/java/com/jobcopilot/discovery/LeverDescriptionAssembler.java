package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverPosting;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
final class LeverDescriptionAssembler {
    String assemble(LeverPosting.Content content) {
        Set<String> components = new LinkedHashSet<>();
        add(components, preferred(content.descriptionPlain(), content.descriptionHtml()));
        for (LeverPosting.Section section : content.sections()) {
            String heading = normalize(section.heading());
            String body = htmlText(section.html());
            add(components, join(heading, body));
        }
        add(components, preferred(content.additionalPlain(), content.additionalHtml()));
        String assembled = String.join("\n\n", components);
        return assembled.isBlank() ? null : assembled;
    }

    private static String preferred(String plain, String html) {
        String normalizedPlain = normalize(plain);
        return normalizedPlain != null ? normalizedPlain : htmlText(html);
    }

    private static String htmlText(String html) {
        if (html == null || html.isBlank()) return null;
        validateControls(html);
        Document document = Jsoup.parseBodyFragment(html);
        document.outputSettings().prettyPrint(false);
        for (Element element : document.select("br")) element.after("\n");
        for (Element element : document.select("li")) {
            element.prependText("• ");
            element.appendText("\n");
        }
        for (Element element : document.select(
                "p,div,section,article,header,footer,h1,h2,h3,h4,h5,h6,ul,ol,table,tr,blockquote,pre")) {
            element.appendText("\n");
        }
        return normalize(document.body().wholeText());
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
        if (value == null) return null;
        String unix = value.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
        validateControls(unix);
        String[] lines = unix.split("\n", -1);
        StringBuilder normalized = new StringBuilder(unix.length());
        int blankLines = 0;
        for (String line : lines) {
            String cleaned = line.stripTrailing();
            if (cleaned.isBlank()) {
                blankLines++;
                if (blankLines > 2) continue;
                cleaned = "";
            } else {
                blankLines = 0;
            }
            if (!normalized.isEmpty()) normalized.append('\n');
            normalized.append(cleaned);
        }
        String result = normalized.toString().strip();
        return result.isEmpty() ? null : result;
    }

    private static void validateControls(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\n' && character != '\r' && character != '\t'
                    && Character.isISOControl(character)) {
                throw new IllegalArgumentException("text contains unsupported control characters");
            }
        }
    }
}
