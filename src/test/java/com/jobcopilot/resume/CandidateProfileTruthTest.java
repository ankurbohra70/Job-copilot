package com.jobcopilot.resume;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CandidateProfileTruthTest {
    @Test void unknownSensitiveFactsAndCompensationRemainUnknown() {
        CandidateProfile profile = new CandidateProfile(emptyData(), LocalDate.of(2026, 9, 13));
        profile.replaceFacts(new CandidateProfileFacts("Candidate", null, null, null, null, null,
                null, null, null, null, null, null, null));

        assertEquals(CandidateFactState.UNKNOWN, profile.facts().workAuthorization());
        assertEquals(CandidateFactState.UNKNOWN, profile.facts().sponsorshipRequired());
        assertEquals(CandidateFactState.UNKNOWN, profile.facts().relocationWilling());
        assertNull(profile.facts().minimumCompensation());
        assertNull(profile.facts().desiredCompensation());
        assertNull(profile.facts().compensationCurrency());
    }

    @Test void currencyWithoutAnAmountIsRejected() {
        CandidateProfile profile = new CandidateProfile(emptyData(), LocalDate.of(2026, 9, 13));
        CandidateProfileFacts invalid = new CandidateProfileFacts("Candidate", null, null, null, null, null,
                null, null, null, null, "INR", null, null);
        assertThrows(IllegalArgumentException.class, () -> profile.replaceFacts(invalid));
    }

    private static CandidateProfileData emptyData() {
        return new CandidateProfileData(List.of(), null, 0, CandidateProfileData.Assessment.UNKNOWN,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
