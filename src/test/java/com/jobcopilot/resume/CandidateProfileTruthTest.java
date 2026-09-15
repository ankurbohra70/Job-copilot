package com.jobcopilot.resume;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CandidateProfileTruthTest {
    @Test void unknownSensitiveCandidateFactsRemainUnknown() {
        CandidateProfile profile = new CandidateProfile(emptyData(), LocalDate.of(2026, 9, 13));
        profile.replaceFacts(new CandidateProfileFacts("Candidate", null, null, null, null, null,
                null, null, null));

        assertEquals(CandidateFactState.UNKNOWN, profile.facts().workAuthorization());
        assertEquals(CandidateFactState.UNKNOWN, profile.facts().sponsorshipRequired());
    }

    @Test void directDomainUpdatesEnforceFactBoundsAndEmail() {
        CandidateProfile profile = new CandidateProfile(emptyData(), LocalDate.of(2026, 9, 13));
        assertThrows(IllegalArgumentException.class, () -> profile.replaceFacts(
                new CandidateProfileFacts("Candidate", "not-an-email", null, null, null,
                        null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> profile.replaceFacts(
                new CandidateProfileFacts("Candidate", null, null, null, null,
                        961, null, null, null)));
    }

    private static CandidateProfileData emptyData() {
        return new CandidateProfileData(List.of(), null, 0, CandidateProfileData.Assessment.UNKNOWN,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
