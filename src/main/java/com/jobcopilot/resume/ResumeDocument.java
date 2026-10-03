package com.jobcopilot.resume;

import java.util.List;

/** Request-local scalar snapshot. Never use layout to reconstruct parser input. */
public record ResumeDocument(String plainText, List<ResumePage> pages, boolean layoutAvailable,
        List<String> diagnostics) {
    public ResumeDocument { pages = List.copyOf(pages); diagnostics = List.copyOf(diagnostics); }
    public static ResumeDocument unavailable(String text, String reason) {
        return new ResumeDocument(text, List.of(), false, List.of(reason));
    }
    @Override public String toString() { return "ResumeDocument[redacted]"; }

    /** Bounds are crop-relative, rotation-adjusted page points, top-left origin. y is baseline. */
    public record Bounds(float x, float y, float width, float height) {
        public boolean valid() {
            return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(width) && Float.isFinite(height)
                    && width >= 0 && height >= 0;
        }
        public boolean intersects(Bounds other) {
            return x <= other.x + other.width && x + width >= other.x
                    && y - height <= other.y && y >= other.y - other.height;
        }
    }
    public record ResumePage(int pageNumber, float width, float height, float cropX, float cropY,
            int rotation, List<ResumeLine> lines, List<ResumeHyperlink> hyperlinks) {
        public ResumePage { lines = List.copyOf(lines); hyperlinks = List.copyOf(hyperlinks); }
        @Override public String toString() { return "ResumePage[redacted]"; }
    }
    public record ResumeLine(String reference, String text, int readingOrder, Bounds bounds,
            List<ResumeTextSpan> spans) {
        public ResumeLine { spans = List.copyOf(spans); }
        @Override public String toString() { return "ResumeLine[redacted]"; }
    }
    public record ResumeTextSpan(String text, Bounds bounds, float fontSize, String fontName, float direction) {
        @Override public String toString() { return "ResumeTextSpan[redacted]"; }
    }
    public record ResumeHyperlink(String uri, Bounds bounds, String lineReference) {
        @Override public String toString() { return "ResumeHyperlink[redacted]"; }
    }
}
