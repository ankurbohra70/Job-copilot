package com.jobcopilot.resume;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;
import static com.jobcopilot.resume.ResumeExceptions.*;

public class PdfBoxResumeTextExtractorTest {
    @Test void rejectsActualImageOnlyPdf() throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(); document.addPage(page);
            var bitmap = new java.awt.image.BufferedImage(20,20,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var image = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document,bitmap);
            try (var content = new PDPageContentStream(document,page)) { content.drawImage(image,30,30); }
            document.save(output);
            assertThrows(EmptyResumeTextException.class, () -> extractor(200000).extract(output.toByteArray()));
        }
    }
    @Test void rejectsPageLimitBeforeExtracting() throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage()); document.addPage(new PDPage()); document.save(output);
            var extractor = new PdfBoxResumeTextExtractor(new ResumeProcessingProperties(5242880,1,200000,1));
            assertThrows(ResumeProcessingLimitException.class, () -> extractor.extract(output.toByteArray()));
        }
    }
    public static byte[] pdf(String text, boolean encrypted) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(); document.addPage(page);
            if (!text.isEmpty()) try (var content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(30, 700);
                for (String line : text.split("\n")) { content.showText(line); content.newLineAtOffset(0, -16); }
                content.endText();
            }
            if (encrypted) document.protect(new StandardProtectionPolicy("owner", "password", new AccessPermission()));
            document.save(output); return output.toByteArray();
        }
    }
    private PdfBoxResumeTextExtractor extractor(int characters) { return new PdfBoxResumeTextExtractor(new ResumeProcessingProperties(5242880,25,characters,50)); }
    @Test void extractsRealText() throws Exception {
        String text = "Java Spring Boot PostgreSQL backend development with reliable services and testing";
        assertTrue(extractor(200000).extract(pdf(text, false)).text().contains("Java Spring Boot"));
    }
    @Test void rejectsNoText() throws Exception { assertThrows(EmptyResumeTextException.class, () -> extractor(200000).extract(pdf("", false))); }
    @Test void rejectsEncrypted() throws Exception { assertThrows(ResumeParsingException.class, () -> extractor(200000).extract(pdf("secret", true))); }
    @Test void rejectsMalformed() { assertThrows(ResumeParsingException.class, () -> extractor(200000).extract("%PDF-garbage".getBytes())); }
    @Test void boundsTextWhileWriting() throws Exception { assertThrows(ResumeProcessingLimitException.class, () -> extractor(10).extract(pdf("longer than ten characters", false))); }
    @Test void boundsInputBytes() { assertThrows(ResumeTooLargeException.class, () -> new PdfBoxResumeTextExtractor(new ResumeProcessingProperties(5,25,100,1)).extract(new byte[6])); }
}
