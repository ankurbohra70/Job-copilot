package com.jobcopilot.resume;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.jobcopilot.resume.ResumeExceptions.*;

class ResumeServiceTest {
    private final ResumeTextExtractor extractor = mock(ResumeTextExtractor.class);
    private final ResumePersistenceService persistence = mock(ResumePersistenceService.class);
    private final ResumeService service = new ResumeService(extractor,new DeterministicProfileParser(),persistence,new ResumeProcessingProperties(100,25,200000,50));
    @Test void rejectsEmptyBeforeParser() {
        assertThrows(InvalidResumeFileException.class, () -> service.upload(new MockMultipartFile("file",new byte[0])));
        verifyNoInteractions(extractor,persistence);
    }
    @Test void rejectsMetadataAndSpoofedContent() {
        assertThrows(UnsupportedResumeTypeException.class, () -> service.upload(new MockMultipartFile("file","a.doc","application/pdf","%PDF-".getBytes())));
        assertThrows(UnsupportedResumeTypeException.class, () -> service.upload(new MockMultipartFile("file","a.pdf","image/png","%PDF-".getBytes())));
        assertThrows(UnsupportedResumeTypeException.class, () -> service.upload(new MockMultipartFile("file","a.pdf","application/pdf","fake".getBytes())));
        verifyNoInteractions(extractor,persistence);
    }
    @Test void rejectsLargeBeforeParser() {
        assertThrows(ResumeTooLargeException.class, () -> service.upload(new MockMultipartFile("file","a.pdf","application/pdf",new byte[101])));
        verifyNoInteractions(extractor,persistence);
    }
    @Test void failureNeverPersists() {
        when(extractor.extract(any())).thenThrow(new ResumeParsingException("bad pdf"));
        assertThrows(ResumeParsingException.class, () -> service.upload(new MockMultipartFile("file","a.pdf",null,"%PDF-".getBytes())));
        verifyNoInteractions(persistence);
    }
    @Test void acceptsGenericMimeAndSanitizesNameWithoutTransaction() {
        when(extractor.extract(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new ResumeTextExtractor.Extraction("Java backend experience",1,"test");
        });
        service.upload(new MockMultipartFile("file","C:\\unsafe\\resume.PDF","application/octet-stream","%PDF-".getBytes()));
        verify(persistence).save(eq("resume.PDF"),eq(5L),eq("Java backend experience"),eq(1),eq("test"),any(),any(),eq("rules-v1"),eq("v1"));
    }
}

