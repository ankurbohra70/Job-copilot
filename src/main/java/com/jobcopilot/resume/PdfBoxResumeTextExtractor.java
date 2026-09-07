package com.jobcopilot.resume;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.Writer;
import static com.jobcopilot.resume.ResumeExceptions.*;

@Component
public class PdfBoxResumeTextExtractor implements ResumeTextExtractor {
    private final ResumeProcessingProperties limits;
    public PdfBoxResumeTextExtractor(ResumeProcessingProperties limits) { this.limits = limits; }
    @Override public Extraction extract(byte[] pdf) {
        if (pdf.length > limits.maxBytes()) throw new ResumeTooLargeException();
        try (var document = Loader.loadPDF(pdf)) {
            if (document.isEncrypted()) throw new ResumeParsingException("Encrypted PDFs are not supported; upload an unencrypted PDF");
            if (document.getNumberOfPages() > limits.maxPages())
                throw new ResumeProcessingLimitException("PDF exceeds the configured page limit");
            var text = new StringBuilder();
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.writeText(document, new Writer() {
                @Override public void write(char[] chars, int offset, int length) {
                    if ((long) text.length() + length > limits.maxCharacters())
                        throw new ResumeProcessingLimitException("PDF exceeds the configured extracted-text limit");
                    text.append(chars, offset, length);
                }
                @Override public void flush() {}
                @Override public void close() {}
            });
            // PostgreSQL TEXT cannot contain NUL.
            String extracted = text.toString().replace("\u0000", "");
            if (extracted.codePoints().filter(Character::isLetterOrDigit).count() < limits.minMeaningfulCharacters())
                throw new EmptyResumeTextException();
            return new Extraction(extracted, document.getNumberOfPages(), "pdfbox-3.0.8");
        } catch (InvalidPasswordException exception) {
            throw new ResumeParsingException("Encrypted PDFs are not supported; upload an unencrypted PDF", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new ResumeParsingException("PDF could not be read; upload a valid text-based PDF", exception);
        }
    }
}
