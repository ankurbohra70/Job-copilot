package com.jobcopilot.intelligence;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.jobcopilot.intelligence.JobIntelligence.ExperienceClause;
import static com.jobcopilot.intelligence.JobIntelligence.Importance;
import static com.jobcopilot.intelligence.JobIntelligence.MinimumStatus;
import static com.jobcopilot.intelligence.JobIntelligence.Scope;
import static com.jobcopilot.intelligence.JobIntelligence.Uncertainty;
import static com.jobcopilot.intelligence.JobIntelligence.UncertaintyCode;
import static com.jobcopilot.intelligence.JobIntelligence.Unit;
import static org.junit.jupiter.api.Assertions.*;

class MinimumExperienceDeriverTest {
    private final MinimumExperienceDeriver deriver = new MinimumExperienceDeriver();

    @Test void explicitZeroIsKnown() {
        var result = deriver.derive(List.of(clause("0 years", new BigDecimal("0"), Unit.YEARS, Importance.REQUIRED, Scope.OVERALL, false)), List.of());
        assertEquals(MinimumStatus.KNOWN, result.status());
        assertEquals(new BigDecimal("0"), result.months());
    }

    @Test void experienceUncertaintyIsAmbiguous() {
        var result = deriver.derive(List.of(), List.of(new Uncertainty(UncertaintyCode.AMBIGUOUS_EXPERIENCE, "facts.experienceClauses", List.of())));
        assertEquals(MinimumStatus.AMBIGUOUS, result.status());
        assertNull(result.months());
    }

    @Test void emptyClausesAreNotStated() {
        var result = deriver.derive(List.of(), List.of());
        assertEquals(MinimumStatus.NOT_STATED, result.status());
        assertNull(result.months());
    }

    @Test void knownRequiresNonNegativeMonths() {
        var result = deriver.derive(List.of(clause("2.5 years", new BigDecimal("2.5"), Unit.YEARS, Importance.REQUIRED, Scope.OVERALL, false)), List.of());
        assertEquals(MinimumStatus.KNOWN, result.status());
        assertEquals(0, new BigDecimal("30.0").compareTo(result.months()));
    }

    private static ExperienceClause clause(String text, BigDecimal minimum, Unit unit, Importance importance, Scope scope, boolean conditional) {
        return new ExperienceClause(text, minimum, unit, importance, scope, conditional, List.of("e1"));
    }
}
