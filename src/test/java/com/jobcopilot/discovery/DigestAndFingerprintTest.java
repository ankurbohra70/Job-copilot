package com.jobcopilot.discovery;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DigestAndFingerprintTest {
    @Test void providerDigestIsStableNullSafeAndSensitiveToEveryMappedField() {
        ProviderContentDigest digest = new ProviderContentDigest();
        String baseline = digest.calculate("Title", null, null, "https://host", "https://apply");
        assertEquals(baseline, digest.calculate("Title", null, null, "https://host", "https://apply"));
        assertNotEquals(baseline, digest.calculate("Other", null, null, "https://host", "https://apply"));
        assertNotEquals(baseline, digest.calculate("Title", "Remote", null, "https://host", "https://apply"));
        assertNotEquals(baseline, digest.calculate("Title", null, "Text", "https://host", "https://apply"));
        assertNotEquals(baseline, digest.calculate("Title", null, null, "https://other", "https://apply"));
        assertNotEquals(baseline, digest.calculate("Title", null, null, "https://host", "https://other"));
        assertTrue(baseline.matches("lever-content-v1:sha256:[0-9a-f]{64}"));
    }

    @Test void extractionFingerprintIncludesDescriptionAndExplicitExtractorVersion() {
        ExtractionFingerprint fingerprint = new ExtractionFingerprint();
        String baseline = fingerprint.calculate("jc005-v1", "Java role");
        assertEquals(baseline, fingerprint.calculate("jc005-v1", "Java role"));
        assertNotEquals(baseline, fingerprint.calculate("jc005-v1", "SQL role"));
        assertNotEquals(baseline, fingerprint.calculate("jc005-v2", "Java role"));
        assertTrue(baseline.matches("jc005-v1:sha256:[0-9a-f]{64}"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint.calculate("unsafe version", "text"));
    }
}
