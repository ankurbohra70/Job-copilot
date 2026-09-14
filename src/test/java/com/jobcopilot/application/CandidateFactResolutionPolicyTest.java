package com.jobcopilot.application;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CandidateFactResolutionPolicyTest {
    private final CandidateFactResolutionPolicy policy = new CandidateFactResolutionPolicy();

    @Test void missingSensitiveFactsRequireUserAndLegalAnswersAreNeverAutofilled() {
        assertEquals(CandidateFactResolution.REQUIRES_USER,
                policy.resolve(CandidateFactType.WORK_AUTHORIZATION, false));
        assertEquals(CandidateFactResolution.REQUIRES_CONFIRMATION,
                policy.resolve(CandidateFactType.WORK_AUTHORIZATION, true));
        assertEquals(CandidateFactResolution.PROHIBITED_AUTOFILL,
                policy.resolve(CandidateFactType.LEGAL_DECLARATION, true));
        assertEquals(CandidateFactResolution.PROHIBITED_AUTOFILL,
                policy.resolve(CandidateFactType.VOLUNTARY_SELF_IDENTIFICATION, false));
    }
}
