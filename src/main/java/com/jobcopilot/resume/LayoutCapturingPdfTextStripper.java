package com.jobcopilot.resume;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static com.jobcopilot.resume.ResumeDocument.*;

/** Observes emission hooks; every text write remains PDFTextStripper's own write. */
final class LayoutCapturingPdfTextStripper extends PDFTextStripper {
    static final String VERSION = "layout-v2";
    private final int maxPositions, maxLines, maxLinks;
    private int positions, lineCount, linkCount;
    private String unavailable;
    private final List<ResumePage> pages = new ArrayList<>();
    private final List<ResumeLine> lines = new ArrayList<>();
    private final List<ResumeTextSpan> spans = new ArrayList<>();
    private final StringBuilder line = new StringBuilder();
    private PDPage page;

    LayoutCapturingPdfTextStripper() { this(200_000, 20_000, 500); }
    LayoutCapturingPdfTextStripper(int positions, int lines, int links) {
        maxPositions = positions; maxLines = lines; maxLinks = links;
    }
    private void disable(String reason) {
        unavailable = reason; pages.clear(); lines.clear(); spans.clear(); line.setLength(0);
    }
    @Override protected void startPage(PDPage value) throws IOException {
        super.startPage(value); page = value;
        var box = page.getCropBox();
        if (box.getWidth() <= 0 || box.getHeight() <= 0 || !Float.isFinite(box.getWidth())
                || !Float.isFinite(box.getHeight()) || Math.floorMod(page.getRotation(), 90) != 0)
            disable("UNSUPPORTED_GEOMETRY");
    }
    @Override protected void writeString(String text, List<TextPosition> values) throws IOException {
        super.writeString(text, values);
        if (unavailable != null) return;
        positions += values.size();
        if (positions > maxPositions) { disable("LAYOUT_POSITION_LIMIT"); return; }
        if (values.isEmpty()) { disable("UNSUPPORTED_GEOMETRY"); return; }
        Bounds bounds = null;
        float size = 0;
        for (var value : values) {
            // getX/getY share page-rotation coordinates; direction-adjusted coordinates differ for rotated text.
            if (value.getDir() != 0) { disable("UNSUPPORTED_TEXT_DIRECTION"); return; }
            var b = new Bounds(value.getX(), value.getY(), value.getWidth(), value.getHeight());
            if (!b.valid() || !Float.isFinite(value.getFontSizeInPt())) { disable("UNSUPPORTED_GEOMETRY"); return; }
            bounds = union(bounds, b); size = Math.max(size, value.getFontSizeInPt());
        }
        String emitted = text.replace("\u0000", "");
        line.append(emitted);
        spans.add(new ResumeTextSpan(emitted, bounds, size,
                values.getFirst().getFont() == null ? null : values.getFirst().getFont().getName(), 0));
    }
    @Override protected void writeWordSeparator() throws IOException {
        super.writeWordSeparator(); if (unavailable == null) line.append(getWordSeparator());
    }
    @Override protected void writeLineSeparator() throws IOException {
        super.writeLineSeparator(); finishLine();
    }
    private void finishLine() {
        if (unavailable != null || spans.isEmpty()) return;
        if (++lineCount > maxLines) { disable("LAYOUT_LINE_LIMIT"); return; }
        Bounds bounds = null;
        for (var span : spans) bounds = union(bounds, span.bounds());
        lines.add(new ResumeLine("p" + getCurrentPageNo() + "-l" + (lines.size() + 1),
                line.toString(), lines.size(), bounds, spans));
        line.setLength(0); spans.clear();
    }
    @Override protected void endPage(PDPage value) throws IOException {
        super.endPage(value); finishLine();
        if (unavailable != null) return;
        var links = new ArrayList<ResumeHyperlink>();
        try {
            for (var annotation : value.getAnnotations()) {
                if (!(annotation instanceof PDAnnotationLink link) || !(link.getAction() instanceof PDActionURI action)) continue;
                if (++linkCount > maxLinks) { disable("LAYOUT_LINK_LIMIT"); return; }
                String uri = action.getURI();
                var rect = link.getRectangle();
                if (uri == null || uri.length() > 2048 || rect == null) continue;
                Bounds b = annotationBounds(rect.getLowerLeftX(), rect.getLowerLeftY(), rect.getWidth(), rect.getHeight());
                if (!b.valid()) { disable("UNSUPPORTED_GEOMETRY"); return; }
                String reference = lines.stream().filter(l -> l.spans().stream().anyMatch(s -> s.bounds().intersects(b)))
                        .map(ResumeLine::reference).findFirst().orElse(null);
                links.add(new ResumeHyperlink(uri, b, reference));
            }
        } catch (IOException malformedAnnotations) {
            disable("ANNOTATIONS_UNAVAILABLE"); return;
        }
        var box = value.getCropBox();
        int rotation = Math.floorMod(value.getRotation(), 360);
        boolean swap = rotation == 90 || rotation == 270;
        pages.add(new ResumePage(getCurrentPageNo(), swap ? box.getHeight() : box.getWidth(),
                swap ? box.getWidth() : box.getHeight(), box.getLowerLeftX(), box.getLowerLeftY(),
                rotation, lines, links));
        lines.clear();
    }
    private Bounds annotationBounds(float x, float y, float width, float height) {
        var crop = page.getCropBox(); x -= crop.getLowerLeftX(); y -= crop.getLowerLeftY();
        return switch (Math.floorMod(page.getRotation(), 360)) {
            case 90 -> new Bounds(y, x + width, height, width);
            case 180 -> new Bounds(crop.getWidth() - x - width, y + height, width, height);
            case 270 -> new Bounds(crop.getHeight() - y - height, crop.getWidth() - x, height, width);
            default -> new Bounds(x, crop.getHeight() - y, width, height);
        };
    }
    private static Bounds union(Bounds left, Bounds right) {
        if (left == null) return right;
        float x = Math.min(left.x(), right.x()), top = Math.min(left.y() - left.height(), right.y() - right.height());
        float bottom = Math.max(left.y(), right.y());
        return new Bounds(x, bottom, Math.max(left.x() + left.width(), right.x() + right.width()) - x, bottom - top);
    }
    ResumeDocument snapshot(String text, PDDocument document) {
        if (unavailable != null) return ResumeDocument.unavailable(text, unavailable);
        // PDFTextStripper skips pages without content; preserve their physical page numbers explicitly.
        var complete = new ArrayList<ResumePage>();
        for (int index = 0; index < document.getNumberOfPages(); index++) {
            int number = index + 1;
            var captured = pages.stream().filter(p -> p.pageNumber() == number).findFirst();
            if (captured.isPresent()) complete.add(captured.get());
            else {
                var empty = document.getPage(index); var crop = empty.getCropBox();
                int rotation = Math.floorMod(empty.getRotation(), 360);
                boolean swap = rotation == 90 || rotation == 270;
                complete.add(new ResumePage(number, swap ? crop.getHeight() : crop.getWidth(),
                        swap ? crop.getWidth() : crop.getHeight(), crop.getLowerLeftX(), crop.getLowerLeftY(),
                        rotation, List.of(), List.of()));
            }
        }
        return new ResumeDocument(text, complete, true, List.of());
    }
}
