package com.jobcopilot.resume;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LayoutExtractionTest {
    private final PdfBoxResumeTextExtractor extractor = new PdfBoxResumeTextExtractor(
            new ResumeProcessingProperties(5242880, 25, 200000, 1));
    static String legacy(byte[] pdf) throws IOException {
        try (var document = Loader.loadPDF(pdf)) {
            var stripper = new PDFTextStripper(); stripper.setSortByPosition(true);
            return stripper.getText(document).replace("\u0000", "");
        }
    }
    private void parity(byte[] pdf) throws IOException {
        var extraction = extractor.extract(pdf);
        assertEquals(legacy(pdf), extraction.text());
        var parser = new DeterministicProfileParser(); var day = LocalDate.of(2026, 10, 3);
        assertEquals(parser.parse(legacy(pdf), day), parser.parse(extraction.text(), day));
        assertEquals(extraction.text(), extraction.document().plainText());
        assertEquals("pdfbox-3.0.8", extraction.version());
        assertEquals("layout-v2", extraction.layoutVersion());
    }
    @ParameterizedTest
    @ValueSource(strings = {"Ankur_Bohra_Amazon", "Ankur_Bohra_Backend_GenAI", "Ankur_Bohra_SDE"})
    void realPdfExactTextAndCompleteProfileParity(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/resume/deterministic/" + name + ".pdf")) {
            assertNotNull(input); parity(input.readAllBytes());
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"AnkurBohra_AI", "AnkurBohra_SDeee"})
    void optionalLocalPdfExactParity(String name) throws Exception {
        String directory = System.getProperty("rulesv5.localPdfDirectory");
        assumeTrue(directory != null, "Local private PDF acceptance requires rulesv5.localPdfDirectory");
        Path path = Path.of(directory, name + ".pdf");
        assumeTrue(Files.isRegularFile(path), "Local private PDF is unavailable");
        parity(Files.readAllBytes(path));
    }
    static byte[] layoutPdf(int rotation, boolean crop, boolean annotation) throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(); page.setRotation(rotation);
            if (crop) page.setCropBox(new PDRectangle(10, 20, 580, 740));
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                text(content, "Ada Lovelace", 40, 730, 18);
                text(content, "Email: Ada@Example.COM", 40, 710, 10);
                text(content, "GitHub", 40, 690, 10);
                text(content, "Caf\u00e9 na\u00efve", 320, 690, 10);
                text(content, "GitHub", 40, 690, 10); // duplicate overlapping text
                text(content, "Experience", 40, 640, 12);
                text(content, "Engineer at Acme", 40, 620, 10);
                text(content, "Jan 2025 - Present", 40, 600, 10);
                text(content, "- Built Java services", 40, 580, 10);
            }
            if (annotation) {
                var link = new PDAnnotationLink(); var action = new PDActionURI();
                action.setURI("https://github.com/ada"); link.setAction(action);
                link.setRectangle(new PDRectangle(40, 686, 35, 14)); page.getAnnotations().add(link);
            }
            document.addPage(new PDPage()); // physical blank page
            var last = new PDPage(); document.addPage(last);
            try (var content = new PDPageContentStream(document, last)) {
                text(content, "Ada Lovelace", 40, 730, 18); // repeated header
                text(content, "Projects", 40, 640, 12);
                text(content, "Alpha | GitHub", 40, 620, 10);
                text(content, "- Built reliable APIs", 40, 600, 10);
            }
            document.save(out); return out.toByteArray();
        }
    }
    static void text(PDPageContentStream content, String value, float x, float y, float size) throws IOException {
        content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
        content.newLineAtOffset(x, y); content.showText(value); content.endText();
    }
    @ParameterizedTest @ValueSource(ints = {0, 90, 180, 270})
    void spacingColumnsDiacriticsDuplicatesBlankPagesRotationAndCropParity(int rotation) throws Exception {
        parity(layoutPdf(rotation, true, true));
    }
    @Test void capturesLinesPagesFontsAndAssociatedUri() throws Exception {
        var document = extractor.extract(layoutPdf(0, false, true)).document();
        assertTrue(document.layoutAvailable()); assertEquals(3, document.pages().size());
        var page = document.pages().getFirst();
        assertEquals("Ada Lovelace", page.lines().getFirst().text());
        assertEquals(1, page.pageNumber()); assertEquals(18, page.lines().getFirst().spans().getFirst().fontSize());
        assertTrue(page.lines().getFirst().bounds().valid());
        assertEquals("https://github.com/ada", page.hyperlinks().getFirst().uri());
        assertNotNull(page.hyperlinks().getFirst().lineReference());
        assertTrue(document.pages().get(1).lines().isEmpty()); assertEquals(3, document.pages().get(2).pageNumber());
    }
    @Test void metadataLimitsDisableLayoutWithoutChangingText() throws Exception {
        byte[] pdf = layoutPdf(0, false, true);
        for (int[] limits : new int[][] {{1, 20000, 500}, {200000, 1, 500}, {200000, 20000, 0}}) {
            try (var document = Loader.loadPDF(pdf)) {
                var stripper = new LayoutCapturingPdfTextStripper(limits[0], limits[1], limits[2]);
                stripper.setSortByPosition(true);
                String text = stripper.getText(document).replace("\u0000", "");
                assertEquals(legacy(pdf), text);
                var snapshot = stripper.snapshot(text, document);
                assertFalse(snapshot.layoutAvailable()); assertTrue(snapshot.pages().isEmpty());
            }
        }
    }
    @Test void unsupportedDirectionDoesNotChangeText() throws Exception {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(); document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setTextMatrix(org.apache.pdfbox.util.Matrix.getRotateInstance(Math.PI / 2, 100, 100));
                content.showText("Rotated contact text"); content.endText();
            }
            document.save(out); byte[] pdf = out.toByteArray(); parity(pdf);
            assertFalse(extractor.extract(pdf).document().layoutAvailable());
        }
    }
    @Test void embeddedUnicodeAndLigatureNormalizationRemainIdentical() throws Exception {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream();
             var fontStream = PDFTextStripper.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(fontStream);
            var font = PDType0Font.load(document,fontStream); var page = new PDPage(); document.addPage(page);
            try (var content = new PDPageContentStream(document,page)) {
                content.beginText(); content.setFont(font,14); content.newLineAtOffset(40,730);
                content.showText("\u00c9lodie na\u00efve \ufb01 \ufb02 office"); content.endText();
            }
            document.save(out); parity(out.toByteArray());
        }
    }
}
