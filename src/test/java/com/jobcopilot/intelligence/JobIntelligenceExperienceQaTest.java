package com.jobcopilot.intelligence;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.jobcopilot.intelligence.JobIntelligence.*;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class JobIntelligenceExperienceQaTest {
    private final MinimumExperienceDeriver deriver = new MinimumExperienceDeriver();
    private static ExperienceClause clause(String text, Importance importance, Scope scope, boolean conditional) {
        var form = ExperienceForm.parse(text);
        return new ExperienceClause(text, form.lower(), form.unit(), importance, scope, conditional, List.of("e1"));
    }
    @Test void eligibilityPrecedenceAndRangeIntersectionUseExactArithmetic() {
        var required = clause("3 years", Importance.REQUIRED, Scope.OVERALL, false);
        var five = clause("5 years", Importance.REQUIRED, Scope.OVERALL, false);
        known(List.of(required), "36");
        known(List.of(required, five), "60");
        known(List.of(five, clause("7 years", Importance.PREFERRED, Scope.OVERALL, false)), "60");
        known(List.of(clause("3-5 years", Importance.REQUIRED, Scope.OVERALL, false),
                clause("4 years", Importance.REQUIRED, Scope.OVERALL, false)), "48");
        for (var ignored : List.of(clause("7 years", Importance.PREFERRED, Scope.OVERALL, false),
                clause("7 years", Importance.UNSPECIFIED, Scope.OVERALL, false),
                clause("7 years", Importance.REQUIRED, Scope.RELEVANT, false),
                clause("7 years", Importance.REQUIRED, Scope.SKILL_SPECIFIC, false))) {
            var result = deriver.derive(List.of(ignored), List.of());
            assertEquals(MinimumStatus.NOT_STATED, result.status()); assertNull(result.months());
        }
        for (var clauses : List.of(List.of(clause("3 years", Importance.REQUIRED, Scope.OVERALL, true)),
                List.of(clause("more than 3 years", Importance.REQUIRED, Scope.OVERALL, false)),
                List.of(clause("3-5 years", Importance.REQUIRED, Scope.OVERALL, false),
                        clause("8 years", Importance.REQUIRED, Scope.OVERALL, false)))) {
            var result = deriver.derive(clauses, List.of());
            assertEquals(MinimumStatus.AMBIGUOUS, result.status()); assertNull(result.months());
        }
        known(List.of(clause("0 years", Importance.REQUIRED, Scope.OVERALL, false)), "0");
        known(List.of(clause("1.25 years", Importance.REQUIRED, Scope.OVERALL, false)), "15");
    }
    @Test void pairedNullPolicyIsNarrowerThanSchema() {
        for (String[] pair : new String[][] {{null, "YEARS"}, {"3", null}}) {
            var result = assertInstanceOf(JobIntelligenceResult.Failed.class, JobIntelligenceAdversarialQaTest.runJson(
                    withExperience("3 years", pair[0], pair[1], "REQUIRED", "OVERALL", false, "Must have 3 years"), "Must have 3 years"));
            assertEquals(JobIntelligenceResult.Code.INCONSISTENT_EXPERIENCE, result.failure().code());
        }
        var exclusive = assertInstanceOf(JobIntelligenceResult.Accepted.class, JobIntelligenceAdversarialQaTest.runJson(
                withExperience("more than 3 years", null, null, "REQUIRED", "OVERALL", false, "Must have more than 3 years"), "Must have more than 3 years"));
        assertEquals(MinimumStatus.AMBIGUOUS, exclusive.intelligence().minimumExperience().status());
        var inclusive = assertInstanceOf(JobIntelligenceResult.Failed.class, JobIntelligenceAdversarialQaTest.runJson(
                withExperience("3 years", null, null, "REQUIRED", "OVERALL", false, "Must have 3 years"), "Must have 3 years"));
        assertEquals(JobIntelligenceResult.Code.INCONSISTENT_EXPERIENCE, inclusive.failure().code());
    }
    @Test void valuesAndUnitsMustMatchSource() {
        for (String[] mismatch : new String[][] {{"3+ years", "5", "YEARS"}, {"18 months", "18", "YEARS"}, {"3-5 years", "4", "YEARS"}}) {
            String raw = "Must have " + mismatch[0];
            var result = assertInstanceOf(JobIntelligenceResult.Failed.class, JobIntelligenceAdversarialQaTest.runJson(
                    withExperience(mismatch[0], mismatch[1], mismatch[2], "REQUIRED", "OVERALL", false, raw), raw));
            assertEquals(JobIntelligenceResult.Code.INCONSISTENT_EXPERIENCE, result.failure().code());
        }
        for (String form : List.of("3+ years", "at least 3 years", "minimum 3 years", "3-5 years", "3 – 5 years", "3 — 5 years")) {
            String raw = "Must have " + form;
            var accepted = assertInstanceOf(JobIntelligenceResult.Accepted.class, JobIntelligenceAdversarialQaTest.runJson(
                    withExperience(form, "3", "YEARS", "REQUIRED", "OVERALL", false, raw), raw));
            assertEquals(0, new BigDecimal("36").compareTo(accepted.intelligence().minimumExperience().months()));
        }
    }
    @Test void uncertaintyTargetIsIgnoredAndOnlyAmbiguousExperienceCodeChangesDerivation() {
        for (var code : UncertaintyCode.values()) {
            var result = deriver.derive(List.of(), List.of(new Uncertainty(code, "unrelated", List.of()), new Uncertainty(code, "unrelated", List.of())));
            assertEquals(code == UncertaintyCode.AMBIGUOUS_EXPERIENCE ? MinimumStatus.AMBIGUOUS : MinimumStatus.NOT_STATED, result.status());
            assertNull(result.months());
        }
    }
    private void known(List<ExperienceClause> clauses, String months) {
        var result = deriver.derive(clauses, List.of());
        assertEquals(MinimumStatus.KNOWN, result.status());
        assertEquals(0, new BigDecimal(months).compareTo(result.months()));
    }
}
