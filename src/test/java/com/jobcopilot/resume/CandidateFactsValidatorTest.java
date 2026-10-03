package com.jobcopilot.resume;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.jobcopilot.resume.ProfessionalLink.Type.*;

class CandidateFactsValidatorTest {
    @Test void canonicalizationPreservesMeaningfulEscapesAndQueryAndStripsCredentialsAndFragment() {
        assertEquals("https://example.com/a%20b?q=a%2Fb", CandidateFactsValidator.normalizeUrl("https://user:password@EXAMPLE.com:443/a%20b?q=a%2Fb#bio"));
        assertEquals("https://github.com/ada",CandidateFactsValidator.normalizeUrl("github.com/ada"));
        assertEquals(List.of(new ProfessionalLink(GITHUB,"https://github.com/ada")), CandidateFactsValidator.canonicalLinks(List.of(
                new ProfessionalLink(GITHUB,"https://github.com/ada#bio"),new ProfessionalLink(GITHUB,"https://github.com/ada"))));
    }
    @ParameterizedTest @ValueSource(strings = {"javascript:alert(1)","file:///etc/file","https://localhost/a","https://github.com.evil.com/user","https://github.com/ada/repo","https://github.com/login","https://github.com/%2f"})
    void unsafeOrNonProfileUrlsCannotBeGithubLinks(String url) {
        assertThrows(IllegalArgumentException.class,()->CandidateFactsValidator.canonicalLinks(List.of(new ProfessionalLink(GITHUB,url))));
    }
    @Test void linksAndEvidenceAreBoundedAndImmutable() {
        assertThrows(IllegalArgumentException.class,()->CandidateFactsValidator.canonicalLinks(Collections.nCopies(21,new ProfessionalLink(WEBSITE,"https://example.com"))));
        assertThrows(IllegalArgumentException.class,()->CandidateFactsValidator.normalizeUrl("https://example.com/" + "a".repeat(2048)));
        var facts = new CandidateProfileFacts(null,null,null,null,null,null,null,null,null,List.of(new ProfessionalLink(WEBSITE,"https://example.com")));
        assertThrows(UnsupportedOperationException.class,()->facts.professionalLinks().clear());
        assertThrows(IllegalArgumentException.class,()->new CandidateFactExtractionData.FactEvidence("email","a@example.com",null,
                CandidateFactExtractionData.Confidence.HIGH,CandidateFactExtractionData.Derivation.HEADER_TEXT,"x".repeat(513),1,"line",null,null));
        assertFalse(facts.toString().contains("example.com"));
    }
}
