package com.jobcopilot.resume;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.resume.dto.ResumeResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import static com.jobcopilot.resume.ResumeExceptions.*;

@Service
public class ResumeService {
    private final ResumeTextExtractor extractor;
    private final DeterministicProfileParser parser;
    private final ResumePersistenceService persistence;
    private final ResumeProcessingProperties limits;
    public ResumeService(ResumeTextExtractor extractor, DeterministicProfileParser parser,
            ResumePersistenceService persistence, ResumeProcessingProperties limits) {
        this.extractor = extractor; this.parser = parser; this.persistence = persistence; this.limits = limits;
    }
    // Deliberately not transactional: untrusted PDF parsing never holds a DB transaction.
    public ResumeResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new InvalidResumeFileException("A nonempty file part named file is required");
        if (file.getSize() > limits.maxBytes()) throw new ResumeTooLargeException();
        String name = file.getOriginalFilename();
        String type = file.getContentType();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                || type != null && !type.isBlank() && !type.equalsIgnoreCase("application/pdf") && !type.equalsIgnoreCase("application/octet-stream"))
            throw new UnsupportedResumeTypeException();
        byte[] bytes;
        try (var input = file.getInputStream()) { bytes = input.readNBytes(limits.maxBytes() + 1); }
        catch (IOException exception) { throw new ResumeParsingException("Upload could not be read", exception); }
        if (bytes.length > limits.maxBytes()) throw new ResumeTooLargeException();
        if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) throw new UnsupportedResumeTypeException();
        var extraction = extractor.extract(bytes);
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        var data = parser.parse(extraction.text(), date);
        String displayName = name.replace('\\', '/');
        displayName = displayName.substring(displayName.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (displayName.length() > 255) displayName = displayName.substring(displayName.length() - 255);
        return persistence.save(displayName, bytes.length, extraction.text(), extraction.pageCount(), extraction.version(),
                data, date, DeterministicProfileParser.VERSION, MatchingVocabulary.standard().version());
    }
}

