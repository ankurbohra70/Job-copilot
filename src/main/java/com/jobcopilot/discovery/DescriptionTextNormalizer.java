package com.jobcopilot.discovery;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/** Converts provider HTML to deterministic plain text before canonical persistence. */
public final class DescriptionTextNormalizer {
    private DescriptionTextNormalizer() {}

    public static String htmlText(String html) {
        if (html == null || html.isBlank()) return null;
        validateControls(html);
        String decoded = html;
        // Some CXS descriptions contain escaped markup, including a second escaped entity layer.
        for (int pass = 0; pass < 2; pass++) {
            String next = Parser.unescapeEntities(decoded, false);
            if (next.equals(decoded)) break;
            decoded = next;
        }
        Document document = Jsoup.parseBodyFragment(decoded);
        document.outputSettings().prettyPrint(false);
        document.select("script,style,noscript,template").remove();
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

    public static String normalize(String value) {
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
